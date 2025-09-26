package com.example.xnote.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context

/**
 * Room 数据库
 */
@Database(
    entities = [Note::class, NoteBlock::class, Category::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class NoteDatabase : RoomDatabase() {
    
    abstract fun noteDao(): NoteDao
    abstract fun categoryDao(): CategoryDao
    
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
        
        fun getDatabase(context: Context): NoteDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NoteDatabase::class.java,
                    "note_database"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
                INSTANCE = instance
                instance
            }
        }
    }
}