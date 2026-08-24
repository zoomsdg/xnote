package com.example.xnote

import android.app.Application
import com.example.xnote.config.AppSecurityConfig
import com.example.xnote.security.LegacyDataMigrator
import com.example.xnote.security.MasterKeyManager
import com.example.xnote.security.MediaCryptor
import com.example.xnote.utils.AttachmentUtils
import com.example.xnote.utils.SecurityLog
import net.sqlcipher.database.SQLiteDatabase

class XNoteApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // SQLCipher native 库必须在任何 DB 操作之前加载
        SQLiteDatabase.loadLibs(this)

        // 触发 Keystore 主密钥创建（首次启动时一次性）
        runCatching { MasterKeyManager.getOrCreateKey() }
            .onFailure { SecurityLog.e("XNoteApp", "Master key init failed", it) }

        // 一次性把旧的明文 DB / 媒体迁移成加密形式
        LegacyDataMigrator.migrateIfNeeded(this)

        // 清掉上次进程遗留的解密临时文件（崩溃/被杀时可能残留）
        MediaCryptor.cleanupDecryptedTempDir(this)

        // 把存量 XNC1 容器升级成 XNC2（后台低优先级，一次性）。
        // 旧格式整文件走 Keystore/StrongBox，几十 KB/s，不升级则打开旧纪事必卡。
        MediaCryptor.upgradeLegacyContainersAsync(this)

        // 清掉上次"打开附件"时解密到 cache 的明文附件
        AttachmentUtils.cleanupOpenCache(this)

        // 配置存储（已有）
        runCatching { AppSecurityConfig.initialize(this) }
            .onFailure { SecurityLog.e("XNoteApp", "AppSecurityConfig init failed", it) }
    }
}
