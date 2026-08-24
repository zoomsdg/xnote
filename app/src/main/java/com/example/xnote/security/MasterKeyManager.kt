package com.example.xnote.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.xnote.utils.SecurityLog
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Android Keystore 中存放的应用主密钥（AES-256-GCM）。
 * 该密钥不可导出；即使设备 root，攻击者也无法直接拿到原始密钥字节，
 * 只能在解锁/可调用 Keymaster 的状态下使用它进行加解密。
 */
object MasterKeyManager {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "XNote_Data_MasterKey_v1"

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    }

    /**
     * 已取到的密钥句柄。缓存的只是一个句柄，真正的密钥材料始终留在
     * TEE/StrongBox 里、不可导出，所以缓存它不改变安全性。
     *
     * 不缓存的话每次调用都要走一趟 keyStore.getKey()，而这个方法会被
     * DB 密钥解封、以及每一次媒体加解密反复调用——启动和加图都在白付往返开销。
     */
    @Volatile
    private var cachedKey: SecretKey? = null

    @Synchronized
    fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        val key = (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: createKey()
        cachedKey = key
        return key
    }

    private fun createKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)

        fun buildSpec(strongBox: Boolean): KeyGenParameterSpec {
            val b = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                b.setIsStrongBoxBacked(true)
            }
            return b.build()
        }

        // 优先尝试 StrongBox（硬件密钥隔离），失败则落回 TEE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                gen.init(buildSpec(strongBox = true))
                val k = gen.generateKey()
                SecurityLog.i("MasterKeyManager", "Master key generated in StrongBox")
                return k
            } catch (_: Exception) {
                // 落回 TEE
            }
        }
        gen.init(buildSpec(strongBox = false))
        val k = gen.generateKey()
        SecurityLog.i("MasterKeyManager", "Master key generated in TEE/Keystore")
        return k
    }
}
