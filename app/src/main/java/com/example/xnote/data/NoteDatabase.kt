package com.example.xnote.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import com.example.xnote.security.DatabaseKeyProvider
import net.sqlcipher.database.SupportFactory

/**
 * Room 数据库
 */
@Database(
    entities = [Note::class, NoteBlock::class, Category::class, Notebook::class],
    version = 5,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class NoteDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao
    abstract fun categoryDao(): CategoryDao
    abstract fun notebookDao(): NotebookDao
    
    companion object {
        @Volatile
        private var INSTANCE: NoteDatabase? = null
        
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 创建分类表
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS categories (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        isDefault INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL
                    )
                """)

                // 添加默认分类
                database.execSQL("INSERT INTO categories (id, name, isDefault, createdAt) VALUES ('daily', '日常', 1, ${System.currentTimeMillis()})")
                database.execSQL("INSERT INTO categories (id, name, isDefault, createdAt) VALUES ('work', '工作', 1, ${System.currentTimeMillis()})")
                database.execSQL("INSERT INTO categories (id, name, isDefault, createdAt) VALUES ('thoughts', '感悟', 1, ${System.currentTimeMillis()})")

                // 为notes表添加categoryId字段
                database.execSQL("ALTER TABLE notes ADD COLUMN categoryId TEXT NOT NULL DEFAULT 'daily'")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 为notes表添加isPinned字段
                database.execSQL("ALTER TABLE notes ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 创建标签页（tab）表
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS notebooks (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        `order` INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL
                    )
                """)

                // 默认标签页（不可删除、可重命名），承载全部历史纪事
                database.execSQL(
                    "INSERT INTO notebooks (id, name, `order`, createdAt) " +
                    "VALUES ('local', '本地纪事', 0, ${System.currentTimeMillis()})"
                )

                // 为notes表添加标签页归属与导入来源 id 字段
                // 历史纪事全部归入默认标签页 'local'
                database.execSQL("ALTER TABLE notes ADD COLUMN notebookId TEXT NOT NULL DEFAULT 'local'")
                database.execSQL("ALTER TABLE notes ADD COLUMN sourceId TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // 附件块的字节数（明文原始大小），仅供显示；纯本地字段，不进导出 ZIP
                database.execSQL("ALTER TABLE note_blocks ADD COLUMN size INTEGER")
            }
        }

        fun getDatabase(context: Context): NoteDatabase {
            return INSTANCE ?: synchronized(this) {
                val appCtx = context.applicationContext
                val passphrase = DatabaseKeyProvider.getOrCreatePassphrase(appCtx)
                    .toByteArray(Charsets.US_ASCII)
                val instance = Room.databaseBuilder(
                    appCtx,
                    NoteDatabase::class.java,
                    "note_database"
                )
                    // clearPassphrase=false: 让 SupportFactory 保留 passphrase 字节，
                    // 以防 Room 在生命周期内需要重建 SupportHelper 时取不到密钥
                    .openHelperFactory(SupportFactory(passphrase, null, false))
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}