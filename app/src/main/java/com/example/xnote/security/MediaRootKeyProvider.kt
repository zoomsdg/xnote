package com.example.xnote.security

import android.content.Context
import android.util.Base64
import com.example.xnote.utils.SecurityLog
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 媒体文件的根密钥：一把 32 字节随机 AES-256 密钥，用 [MasterKeyManager] 的
 * Keystore 主密钥包封后存在 SharedPreferences 里。
 *
 * 存在的理由是性能。XNC2 把每个文件的数据密钥都直接交给 Keystore 包封，
 * 于是**每读一个文件就要走一趟 Keystore**。带 StrongBox 的机器上这趟往返
 * 是固定开销（与数据量无关，实测约半秒），打开一条有十张图的纪事就要
 * 排队做十次，图片只能一张一张往外蹦。
 *
 * 改成根密钥之后：整个进程只在首次用到时解封一次，之后每个文件的数据密钥
 * 都用这把根密钥在软件里包封/解封（微秒级）。
 *
 * 落盘形式与 [DatabaseKeyProvider] 一致：
 *   SharedPreferences("xnote_media_root_key_v1") {
 *     "iv"      : Base64(12B IV)
 *     "wrapped" : Base64(GCM(root_key_bytes))
 *   }
 *
 * 安全取舍（重要）：
 * 根密钥在进程存活期间驻留堆内存，一次内存 dump 即可拿到它，进而离线解开
 * 全部媒体文件——而此前必须能调用本机 Keystore 才行。这确实是一次弱化。
 * 但本应用的数据库早已是同一模型：[DatabaseKeyProvider] 把 SQLCipher 的
 * passphrase 解封后同样常驻内存，全部纪事正文本来就靠它保护。
 * 所以这里只是让媒体与数据库处在同一条水位线上，没有引入新的弱点类别。
 * 落盘仍是加密的：设备 root、直接 dump 出文件，没有 Keystore 依旧解不开。
 */
object MediaRootKeyProvider {

    private const val PREFS = "xnote_media_root_key_v1"
    private const val KEY_IV = "iv"
    private const val KEY_WRAPPED = "wrapped"
    private const val ROOT_KEY_BYTES = 32
    private const val GCM_TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cached: SecretKey? = null

    /** 在 Application.onCreate 里尽早调用，只记 Context，不做任何加解密 */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 取根密钥。首次调用会走一次 Keystore（或首次生成时写一次 SharedPreferences），
     * 之后直接返回内存里的实例。
     */
    @Synchronized
    fun getOrCreate(): SecretKey {
        cached?.let { return it }
        val ctx = requireNotNull(appContext) { "MediaRootKeyProvider.install() 尚未调用" }
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val ivB64 = prefs.getString(KEY_IV, null)
        val wrappedB64 = prefs.getString(KEY_WRAPPED, null)

        val raw = if (ivB64 != null && wrappedB64 != null) {
            unwrap(Base64.decode(ivB64, Base64.NO_WRAP), Base64.decode(wrappedB64, Base64.NO_WRAP))
        } else {
            val fresh = ByteArray(ROOT_KEY_BYTES).also { SecureRandom().nextBytes(it) }
            val (iv, wrapped) = wrap(fresh)
            prefs.edit()
                .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(KEY_WRAPPED, Base64.encodeToString(wrapped, Base64.NO_WRAP))
                .apply()
            SecurityLog.i("MediaRootKeyProvider", "Generated and wrapped new media root key")
            fresh
        }

        val key = SecretKeySpec(raw, "AES")
        raw.fill(0)
        cached = key
        return key
    }

    /** 预热：让首次用到根密钥时不必现场等那趟 Keystore。放后台线程调用即可 */
    fun warmUp() {
        runCatching { getOrCreate() }
            .onFailure { SecurityLog.e("MediaRootKeyProvider", "Root key warm-up failed", it) }
    }

    private fun wrap(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, MasterKeyManager.getOrCreateKey())
        return cipher.iv to cipher.doFinal(plain)
    }

    private fun unwrap(iv: ByteArray, wrapped: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            MasterKeyManager.getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        return cipher.doFinal(wrapped)
    }
}
