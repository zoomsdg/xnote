package com.example.xnote.data

import androidx.room.TypeConverter

/**
 * Room 数据库类型转换器
 */
class Converters {
    
    @TypeConverter
    fun fromBlockType(blockType: BlockType): String {
        return blockType.name
    }
    
    @TypeConverter
    fun toBlockType(blockType: String): BlockType {
        return BlockType.valueOf(blockType)
    }
}