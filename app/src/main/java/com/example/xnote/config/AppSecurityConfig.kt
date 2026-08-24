package com.example.xnote.config

import android.content.Context
import com.example.xnote.utils.SecureConfigStorage
import com.example.xnote.utils.SecurityLog

/**
 * 应用安全配置管理器
 * 演示如何安全存储敏感配置
 */
object AppSecurityConfig {
    
    private const val KEY_LAST_EXPORT_PASSWORD_HASH = "last_export_pwd_hash"
    private const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"
    private const val KEY_BIOMETRIC_AUTH_ENABLED = "biometric_auth_enabled"
    private const val KEY_SESSION_TIMEOUT = "session_timeout_minutes"
    
    @Volatile
    private var appContext: Context? = null

    /**
     * 首次使用时才真正建加密存储。
     *
     * [SecureConfigStorage] 的构造里会 EncryptedSharedPreferences.create——
     * 创建/读取 StrongBox 主密钥再初始化 Tink 密钥集，在带 StrongBox 的机器上
     * 要几百毫秒到一秒多。而这里存的只是导出密码哈希和几个开关，
     * 用户不打开导出/设置根本用不到，没必要卡在启动路径上。
     * [SecureConfigStorage.getInstance] 自带单例缓存，之后取用是廉价的。
     */
    private val secureStorage: SecureConfigStorage
        get() = SecureConfigStorage.getInstance(
            requireNotNull(appContext) { "AppSecurityConfig.initialize() 尚未调用" }
        )
    
    /**
     * 初始化安全配置
     */
    fun initialize(context: Context) {
        // 只记住 Context，真正的加密存储延后到首次使用
        appContext = context.applicationContext
        SecurityLog.d("AppSecurityConfig", "Security configuration initialized (lazy)")
    }
    
    /**
     * 存储导出密码哈希（用于密码提示）
     */
    fun storeExportPasswordHash(passwordHash: String) {
        secureStorage.putSecureString(KEY_LAST_EXPORT_PASSWORD_HASH, passwordHash)
        SecurityLog.d("AppSecurityConfig", "Export password hash stored")
    }
    
    /**
     * 获取导出密码哈希
     */
    fun getExportPasswordHash(): String? {
        return secureStorage.getSecureString(KEY_LAST_EXPORT_PASSWORD_HASH)
    }
    
    /**
     * 设置自动备份开关
     */
    fun setAutoBackupEnabled(enabled: Boolean) {
        secureStorage.putSecureBoolean(KEY_AUTO_BACKUP_ENABLED, enabled)
        SecurityLog.d("AppSecurityConfig", "Auto backup setting updated")
    }
    
    /**
     * 获取自动备份开关
     */
    fun isAutoBackupEnabled(): Boolean {
        return secureStorage.getSecureBoolean(KEY_AUTO_BACKUP_ENABLED, false)
    }
    
    /**
     * 设置生物识别认证开关
     */
    fun setBiometricAuthEnabled(enabled: Boolean) {
        secureStorage.putSecureBoolean(KEY_BIOMETRIC_AUTH_ENABLED, enabled)
        SecurityLog.d("AppSecurityConfig", "Biometric auth setting updated")
    }
    
    /**
     * 获取生物识别认证开关
     */
    fun isBiometricAuthEnabled(): Boolean {
        return secureStorage.getSecureBoolean(KEY_BIOMETRIC_AUTH_ENABLED, false)
    }
    
    /**
     * 设置会话超时时间（分钟）
     */
    fun setSessionTimeout(minutes: Int) {
        secureStorage.putSecureInt(KEY_SESSION_TIMEOUT, minutes)
        SecurityLog.d("AppSecurityConfig", "Session timeout updated")
    }
    
    /**
     * 获取会话超时时间
     */
    fun getSessionTimeout(): Int {
        return secureStorage.getSecureInt(KEY_SESSION_TIMEOUT, 30) // 默认30分钟
    }
    
    /**
     * 清除所有安全配置
     */
    fun clearAllSecureConfig() {
        secureStorage.clearAll()
        SecurityLog.i("AppSecurityConfig", "All secure configuration cleared")
    }
    
    /**
     * 验证配置完整性
     */
    fun validateConfiguration(): Boolean {
        return try {
            // 执行基本的配置验证
            val timeout = getSessionTimeout()
            val isValid = timeout in 1..1440 // 1分钟到24小时
            
            if (!isValid) {
                SecurityLog.w("AppSecurityConfig", "Invalid session timeout configuration")
            }
            
            isValid
        } catch (e: Exception) {
            SecurityLog.e("AppSecurityConfig", "Configuration validation failed", e)
            false
        }
    }
}