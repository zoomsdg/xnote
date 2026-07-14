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
    
    /**
     * 未知取值（例如被更高版本写入、又降级回来的库）一律回落文本块，绝不抛异常。
     */
    @TypeConverter
    fun toBlockType(blockType: String): BlockType {
        return runCatching { BlockType.valueOf(blockType) }.getOrDefault(BlockType.TEXT)
    }
}