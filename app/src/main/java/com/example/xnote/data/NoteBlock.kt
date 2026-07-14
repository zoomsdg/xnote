package com.example.xnote.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

/**
 * 记事内容块数据模型
 */
@Entity(tableName = "note_blocks")
data class NoteBlock(
    @PrimaryKey
    val id: String,
    
    val noteId: String,
    
    val type: BlockType,
    
    val order: Int,
    
    val text: String? = null,
    
    val url: String? = null,
    
    val alt: String? = null,
    
    val duration: Long? = null,

    val width: Int? = null,

    val height: Int? = null,

    /**
     * 附件字节数（明文原始大小），仅用于列表显示。
     * 纯本地字段：不进导出 DTO，也不进 ZIP 的 notes_data.json。
     */
    val size: Long? = null,

    val createdAt: Long = System.currentTimeMillis(),

    val updatedAt: Long = System.currentTimeMillis()
)

enum class BlockType {
    @SerializedName("text")
    TEXT,

    @SerializedName("image")
    IMAGE,

    @SerializedName("audio")
    AUDIO,

    /**
     * 任意格式附件。本项目不解析其内容，只挂载/显示/交给系统程序打开/另存为。
     * 跨平台契约：ZIP 内 block type == "file"，复用 mediaFileName（media/file_*）与 alt（原始文件名）。
     */
    @SerializedName("file")
    FILE
}