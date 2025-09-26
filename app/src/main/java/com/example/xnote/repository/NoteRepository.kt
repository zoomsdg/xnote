package com.example.xnote.repository

import android.content.Context
import com.example.xnote.data.*
import com.example.xnote.utils.ExportImportUtils
import com.example.xnote.utils.FileUtils
import com.example.xnote.utils.SecurityLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * 记事仓库
 */
class NoteRepository(val context: Context) {
    
    private val database = NoteDatabase.getDatabase(context)
    private val noteDao = database.noteDao()
    private val categoryDao = database.categoryDao()
    
    init {
        // 确保默认分类存在
        CoroutineScope(Dispatchers.IO).launch {
            ensureDefaultCategoriesExist()
        }
    }
    
    /**
     * 确保默认分类存在
     */
    private suspend fun ensureDefaultCategoriesExist() {
        try {
            SecurityLog.d("NoteRepository", "Initializing default categories")
            // 检查并创建默认分类
            val defaultCategories = listOf(
                Category(id = "daily", name = "日常", isDefault = true, createdAt = System.currentTimeMillis()),
                Category(id = "work", name = "工作", isDefault = true, createdAt = System.currentTimeMillis()),
                Category(id = "thoughts", name = "感悟", isDefault = true, createdAt = System.currentTimeMillis())
            )
            
            for (category in defaultCategories) {
                val exists = categoryDao.categoryExists(category.id)
                if (exists == 0) {
                    categoryDao.insertCategory(category)
                    SecurityLog.d("NoteRepository", "Created default category")
                }
            }
        } catch (e: Exception) {
            SecurityLog.e("NoteRepository", "Failed to initialize default categories", e)
        }
    }
    
    fun getAllNotes(): Flow<List<Note>> = noteDao.getAllNotes()
    
    fun getNoteSummaries(): Flow<List<NoteSummary>> = noteDao.getNoteSummaries()
    
    fun searchNoteSummaries(searchQuery: String): Flow<List<NoteSummary>> = 
        noteDao.searchNoteSummaries(searchQuery)
    
    /**
     * 智能搜索功能：支持文本搜索和日期格式搜索
     * 日期格式示例：202503 -> 2025年3月, 20250315 -> 2025年3月15日
     */
    fun smartSearchNoteSummaries(searchQuery: String): Flow<List<NoteSummary>> {
        val dateRange = parseDateQuery(searchQuery)
        return if (dateRange != null) {
            // 如果是纯数字日期查询，优先使用时间范围搜索
            // 同时也返回包含该数字的文本搜索结果，使用OR逻辑
            noteDao.searchNoteSummariesWithDateOrText(searchQuery, dateRange.first, dateRange.second)
        } else {
            // 普通文本搜索
            noteDao.searchNoteSummaries(searchQuery)
        }
    }
    
    /**
     * 解析日期查询字符串
     * 支持格式：
     * - 202503 -> 2025年3月
     * - 20250315 -> 2025年3月15日
     * - 2025 -> 2025年
     */
    private fun parseDateQuery(query: String): Pair<Long, Long>? {
        try {
            // 检查是否为纯数字
            if (!query.matches(Regex("\\d+"))) {
                return null
            }
            
            val calendar = java.util.Calendar.getInstance()
            
            when (query.length) {
                4 -> {
                    // 年份格式：2025
                    val year = query.toInt()
                    if (year < 1900 || year > 2100) return null
                    
                    calendar.set(year, 0, 1, 0, 0, 0)
                    calendar.set(java.util.Calendar.MILLISECOND, 0)
                    val startTime = calendar.timeInMillis
                    
                    calendar.set(year, 11, 31, 23, 59, 59)
                    calendar.set(java.util.Calendar.MILLISECOND, 999)
                    val endTime = calendar.timeInMillis
                    
                    return Pair(startTime, endTime)
                }
                6 -> {
                    // 年月格式：202503
                    val year = query.substring(0, 4).toInt()
                    val month = query.substring(4, 6).toInt()
                    if (year < 1900 || year > 2100 || month < 1 || month > 12) return null
                    
                    calendar.set(year, month - 1, 1, 0, 0, 0)
                    calendar.set(java.util.Calendar.MILLISECOND, 0)
                    val startTime = calendar.timeInMillis
                    
                    calendar.set(year, month - 1, calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH), 23, 59, 59)
                    calendar.set(java.util.Calendar.MILLISECOND, 999)
                    val endTime = calendar.timeInMillis
                    
                    return Pair(startTime, endTime)
                }
                8 -> {
                    // 年月日格式：20250315
                    val year = query.substring(0, 4).toInt()
                    val month = query.substring(4, 6).toInt()
                    val day = query.substring(6, 8).toInt()
                    if (year < 1900 || year > 2100 || month < 1 || month > 12 || day < 1 || day > 31) return null
                    
                    calendar.set(year, month - 1, day, 0, 0, 0)
                    calendar.set(java.util.Calendar.MILLISECOND, 0)
                    val startTime = calendar.timeInMillis
                    
                    calendar.set(year, month - 1, day, 23, 59, 59)
                    calendar.set(java.util.Calendar.MILLISECOND, 999)
                    val endTime = calendar.timeInMillis
                    
                    return Pair(startTime, endTime)
                }
                else -> return null
            }
        } catch (e: Exception) {
            return null
        }
    }
    
    fun getNoteSummariesByCategory(categoryId: String): Flow<List<NoteSummary>> = 
        noteDao.getNoteSummariesByCategory(categoryId)
    
    suspend fun getAllCategories(): List<Category> = categoryDao.getAllCategoriesOnce()
    
    suspend fun getNoteById(noteId: String): Note? = noteDao.getNoteById(noteId)
    
    suspend fun getFullNote(noteId: String): FullNote? = noteDao.getFullNote(noteId)
    
    suspend fun saveNote(note: Note) = noteDao.insertNote(note)
    
    suspend fun saveFullNote(fullNote: FullNote) = noteDao.saveFullNote(fullNote)
    
    suspend fun updateNote(note: Note) = noteDao.updateNote(note)
    
    suspend fun deleteNote(noteId: String) = noteDao.deleteFullNote(noteId)
    
    suspend fun createNewNote(title: String = "无标题"): String {
        val noteId = UUID.randomUUID().toString()
        val note = Note(
            id = noteId,
            title = title,
            categoryId = "daily", // 默认分类为日常
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        
        // 创建初始文本块
        val initialBlock = NoteBlock(
            id = UUID.randomUUID().toString(),
            noteId = noteId,
            type = BlockType.TEXT,
            order = 0,
            text = ""
        )
        
        val fullNote = FullNote(note, listOf(initialBlock))
        saveFullNote(fullNote)
        return noteId
    }
    
    suspend fun addBlock(noteId: String, blockType: BlockType, order: Int, data: Map<String, Any?> = emptyMap()): String {
        val blockId = UUID.randomUUID().toString()
        val block = NoteBlock(
            id = blockId,
            noteId = noteId,
            type = blockType,
            order = order,
            text = data["text"] as? String,
            url = data["url"] as? String,
            alt = data["alt"] as? String,
            duration = data["duration"] as? Long,
            width = data["width"] as? Int,
            height = data["height"] as? Int
        )
        noteDao.insertBlock(block)
        
        // 更新记事的修改时间
        val note = getNoteById(noteId)
        if (note != null) {
            updateNote(note.copy(updatedAt = System.currentTimeMillis()))
        }
        
        return blockId
    }
    
    suspend fun updateBlock(blockId: String, data: Map<String, Any?>) {
        val blocks = noteDao.getBlocksByNoteId("") // 需要优化：直接获取block
        // 这里应该有根据blockId获取block的方法
        // 暂时简化处理
    }
    
    suspend fun deleteBlock(blockId: String) {
        noteDao.deleteBlock(blockId)
    }
    
    suspend fun deleteAllNotes() {
        noteDao.deleteAllNotes()
        noteDao.deleteAllBlocks()
    }
    
    suspend fun importNote(importNote: ExportImportUtils.ImportNote) {
        // 创建新的记事
        val note = Note(
            id = UUID.randomUUID().toString(), // 生成新的ID避免冲突
            title = importNote.title,
            categoryId = "daily", // 导入的记事默认分类为日常
            createdAt = importNote.createdAt,
            updatedAt = importNote.updatedAt, // 使用外部记事自带的修改时间
            version = 1
        )
        
        noteDao.insertNote(note)
        
        // 导入记事块
        val blocks = mutableListOf<NoteBlock>()
        for (importBlock in importNote.blocks) {
            val block = when (importBlock.type) {
                BlockType.TEXT -> NoteBlock(
                    id = UUID.randomUUID().toString(),
                    noteId = note.id,
                    type = importBlock.type,
                    order = importBlock.order,
                    text = importBlock.text,
                    createdAt = importNote.createdAt,
                    updatedAt = importNote.updatedAt
                )
                BlockType.IMAGE -> {
                    var filePath: String? = null
                    
                    // 复制媒体文件到应用私有目录
                    if (importBlock.mediaFile?.exists() == true) {
                        val fileName = "imported_image_${System.currentTimeMillis()}_${importBlock.mediaFile.name}"
                        val targetFile = File(context.filesDir, "images/$fileName")
                        targetFile.parentFile?.mkdirs()
                        importBlock.mediaFile.copyTo(targetFile, overwrite = true)
                        filePath = targetFile.absolutePath
                    }
                    
                    NoteBlock(
                        id = UUID.randomUUID().toString(),
                        noteId = note.id,
                        type = importBlock.type,
                        order = importBlock.order,
                        url = filePath,
                        alt = importBlock.alt,
                        width = importBlock.width,
                        height = importBlock.height,
                        createdAt = importNote.createdAt,
                        updatedAt = importNote.updatedAt
                    )
                }
                BlockType.AUDIO -> {
                    var filePath: String? = null
                    
                    // 复制媒体文件到应用私有目录
                    if (importBlock.mediaFile?.exists() == true) {
                        val fileName = "imported_audio_${System.currentTimeMillis()}_${importBlock.mediaFile.name}"
                        val targetFile = File(context.filesDir, "audio/$fileName")
                        targetFile.parentFile?.mkdirs()
                        importBlock.mediaFile.copyTo(targetFile, overwrite = true)
                        filePath = targetFile.absolutePath
                    }
                    
                    NoteBlock(
                        id = UUID.randomUUID().toString(),
                        noteId = note.id,
                        type = importBlock.type,
                        order = importBlock.order,
                        url = filePath,
                        duration = importBlock.duration,
                        createdAt = importNote.createdAt,
                        updatedAt = importNote.updatedAt
                    )
                }
            }
            blocks.add(block)
        }
        
        // 批量插入块
        blocks.forEach { noteDao.insertBlock(it) }
    }
    
    // 分类相关方法
    suspend fun getAllCategoriesOnce(): List<Category> = categoryDao.getAllCategoriesOnce()
    
    suspend fun getCategoryById(categoryId: String): Category? = categoryDao.getCategoryById(categoryId)
    
    suspend fun createCategory(categoryName: String): String {
        val categoryId = UUID.randomUUID().toString()
        val category = Category(
            id = categoryId,
            name = categoryName,
            isDefault = false,
            createdAt = System.currentTimeMillis()
        )
        categoryDao.insertCategory(category)
        return categoryId
    }
    
    suspend fun updateNoteCategory(noteId: String, categoryId: String) {
        val note = getNoteById(noteId)
        if (note != null) {
            updateNote(note.copy(categoryId = categoryId, updatedAt = System.currentTimeMillis()))
        }
    }
    
    suspend fun updateNotePinStatus(noteId: String, isPinned: Boolean) {
        noteDao.updateNotePinStatus(noteId, isPinned)
    }

    suspend fun deleteCategory(categoryId: String) {
        // 防止删除默认分类
        if (categoryId == "daily" || categoryId == "work" || categoryId == "thoughts") {
            throw IllegalArgumentException("Cannot delete default categories")
        }
        
        val category = categoryDao.getCategoryById(categoryId)
        if (category != null) {
            // 先将此分类的所有记事转移到"日常"分类
            val notesWithCategory = noteDao.getAllNotesInCategory(categoryId)
            notesWithCategory.forEach { note ->
                updateNote(note.copy(categoryId = "daily", updatedAt = System.currentTimeMillis()))
            }
            
            // 删除分类
            categoryDao.deleteCategory(category)
        }
    }
}