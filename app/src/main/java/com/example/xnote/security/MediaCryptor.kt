package com.example.xnote.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.xnote.utils.SecurityLog
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 媒体文件加解密。采用信封加密（envelope encryption）。
 *
 * 落盘格式（当前写入 XNC2）：
 *   [magic 4B "XNC2"][wrapIv 12B][wrappedKey 48B][dataIv 12B][ciphertext + GCM tag]
 *
 * 仍兼容读取旧格式 XNC1：
 *   [magic 4B "XNC1"][iv 12B][ciphertext + GCM tag]
 *
 * 为什么要分两层：
 * XNC1 把整个文件直接喂给 [MasterKeyManager] 的 Keystore 主密钥，而该密钥优先建在
 * StrongBox（独立安全芯片）里——每个数据块都要经慢速总线送进那颗芯片运算，
 * 实测吞吐只有几十 KB/s 量级。一张 500KB 的图要 5~8 秒，表现为「添加图片极慢」，
 * 以及点开大图时主线程卡死触发 ANR。设备越新（带 StrongBox）越严重，
 * 没有 StrongBox 的机器落回 TEE 反而快得多——这正是 Android 12 正常、
 * Android 16 极慢的原因。
 *
 * XNC2 改为：每个文件随机生成一把 32 字节 AES-256 数据密钥，用软件 provider
 * （Conscrypt，走 ARMv8 加密指令，GB/s 级）加解密文件本体；只把这 32 字节数据密钥
 * 送进 Keystore 包装。StrongBox 处理 32 字节是瞬时的，安全性不变：
 * 主密钥依旧不可导出，dump 出加密文件没有 Keystore 一样解不开。
 *
 * 单次 in-memory 加解密，文件大小受 [MAX_PLAINTEXT_SIZE] 限制；
 * 与现有 50MB ZIP 上限保持一致。
 */
object MediaCryptor {

    private val MAGIC_V1 = byteArrayOf(0x58, 0x4E, 0x43, 0x31) // "XNC1"
    private val MAGIC_V2 = byteArrayOf(0x58, 0x4E, 0x43, 0x32) // "XNC2"
    private const val MAGIC_SIZE = 4
    private const val IV_SIZE = 12
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_SIZE = 16
    private const val DATA_KEY_SIZE = 32                        // AES-256
    private const val WRAPPED_KEY_SIZE = DATA_KEY_SIZE + GCM_TAG_SIZE
    private const val MAX_PLAINTEXT_SIZE: Long = 64L * 1024 * 1024 // 64 MB
    private const val DECRYPT_TMP_DIR = "decrypt_tmp"

    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private val secureRandom by lazy { SecureRandom() }

    private const val KEY_CACHE_MAX = 16

    /**
     * 已解包的数据密钥缓存，key 是该文件头里那段 wrappedKey 的十六进制。
     *
     * Keystore（尤其 StrongBox）每次调用都要往安全芯片走一趟，这个固定往返开销
     * 与数据量无关。而一次「添加图片」会对同一个文件连续解包好几次
     * （取尺寸、画缩略图、点开大图），每次都白付一趟。用 wrappedKey 当 key 的好处是
     * 文件内容一变、wrappedKey 必变，缓存自动失效，不需要盯 mtime。
     *
     * 代价：最近用过的若干把数据密钥会驻留在堆内存里（16 × 32 字节）。
     * 主密钥仍然只在 Keystore 内、不可导出；而解密后的图片本体本来也在堆上，
     * 所以这点暴露面的增加是可接受的。需要时可调 [clearKeyCache] 主动清掉。
     */
    private val keyCache = object : LinkedHashMap<String, ByteArray>(KEY_CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean {
            if (size > KEY_CACHE_MAX) {
                eldest.value.fill(0)
                return true
            }
            return false
        }
    }

    private fun cacheKeyOf(wrappedKey: ByteArray): String {
        val sb = StringBuilder(wrappedKey.size * 2)
        for (b in wrappedKey) sb.append("%02x".format(b))
        return sb.toString()
    }

    private fun cachedDataKey(wrappedKey: ByteArray): ByteArray? =
        synchronized(keyCache) { keyCache[cacheKeyOf(wrappedKey)]?.copyOf() }

    private fun rememberDataKey(wrappedKey: ByteArray, dataKey: ByteArray) {
        synchronized(keyCache) { keyCache[cacheKeyOf(wrappedKey)] = dataKey.copyOf() }
    }

    /** 清空数据密钥缓存（例如切到后台、或需要收紧内存暴露面时） */
    fun clearKeyCache() {
        synchronized(keyCache) {
            keyCache.values.forEach { it.fill(0) }
            keyCache.clear()
        }
    }

    /** 读文件头 4 字节；不是可识别的容器则返回 null */
    private fun magicOf(file: File): ByteArray? {
        if (!file.exists() || file.length() < (MAGIC_SIZE + IV_SIZE).toLong()) return null
        val header = ByteArray(MAGIC_SIZE)
        return try {
            val read = FileInputStream(file).use { it.read(header) }
            if (read != MAGIC_SIZE) null else header
        } catch (_: IOException) {
            null
        }
    }

    fun isEncryptedFile(file: File): Boolean {
        val m = magicOf(file) ?: return false
        return m.contentEquals(MAGIC_V2) || m.contentEquals(MAGIC_V1)
    }

    fun encryptBytes(plain: ByteArray, dest: File) {
        require(plain.size.toLong() <= MAX_PLAINTEXT_SIZE) { "Plaintext too large" }

        // 1) 本文件专属的随机数据密钥，纯软件生成，不进 Keystore
        val dataKeyBytes = ByteArray(DATA_KEY_SIZE).also { secureRandom.nextBytes(it) }

        // 2) 用软件 provider 加密文件本体（SecretKeySpec 会走 Conscrypt，硬件加速）
        val dataCipher = Cipher.getInstance(TRANSFORMATION)
        dataCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dataKeyBytes, "AES"))
        val dataIv = dataCipher.iv
        val ct = dataCipher.doFinal(plain)

        // 3) 只把 32 字节数据密钥送进 Keystore 包装——StrongBox 处理这点数据是瞬时的
        val wrapCipher = Cipher.getInstance(TRANSFORMATION)
        wrapCipher.init(Cipher.ENCRYPT_MODE, MasterKeyManager.getOrCreateKey())
        val wrapIv = wrapCipher.iv
        val wrappedKey = wrapCipher.doFinal(dataKeyBytes)
        // 先入缓存：刚写完的文件马上就会被读回去画缩略图，省掉那趟 Keystore 往返
        rememberDataKey(wrappedKey, dataKeyBytes)
        dataKeyBytes.fill(0)

        check(wrapIv.size == IV_SIZE) { "unexpected wrap iv size" }
        check(dataIv.size == IV_SIZE) { "unexpected data iv size" }
        check(wrappedKey.size == WRAPPED_KEY_SIZE) { "unexpected wrapped key size" }

        dest.parentFile?.mkdirs()
        dest.outputStream().use { out ->
            out.write(MAGIC_V2)
            out.write(wrapIv)
            out.write(wrappedKey)
            out.write(dataIv)
            out.write(ct)
        }
    }

    fun encryptStream(input: InputStream, dest: File) {
        encryptBytes(input.readBytes(), dest)
    }

    /**
     * 把已存在的明文文件就地替换为加密文件。已加密的文件直接返回。
     */
    fun encryptInPlace(file: File) {
        if (!file.exists() || isEncryptedFile(file)) return
        val tmp = File(file.parentFile, "${file.name}.enc.tmp")
        try {
            encryptBytes(file.readBytes(), tmp)
            if (!file.delete()) throw IOException("delete plaintext failed: ${file.name}")
            if (!tmp.renameTo(file)) throw IOException("rename encrypted failed: ${file.name}")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    fun decryptBytes(src: File): ByteArray {
        val magic = magicOf(src)
        require(magic != null) { "Not an encrypted file: ${src.name}" }
        val all = src.readBytes()
        return when {
            magic.contentEquals(MAGIC_V2) -> decryptV2(all, src)
            magic.contentEquals(MAGIC_V1) -> decryptV1(all)
            else -> throw IllegalArgumentException("Unknown container magic: ${src.name}")
        }
    }

    /** 信封格式：先用 Keystore 解出 32 字节数据密钥，再用软件密钥解文件本体 */
    private fun decryptV2(all: ByteArray, src: File): ByteArray {
        val minSize = MAGIC_SIZE + IV_SIZE + WRAPPED_KEY_SIZE + IV_SIZE
        require(all.size > minSize) { "Truncated XNC2 file: ${src.name}" }

        var p = MAGIC_SIZE
        val wrapIv = all.copyOfRange(p, p + IV_SIZE); p += IV_SIZE
        val wrappedKey = all.copyOfRange(p, p + WRAPPED_KEY_SIZE); p += WRAPPED_KEY_SIZE
        val dataIv = all.copyOfRange(p, p + IV_SIZE); p += IV_SIZE
        val ct = all.copyOfRange(p, all.size)

        val dataKeyBytes = cachedDataKey(wrappedKey) ?: run {
            val wrapCipher = Cipher.getInstance(TRANSFORMATION)
            wrapCipher.init(
                Cipher.DECRYPT_MODE,
                MasterKeyManager.getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, wrapIv)
            )
            wrapCipher.doFinal(wrappedKey).also { rememberDataKey(wrappedKey, it) }
        }
        try {
            val dataCipher = Cipher.getInstance(TRANSFORMATION)
            dataCipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(dataKeyBytes, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, dataIv)
            )
            return dataCipher.doFinal(ct)
        } finally {
            dataKeyBytes.fill(0)
        }
    }

    /** 旧格式：整个文件直接过 Keystore 主密钥。慢，但既有文件必须仍能读 */
    private fun decryptV1(all: ByteArray): ByteArray {
        val iv = all.copyOfRange(MAGIC_SIZE, MAGIC_SIZE + IV_SIZE)
        val ct = all.copyOfRange(MAGIC_SIZE + IV_SIZE, all.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            MasterKeyManager.getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        return cipher.doFinal(ct)
    }

    /**
     * 通用读：加密文件→解密返回；非加密（迁移过渡态）→直接返回原字节。
     *
     * 读到旧格式 XNC1 时顺手改写为 XNC2。XNC1 每次读都要把整个文件过一遍
     * StrongBox（几十 KB/s），升级一次之后就永久走软件密钥了。
     * 迁移失败不影响本次读取，下次再试。
     */
    fun readAll(file: File): ByteArray {
        val magic = magicOf(file) ?: return file.readBytes()
        val plain = decryptBytes(file)
        if (magic.contentEquals(MAGIC_V1)) {
            try {
                rewriteAsV2(file, plain)
                SecurityLog.i("MediaCryptor", "Upgraded container XNC1 to XNC2")
            } catch (t: Throwable) {
                SecurityLog.w("MediaCryptor", "XNC1 to XNC2 upgrade failed, kept as is")
            }
        }
        return plain
    }

    /** 原子改写：先写临时文件再 rename，中途失败不会留下半个坏文件 */
    private fun rewriteAsV2(file: File, plain: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".v2.tmp")
        try {
            encryptBytes(plain, tmp)
            if (!tmp.renameTo(file)) throw IOException("rename failed: " + file.name)
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    /**
     * 通用 InputStream，调用方关闭后要确保后续不再持有解密后的字节缓存。
     */
    fun openInput(file: File): InputStream =
        if (isEncryptedFile(file)) ByteArrayInputStream(decryptBytes(file)) else FileInputStream(file)

    /**
     * 解密到 cache 临时文件，返回该文件路径。
     * 用于必须接受 File/path 输入的系统组件（MediaPlayer / MediaMetadataRetriever / 大图查看器）。
     * 调用方使用完应调用 [releaseDecryptedTemp]。
     */
    fun decryptToCache(context: Context, src: File, suffix: String): File {
        val dir = File(context.cacheDir, DECRYPT_TMP_DIR).apply { mkdirs() }
        val tmp = File(dir, "tmp_${UUID.randomUUID()}$suffix")
        if (isEncryptedFile(src)) {
            tmp.writeBytes(decryptBytes(src))
        } else {
            // 迁移过渡：源是明文，复制即可
            src.copyTo(tmp, overwrite = true)
        }
        return tmp
    }

    /**
     * 删除由 [decryptToCache] 产生的临时文件。
     */
    fun releaseDecryptedTemp(file: File?) {
        if (file == null) return
        try {
            if (file.exists() && file.parentFile?.name == DECRYPT_TMP_DIR) {
                if (!file.delete()) {
                    SecurityLog.w("MediaCryptor", "Failed to delete decrypted temp")
                }
            }
        } catch (e: Exception) {
            SecurityLog.e("MediaCryptor", "releaseDecryptedTemp error", e)
        }
    }

    /**
     * 后台一次性把私有目录下的 XNC1 容器升级成 XNC2。
     *
     * 光靠 [readAll] 里的懒迁移不够：[com.example.xnote.ui.ImageMediaSpan] 是在主线程
     * 解密缩略图的，一条纪事里有几张旧图，打开时就会在主线程连续做几次
     * StrongBox 整文件解密，直接 ANR。所以启动时先在后台把存量升级掉。
     *
     * 单个文件失败就跳过，留给懒迁移下次再试；用最低优先级线程，不与启动争资源。
     */
    fun upgradeLegacyContainersAsync(context: Context) {
        val thread = Thread({
            var upgraded = 0
            var skipped = 0
            for (name in arrayOf("images", "audios", "files")) {
                val files = File(context.filesDir, name).listFiles() ?: continue
                for (f in files) {
                    if (!f.isFile) continue
                    if (magicOf(f)?.contentEquals(MAGIC_V1) != true) continue
                    try {
                        rewriteAsV2(f, decryptV1(f.readBytes()))
                        upgraded++
                    } catch (t: Throwable) {
                        skipped++
                    }
                }
            }
            if (upgraded > 0 || skipped > 0) {
                SecurityLog.i(
                    "MediaCryptor",
                    "Legacy container upgrade done: upgraded=" + upgraded + " skipped=" + skipped
                )
            }
        }, "xnc-upgrade")
        thread.priority = Thread.MIN_PRIORITY
        thread.start()
    }

    /**
     * 启动时清理上次遗留的解密临时文件。
     */
    fun cleanupDecryptedTempDir(context: Context) {
        val dir = File(context.cacheDir, DECRYPT_TMP_DIR)
        if (!dir.exists()) return
        dir.listFiles()?.forEach { it.delete() }
    }

    fun decodeBitmapBounds(src: File): BitmapFactory.Options {
        val bytes = readAll(src)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return opts
    }

    fun decodeBitmapSampled(src: File, maxWidth: Int, maxHeight: Int): Bitmap? {
        val t0 = System.currentTimeMillis()
        val bytes = readAll(src)
        val t1 = System.currentTimeMillis()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        if (bounds.outWidth > maxWidth || bounds.outHeight > maxHeight) {
            val halfW = bounds.outWidth / 2
            val halfH = bounds.outHeight / 2
            while (halfW / sample >= maxWidth && halfH / sample >= maxHeight) sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val t2 = System.currentTimeMillis()
        SecurityLog.i(
            "Perf",
            "decodeSampled 解密=" + (t1 - t0) + "ms 解码=" + (t2 - t1) +
                "ms 上限=" + maxWidth + "x" + maxHeight + " sample=" + sample +
                " 源=" + bounds.outWidth + "x" + bounds.outHeight
        )
        return bmp
    }

    /**
     * 全尺寸解码。大图会直接吃掉几十 MB 堆并可能抛 OutOfMemoryError，
     * 只在确知图片很小、且不在主线程时用；显示用途请走 [decodeBitmapSampled]。
     */
    fun decodeBitmapFull(src: File): Bitmap? {
        val bytes = readAll(src)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
