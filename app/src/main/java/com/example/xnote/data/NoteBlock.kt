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
    
    val createdAt: Long = System.currentTimeMillis(),
    
    val updatedAt: Long = System.currentTimeMillis()
)

enum class BlockType {
    @SerializedName("text")
    TEXT,
    
    @SerializedName("image")
    IMAGE,
    
    @SerializedName("audio")
    AUDIO
}