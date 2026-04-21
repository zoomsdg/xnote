package com.example.xnote.security

import android.content.Context
import android.util.Base64
import com.example.xnote.utils.SecurityLog
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * 维护 SQLCipher 用的 32 字节随机密钥（以 64 字符 hex 字符串形式提供，
 * 既能直接喂给 SupportFactory，也能安全嵌入 SQL `KEY '...'` 子句用于迁移）。
 *
 * 落盘形式：
 *   SharedPreferences("xnote_db_key_v1") {
 *     "iv"        : Base64(12B IV)
 *     "wrapped"   : Base64(GCM(passphrase_bytes))
 *   }
 *
 * 仅持有用 [MasterKeyManager] 主密钥包封后的密文；root 拿到 SharedPreferences 文件
 * 也无法解出原文，必须能调用 Keystore 才能解封。
 */
object DatabaseKeyProvider {

    private const val PREFS = "xnote_db_key_v1"
    private const val KEY_IV = "iv"
    private const val KEY_WRAPPED = "wrapped"
    private const val GCM_TAG_BITS = 128
    private const val PASSPHRASE_BYTES = 32 // 256-bit

    @Volatile
    private var cached: String? = null

    /**
     * 返回 64 字符 hex passphrase；首次调用时生成并安全持久化。
     */
    @Synchronized
    fun getOrCreatePassphrase(context: Context): String {
        cached?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ivB64 = prefs.getString(KEY_IV, null)
        val wrappedB64 = prefs.getString(KEY_WRAPPED, null)
        val pass = if (ivB64 != null && wrappedB64 != null) {
            unwrap(Base64.decode(ivB64, Base64.NO_WRAP), Base64.decode(wrappedB64, Base64.NO_WRAP))
        } else {
            val raw = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
            val hex = toHex(raw)
            val (iv, wrapped) = wrap(hex.toByteArray(Charsets.US_ASCII))
            prefs.edit()
                .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(KEY_WRAPPED, Base64.encodeToString(wrapped, Base64.NO_WRAP))
                .apply()
            SecurityLog.i("DatabaseKeyProvider", "Generated and wrapped new DB passphrase")
            hex
        }
        cached = pass
        return pass
    }

    private fun wrap(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, MasterKeyManager.getOrCreateKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        return iv to ct
    }

    private fun unwrap(iv: ByteArray, wrapped: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            MasterKeyManager.getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        return String(cipher.doFinal(wrapped), Charsets.US_ASCII)
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(HEX[(b.toInt() ushr 4) and 0xF])
            sb.append(HEX[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    private val HEX = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    )
}
