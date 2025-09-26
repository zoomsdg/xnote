package com.example.xnote.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 记事数据模型
 */
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey
    val id: String,

    val title: String,

    val categoryId: String = "daily",

    val isPinned: Boolean = false,

    val createdAt: Long = System.currentTimeMillis(),

    val updatedAt: Long = System.currentTimeMillis(),

    val version: Int = 1
)

/**
 * 完整记事（包含所有块）
 */
data class FullNote(
    val note: Note,
    val blocks: List<NoteBlock>
)

/**
 * 记事摘要（用于列表显示）
 */
data class NoteSummary(
    val id: String,
    val title: String,
    val preview: String,
    val lastModified: Long,
    val blockCount: Int,
    val categoryId: String = "daily",
    val isPinned: Boolean = false
)