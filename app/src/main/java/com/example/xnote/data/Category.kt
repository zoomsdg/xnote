package com.example.xnote.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 分类数据模型
 */
@Entity(tableName = "categories")
data class Category(
    @PrimaryKey
    val id: String,
    
    val name: String,
    
    val isDefault: Boolean = false,
    
    val createdAt: Long = System.currentTimeMillis()
)