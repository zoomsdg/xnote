package com.example.xnote.utils

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.xnote.utils.SecurityLog
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore
import java.util.*

/**
 * 安全加密存储工具
 * 用于存储敏感配置和数据
 */
class SecureConfigStorage private constructor(context: Context) {
    
    companion object {
        private const val KEY_ALIAS = "XNote_Master_Key"
        private const val ENCRYPTED_PREFS_FILE = "secure_config_prefs"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 16
        
        @Volatile
        private var INSTANCE: SecureConfigStorage? = null
        
        fun getInstance(context: Context): SecureConfigStorage {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecureConfigStorage(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val encryptedPrefs: SharedPreferences
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore")
    
    init {
        keyStore.load(null)
        
        try {
            // 创建或获取主密钥
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .setRequestStrongBoxBacked(true) // 如果设备支持，使用硬件安全模块
                .build()
            
            // 创建加密的SharedPreferences
            encryptedPrefs = EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            
            SecurityLog.d("SecureConfigStorage", "Encrypted storage initialized successfully")
        } catch (e: Exception) {
            SecurityLog.e("SecureConfigStorage", "Failed to initialize encrypted storage", e)
            throw SecurityException("无法初始化安全存储")
        }
    }
    
    /**
     * 存储加密配置
     */
    fun putSecureString(key: String, value: String) {
        try {
            encryptedPrefs.edit().putString(sanitizeKey(key), value).apply()
            SecurityLog.d("SecureConfigStorage", "Stored secure string configuration")
        } catch (e: Exception) {
            SecurityLog.e("SecureConfigStorage", "Failed to store secure string", e)
            throw SecurityException("存储配置失败")
        }
    }
    
    /**
     * 获取加密配置
     */
    fun getSecureString(key: String, defaultValue: String? = null): String? {
        return try {
            val value = encryptedPrefs.getString(sanitizeKey(key), defaultValue)
            SecurityLog.d("SecureConfigStorage", "Retrieved secure string configuration")
            value
        } catch (e: Exception) {
            SecurityLog.e("SecureConfigStorage", "Failed to retrieve secure string", e)
            defaultValue
        }
    }
    
    /**
     * 存储加密布尔值
     */
    fun putSecureBoolean(key: String, value: Boolean) {
        try {
            encryptedPrefs.edit().putBoolean(sanitizeKey(key), value).apply()
            SecurityLog.d("SecureConfigStorage", "Stored secure boolean configuration")
        } catch (e: Exception) {
            SecurityLog.e("SecureConfigStorage", "Failed to store secure boolean", e)
            throw SecurityException("存储配置失败")
        }
    }
    
    /**
     * 获取加密布尔值
     */
    fun getSecureBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return try {
            val value = encryptedPrefs.getBoolean(sanitizeKey(key), defaultValue)
            SecurityLog.d("SecureConfigStorage", "Retrieved secure boolean configuration")
            value
        } catch (e: Exception) {
            SecurityLog.e("SecureConfigStorage", "Failed to retrieve secure boolean", e)
            defaultValue
        }
    }
    
    /**
     * 存储加密整数
     */
    fun putSecureInt(key: String, value: Int) {
        try {
            encryptedPrefs.edit().putInt(sanitizeKey(key), value).apply()
            SecurityLog.d("SecureConfigStorage", "Stored secure int configuration")
        } catch (e: Exception) {
            SecurityLog.e("SecureConfigStorage", "Failed to store secure int", e)
            throw SecurityException("存储配置失败")
        }
    }
    
    /**
     * 获取加密整数
     */
    fun getSecureInt(key: String, defaultValue: Int = 0): Int {
        return try {
            val value = encryptedPrefs.getInt(sanitizeKey(key), defaultValue)
            SecurityLog.d("SecureConfigStorage", "Retrieved secure int configuration")
            value
        } catch (e: Exception) {
            SecurityLog.e("SecureConfigStorage", "Failed to retrieve secure int", e)
            defaultValue
        }
    }
    
    /**
     * 删除配置
     */
    fun removeSecure(key: String) {
        try {
            encryptedPrefs.edit().remove(sanitizeKey(key)).apply()
            SecurityLog.d("SecureConfigStorage", "Removed secure configuration")
        } catch (e: Exception) {
            SecurityLog.e("SecureConfigStorage", "Failed to remove secure config", e)
        }
    }
    
    /**
     * 检查配置是否存在
     */
    fun containsSecure(key: String): Boolean {
        return try {
            encryptedPrefs.contains(sanitizeKey(key))
        } catch (e: Exception) {
            SecurityLog.e("SecureConfigStorage", "Failed to check secure config", e)
            false
        }
    }
    
    /**
     * 清空所有安全配置
     */
    fun clearAll() {
        try {
            encryptedPrefs.edit().clear().apply()
            SecurityLog.i("SecureConfigStorage", "Cleared all secure configurations")
        } catch (e: Exception) {
            SecurityLog.e("SecureConfigStorage", "Failed to clear secure configs", e)
        }
    }
    
    /**
     * 加密任意二进制数据
     */
    fun encryptData(plainData: ByteArray): EncryptedData {
        return try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(plainData)
            
            SecurityLog.d("SecureConfigStorage", "Successfully encrypted data")
            EncryptedData(encryptedBytes, iv)
        } catch (e: Exception) {
            SecurityLog.e("SecureConfigStorage", "Failed to encrypt data", e)
            throw SecurityException("数据加密失败")
        }
    }
    
    /**
     * 解密二进制数据
     */
    fun decryptData(encryptedData: EncryptedData): ByteArray {
        return try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH * 8, encryptedData.iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            
            val decryptedBytes = cipher.doFinal(encryptedData.encryptedBytes)
            SecurityLog.d("SecureConfigStorage", "Successfully decrypted data")
            decryptedBytes
        } catch (e: Exception) {
            SecurityLog.e("SecureConfigStorage", "Failed to decrypt data", e)
            throw SecurityException("数据解密失败")
        }
    }
    
    /**
     * 获取或创建密钥
     */
    private fun getOrCreateSecretKey(): SecretKey {
        return if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.getKey(KEY_ALIAS, null) as SecretKey
        } else {
            createSecretKey()
        }
    }
    
    /**
     * 创建密钥
     */
    private fun createSecretKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false) // 根据需要可以启用生物识别验证
            .build()
        
        keyGenerator.init(keyGenParameterSpec)
        return keyGenerator.generateKey()
    }
    
    /**
     * 清理密钥名称
     */
    private fun sanitizeKey(key: String): String {
        return key.replace(Regex("[^a-zA-Z0-9_]"), "_")
    }
    
    /**
     * 加密数据包装类
     */
    data class EncryptedData(
        val encryptedBytes: ByteArray,
        val iv: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            
            other as EncryptedData
            
            if (!encryptedBytes.contentEquals(other.encryptedBytes)) return false
            if (!iv.contentEquals(other.iv)) return false
            
            return true
        }
        
        override fun hashCode(): Int {
            var result = encryptedBytes.contentHashCode()
            result = 31 * result + iv.contentHashCode()
            return result
        }
    }
}