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
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * 媒体文件加解密。文件落盘格式：
 *   [magic 4B "XNC1"][iv 12B][ciphertext + GCM tag]
 *
 * 使用 [MasterKeyManager] 提供的 Keystore AES-256-GCM 主密钥。
 * 设备 root 也无法直接读出原始字节（即便 dump 出加密文件，没有 Keystore 也解不开）。
 *
 * 单次 in-memory 加解密，文件大小受 [MAX_PLAINTEXT_SIZE] 限制；
 * 与现有 50MB ZIP 上限保持一致。
 */
object MediaCryptor {

    private val MAGIC = byteArrayOf(0x58, 0x4E, 0x43, 0x31) // "XNC1"
    private const val IV_SIZE = 12
    private const val GCM_TAG_BITS = 128
    private const val MAX_PLAINTEXT_SIZE: Long = 64L * 1024 * 1024 // 64 MB
    private const val DECRYPT_TMP_DIR = "decrypt_tmp"

    fun isEncryptedFile(file: File): Boolean {
        if (!file.exists() || file.length() < (MAGIC.size + IV_SIZE).toLong()) return false
        val header = ByteArray(MAGIC.size)
        return try {
            FileInputStream(file).use { it.read(header) }
            header.contentEquals(MAGIC)
        } catch (_: IOException) {
            false
        }
    }

    fun encryptBytes(plain: ByteArray, dest: File) {
        require(plain.size.toLong() <= MAX_PLAINTEXT_SIZE) { "Plaintext too large" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, MasterKeyManager.getOrCreateKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        dest.parentFile?.mkdirs()
        dest.outputStream().use { out ->
            out.write(MAGIC)
            out.write(iv)
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
        require(isEncryptedFile(src)) { "Not an XNC1 encrypted file: ${src.name}" }
        val all = src.readBytes()
        val iv = all.copyOfRange(MAGIC.size, MAGIC.size + IV_SIZE)
        val ct = all.copyOfRange(MAGIC.size + IV_SIZE, all.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            MasterKeyManager.getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        return cipher.doFinal(ct)
    }

    /**
     * 通用读：加密文件→解密返回；非加密（迁移过渡态）→直接返回原字节。
     */
    fun readAll(file: File): ByteArray =
        if (isEncryptedFile(file)) decryptBytes(file) else file.readBytes()

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
        val bytes = readAll(src)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        if (bounds.outWidth > maxWidth || bounds.outHeight > maxHeight) {
            val halfW = bounds.outWidth / 2
            val halfH = bounds.outHeight / 2
            while (halfW / sample >= maxWidth && halfH / sample >= maxHeight) sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    fun decodeBitmapFull(src: File): Bitmap? {
        val bytes = readAll(src)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
