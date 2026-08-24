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
        val t0 = System.currentTimeMillis()

        // SQLCipher native 库必须在任何 DB 操作之前加载，只能同步做
        SQLiteDatabase.loadLibs(this)
        val tLibs = System.currentTimeMillis()

        // 取一次 Keystore 主密钥句柄。MasterKeyManager 内部会缓存，
        // 后面 DB 解封和媒体加解密都复用这一次的结果。
        runCatching { MasterKeyManager.getOrCreateKey() }
            .onFailure { SecurityLog.e("XNoteApp", "Master key init failed", it) }
        val tKey = System.currentTimeMillis()

        // 一次性把旧的明文 DB / 媒体迁移成加密形式。
        // DB 那步只读 16 字节文件头判断，已加密就立刻返回；媒体那步自己起线程。
        LegacyDataMigrator.migrateIfNeeded(this)
        val tMigrate = System.currentTimeMillis()

        // 只记住 Context，真正的加密存储延后到首次使用（见 AppSecurityConfig）
        runCatching { AppSecurityConfig.initialize(this) }
            .onFailure { SecurityLog.e("XNoteApp", "AppSecurityConfig init failed", it) }

        // 剩下这些都是清理上次进程的残留、以及存量格式升级：
        // 没有任何东西同步依赖它们完成，但都要遍历/删除目录甚至做加解密，
        // 放主线程纯粹白占启动时间。统一挪到一条低优先级后台线程上。
        val thread = Thread({
            // 上次进程遗留的解密临时文件（崩溃/被杀时可能残留）
            runCatching { MediaCryptor.cleanupDecryptedTempDir(this) }
            // 上次"打开附件"时解密到 cache 的明文附件
            runCatching { AttachmentUtils.cleanupOpenCache(this) }
            // 存量 XNC1 容器升级成 XNC2
            runCatching { MediaCryptor.upgradeLegacyContainers(this) }
        }, "xnote-startup-chores")
        thread.priority = Thread.MIN_PRIORITY
        thread.isDaemon = true
        thread.start()

        SecurityLog.i(
            "Perf",
            "startup 加载so=" + (tLibs - t0) + "ms 主密钥=" + (tKey - tLibs) +
                "ms 迁移检查=" + (tMigrate - tKey) +
                "ms 主线程合计=" + (System.currentTimeMillis() - t0) + "ms"
        )
    }
}
