package com.example.xnote.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 标签页（tab / 笔记本）数据模型。
 *
 * 位于纪事之上的本地分区层：每条纪事属且仅属于一个标签页。
 * 纯本地概念，不参与跨平台导出（导出 ZIP 不含标签页归属字段）。
 */
@Entity(tableName = "notebooks")
data class Notebook(
    @PrimaryKey
    val id: String,

    val name: String,

    /** 标签条显示顺序 */
    val order: Int = 0,

    val createdAt: Long = System.currentTimeMillis()
)
