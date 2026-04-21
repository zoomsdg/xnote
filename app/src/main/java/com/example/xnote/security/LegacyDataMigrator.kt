package com.example.xnote.security

import android.content.Context
import com.example.xnote.utils.SecurityLog
import net.sqlcipher.database.SQLiteDatabase
import java.io.File
import java.io.IOException

/**
 * 一次性把旧的明文 Room/SQLite 数据库及明文媒体文件迁移为加密形式。
 * 幂等：再次调用什么都不做。
 */
object LegacyDataMigrator {

    private const val DB_NAME = "note_database"

    fun migrateIfNeeded(context: Context) {
        runCatching { migrateDatabase(context) }
            .onFailure { SecurityLog.e("Migrator", "DB migration failed", it) }
        // 媒体迁移可异步，且 MediaCryptor.readAll 会自动兼容尚未迁移的明文
        Thread {
            runCatching { migrateMediaDir(File(context.filesDir, "images")) }
            runCatching { migrateMediaDir(File(context.filesDir, "audios")) }
            runCatching { migrateMediaDir(File(context.filesDir, "audio")) }
        }.apply { name = "xnote-media-migrator"; isDaemon = true }.start()
    }

    private fun migrateDatabase(context: Context) {
        val dbFile = context.getDatabasePath(DB_NAME) ?: return
        if (!dbFile.exists()) return
        if (!isPlaintextSqlite(dbFile)) return // 已加密或非 SQLite，跳过

        SQLiteDatabase.loadLibs(context)
        val pass = DatabaseKeyProvider.getOrCreatePassphrase(context)
        val passEscaped = pass.replace("'", "''") // 仅含 [0-9a-f] 实际无需转义，保险起见
        val migratedFile = File(dbFile.parentFile, "${dbFile.name}.migrated")
        if (migratedFile.exists()) migratedFile.delete()

        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            "", // 空密码 = 按明文打开
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        try {
            // 读取原 user_version 以便迁移后保留 Room schema 版本
            var userVersion = 0
            db.rawQuery("PRAGMA user_version", null).use {
                if (it.moveToFirst()) userVersion = it.getInt(0)
            }

            db.rawExecSQL(
                "ATTACH DATABASE '${migratedFile.absolutePath}' AS encrypted KEY '$passEscaped'"
            )
            db.rawExecSQL("SELECT sqlcipher_export('encrypted')")
            db.rawExecSQL("PRAGMA encrypted.user_version = $userVersion")
            db.rawExecSQL("DETACH DATABASE encrypted")
        } finally {
            db.close()
        }

        // 替换原文件，并清理 -journal / -wal / -shm
        if (!dbFile.delete()) throw IOException("delete plaintext db failed")
        if (!migratedFile.renameTo(dbFile)) throw IOException("rename encrypted db failed")
        listOf("$DB_NAME-journal", "$DB_NAME-wal", "$DB_NAME-shm").forEach {
            File(dbFile.parentFile, it).takeIf { f -> f.exists() }?.delete()
        }
        SecurityLog.i("Migrator", "Database migrated to SQLCipher")
    }

    private fun isPlaintextSqlite(file: File): Boolean {
        if (file.length() < 16) return false
        val header = ByteArray(16)
        file.inputStream().use { it.read(header) }
        // SQLite 明文文件头："SQLite format 3\u0000"
        return String(header, Charsets.US_ASCII).startsWith("SQLite format 3")
    }

    private fun migrateMediaDir(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        var migrated = 0
        dir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            if (MediaCryptor.isEncryptedFile(file)) return@forEach
            try {
                MediaCryptor.encryptInPlace(file)
                migrated++
            } catch (e: Exception) {
                SecurityLog.e("Migrator", "Encrypt-in-place failed for ${file.name}", e)
            }
        }
        if (migrated > 0) SecurityLog.i("Migrator", "Encrypted $migrated media files in ${dir.name}")
    }
}
