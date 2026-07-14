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

    /**
     * 所属标签页（tab / 笔记本）。纯本地概念，不进导出 ZIP。
     * 历史数据迁移到默认标签页 "local"。
     */
    val notebookId: String = "local",

    /**
     * 导入来源纪事的原始 id，用于导入去重；本地新建纪事为 null。不进导出 ZIP。
     */
    val sourceId: String? = null,

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
    val isPinned: Boolean = false,
    /** 该纪事挂载的附件数量，用于列表页 📎N 角标 */
    val attachmentCount: Int = 0
)