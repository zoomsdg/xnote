package com.example.xnote.repository

import android.content.Context
import com.example.xnote.data.*
import com.example.xnote.security.MediaCryptor
import com.example.xnote.utils.AttachmentUtils
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
    private val notebookDao = database.notebookDao()

    init {
        // 确保默认分类与默认标签页存在
        CoroutineScope(Dispatchers.IO).launch {
            ensureDefaultCategoriesExist()
            ensureDefaultNotebookExists()
        }
    }

    companion object {
        /** 默认标签页固定 id：不可删除、可重命名；历史纪事归入此标签页。 */
        const val DEFAULT_NOTEBOOK_ID = "local"
        const val DEFAULT_NOTEBOOK_NAME = "本地纪事"
    }

    /**
     * 确保默认标签页存在。默认标签页不可删除，必须始终存在，
     * 因此每次启动都补齐（与可被用户删除的预置分类不同）。
     */
    private suspend fun ensureDefaultNotebookExists() {
        try {
            if (notebookDao.notebookExists(DEFAULT_NOTEBOOK_ID) == 0) {
                notebookDao.insertNotebook(
                    Notebook(
                        id = DEFAULT_NOTEBOOK_ID,
                        name = DEFAULT_NOTEBOOK_NAME,
                        order = 0,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
        } catch (e: Exception) {
            SecurityLog.e("NoteRepository", "Failed to seed default notebook", e)
        }
    }
    
    /**
     * 首次启动时种子默认分类。靠 SharedPreferences 标记保证只跑一次。
     * 之前的实现每次实例化都补齐缺失项，导致用户删除的预置分类被反复复活。
     */
    private suspend fun ensureDefaultCategoriesExist() {
        try {
            val prefs = context.getSharedPreferences("xnote_prefs", Context.MODE_PRIVATE)
            val seededKey = "default_categories_seeded_v2"
            if (prefs.getBoolean(seededKey, false)) return

            SecurityLog.d("NoteRepository", "Seeding default categories (one-time)")
            val now = System.currentTimeMillis()
            val defaultCategories = listOf(
                Category(id = "daily", name = "日常", isDefault = true, createdAt = now),
                Category(id = "work", name = "工作", isDefault = true, createdAt = now),
                Category(id = "thoughts", name = "感悟", isDefault = true, createdAt = now),
                Category(id = "finance", name = "金融", isDefault = true, createdAt = now),
                Category(id = "health", name = "健康", isDefault = true, createdAt = now)
            )

            for (category in defaultCategories) {
                if (categoryDao.categoryExists(category.id) == 0) {
                    categoryDao.insertCategory(category)
                }
            }

            prefs.edit().putBoolean(seededKey, true).apply()
        } catch (e: Exception) {
            SecurityLog.e("NoteRepository", "Failed to seed default categories", e)
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

    // ---------- 标签页（tab）作用域内的列表/搜索 ----------

    fun getNoteSummariesByNotebook(notebookId: String): Flow<List<NoteSummary>> =
        noteDao.getNoteSummariesByNotebook(notebookId)

    fun getNoteSummariesByNotebookAndCategory(
        notebookId: String,
        categoryId: String
    ): Flow<List<NoteSummary>> =
        noteDao.getNoteSummariesByNotebookAndCategory(notebookId, categoryId)

    /**
     * 标签页内的智能搜索：支持文本搜索与日期格式搜索（与 [smartSearchNoteSummaries] 同款规则）。
     */
    fun smartSearchNoteSummariesInNotebook(
        notebookId: String,
        searchQuery: String
    ): Flow<List<NoteSummary>> {
        val dateRange = parseDateQuery(searchQuery)
        return if (dateRange != null) {
            noteDao.searchNoteSummariesWithDateOrTextInNotebook(
                notebookId, searchQuery, dateRange.first, dateRange.second
            )
        } else {
            noteDao.searchNoteSummariesInNotebook(notebookId, searchQuery)
        }
    }

    suspend fun getAllCategories(): List<Category> = categoryDao.getAllCategoriesOnce()
    
    suspend fun getNoteById(noteId: String): Note? = noteDao.getNoteById(noteId)
    
    suspend fun getFullNote(noteId: String): FullNote? = noteDao.getFullNote(noteId)
    
    suspend fun saveNote(note: Note) = noteDao.insertNote(note)
    
    suspend fun saveFullNote(fullNote: FullNote) = noteDao.saveFullNote(fullNote)
    
    suspend fun updateNote(note: Note) = noteDao.updateNote(note)
    
    suspend fun deleteNote(noteId: String) = noteDao.deleteFullNote(noteId)
    
    suspend fun createNewNote(
        title: String = "无标题",
        notebookId: String = DEFAULT_NOTEBOOK_ID
    ): String {
        val noteId = UUID.randomUUID().toString()
        // 归入指定标签页；若该标签页已不存在则回落默认标签页
        val resolvedNotebookId =
            if (notebookDao.notebookExists(notebookId) > 0) notebookId else DEFAULT_NOTEBOOK_ID
        val note = Note(
            id = noteId,
            title = title,
            categoryId = "daily", // 默认分类为日常
            notebookId = resolvedNotebookId,
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
    
    /** 导入结果统计：新增 / 更新（按更新时间取新覆盖）/ 跳过（重复且不更新）。 */
    data class ImportSummary(val added: Int, val updated: Int, val skipped: Int)

    private enum class ImportOutcome { ADDED, UPDATED, SKIPPED }

    /**
     * 把一批导入纪事落盘到指定标签页 [notebookId]，并在该标签页内去重。
     *
     * 去重规则（仅与目标标签页内的纪事比对）：
     *  1. ID 命中：目标内某纪事的 sourceId 或 自身 id == 导入纪事的 id → 同一条（幂等重导入）。
     *  2. 内容兜底：ID 未命中时，按「标题 + 全部文本内容」组成的内容键比对。
     * 命中后按 updatedAt 取新：导入更新→覆盖（清理旧媒体）；导入更旧或相同→跳过。
     * 未命中→新增。批内已处理的条目同样参与比对（同批去重）。
     */
    suspend fun importNotesIntoNotebook(
        importNotes: List<ExportImportUtils.ImportNote>,
        notebookId: String
    ): ImportSummary {
        val targetNotebookId =
            if (notebookDao.notebookExists(notebookId) > 0) notebookId else DEFAULT_NOTEBOOK_ID

        // 载入目标标签页内已有纪事（含块），作为内存中的去重基准；
        // 处理过程中实时更新，使同批后续条目也能识别到已处理的条目。
        val existing = noteDao.getAllNotesInNotebook(targetNotebookId).map { note ->
            FullNote(note, noteDao.getBlocksByNoteId(note.id))
        }.toMutableList()

        var added = 0
        var updated = 0
        var skipped = 0

        for (importNote in importNotes) {
            when (saveImportedNote(importNote, targetNotebookId, existing)) {
                ImportOutcome.ADDED -> added++
                ImportOutcome.UPDATED -> updated++
                ImportOutcome.SKIPPED -> skipped++
            }
        }

        return ImportSummary(added, updated, skipped)
    }

    private suspend fun saveImportedNote(
        importNote: ExportImportUtils.ImportNote,
        notebookId: String,
        existing: MutableList<FullNote>
    ): ImportOutcome {
        val duplicate = findDuplicate(importNote, existing)
        val resolvedCategoryId = resolveImportCategoryId(
            importCategoryId = importNote.categoryId,
            importCategoryName = importNote.categoryName
        )

        if (duplicate != null) {
            // 命中重复 → 按 updatedAt 取新；非更新即跳过（不落媒体，避免孤儿文件）
            if (importNote.updatedAt <= duplicate.note.updatedAt) {
                return ImportOutcome.SKIPPED
            }

            // 覆盖更新：清理旧媒体，复用原本地 id，重建块
            deleteBlockMedia(duplicate.blocks)
            val updatedNote = Note(
                id = duplicate.note.id,
                title = importNote.title,
                categoryId = resolvedCategoryId,
                notebookId = notebookId,
                sourceId = importNote.id.takeIf { it.isNotBlank() },
                isPinned = importNote.isPinned,
                createdAt = importNote.createdAt,
                updatedAt = importNote.updatedAt,
                version = 1
            )
            val blocks = buildImportedBlocks(importNote, updatedNote.id)
            noteDao.saveFullNote(FullNote(updatedNote, blocks))

            val idx = existing.indexOfFirst { it.note.id == duplicate.note.id }
            if (idx >= 0) existing[idx] = FullNote(updatedNote, blocks)
            return ImportOutcome.UPDATED
        }

        // 未命中 → 作为新纪事新增（生成新的本地 id，记录来源 id 供后续去重）
        val newNote = Note(
            id = UUID.randomUUID().toString(),
            title = importNote.title,
            categoryId = resolvedCategoryId,
            notebookId = notebookId,
            sourceId = importNote.id.takeIf { it.isNotBlank() },
            isPinned = importNote.isPinned,
            createdAt = importNote.createdAt,
            updatedAt = importNote.updatedAt,
            version = 1
        )
        val blocks = buildImportedBlocks(importNote, newNote.id)
        noteDao.saveFullNote(FullNote(newNote, blocks))
        existing.add(FullNote(newNote, blocks))
        return ImportOutcome.ADDED
    }

    /** 在目标标签页内查找重复：ID 优先（sourceId / 本地 id == 导出 id），内容兜底（标题+正文）。 */
    private fun findDuplicate(
        importNote: ExportImportUtils.ImportNote,
        existing: List<FullNote>
    ): FullNote? {
        if (importNote.id.isNotBlank()) {
            val byId = existing.firstOrNull {
                it.note.sourceId == importNote.id || it.note.id == importNote.id
            }
            if (byId != null) return byId
        }

        val key = contentKey(
            importNote.title,
            importNote.blocks
                .filter { it.type == BlockType.TEXT }
                .sortedBy { it.order }
                .map { it.text }
        )
        return existing.firstOrNull { full ->
            contentKey(
                full.note.title,
                full.blocks
                    .filter { it.type == BlockType.TEXT }
                    .sortedBy { it.order }
                    .map { it.text }
            ) == key
        }
    }

    /** 内容键：仅取文本（标题 + 各文本段按顺序拼接，去首尾空白），不含图片/音频。 */
    private fun contentKey(title: String?, texts: List<String?>): String =
        (title ?: "").trim() + "\n" + texts.joinToString("\n") { (it ?: "").trim() }

    /** 把导入纪事的块还原为本地块（图片/音频加密落盘到应用私有目录）。 */
    private fun buildImportedBlocks(
        importNote: ExportImportUtils.ImportNote,
        noteId: String
    ): List<NoteBlock> {
        val blocks = mutableListOf<NoteBlock>()
        for (importBlock in importNote.blocks) {
            val block = when (importBlock.type) {
                BlockType.TEXT -> NoteBlock(
                    id = UUID.randomUUID().toString(),
                    noteId = noteId,
                    type = importBlock.type,
                    order = importBlock.order,
                    text = importBlock.text,
                    createdAt = importNote.createdAt,
                    updatedAt = importNote.updatedAt
                )
                BlockType.IMAGE -> {
                    var filePath: String? = null
                    // 加密落盘到应用私有目录
                    if (importBlock.mediaFile?.exists() == true) {
                        val fileName = "imported_image_${System.currentTimeMillis()}_${importBlock.mediaFile.name}"
                        val targetFile = File(context.filesDir, "images/$fileName")
                        targetFile.parentFile?.mkdirs()
                        MediaCryptor.encryptBytes(importBlock.mediaFile.readBytes(), targetFile)
                        filePath = targetFile.absolutePath
                    }
                    NoteBlock(
                        id = UUID.randomUUID().toString(),
                        noteId = noteId,
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
                    // 加密落盘到应用私有目录
                    if (importBlock.mediaFile?.exists() == true) {
                        val fileName = "imported_audio_${System.currentTimeMillis()}_${importBlock.mediaFile.name}"
                        val targetFile = File(context.filesDir, "audios/$fileName")
                        targetFile.parentFile?.mkdirs()
                        MediaCryptor.encryptBytes(importBlock.mediaFile.readBytes(), targetFile)
                        filePath = targetFile.absolutePath
                    }
                    NoteBlock(
                        id = UUID.randomUUID().toString(),
                        noteId = noteId,
                        type = importBlock.type,
                        order = importBlock.order,
                        url = filePath,
                        duration = importBlock.duration,
                        createdAt = importNote.createdAt,
                        updatedAt = importNote.updatedAt
                    )
                }
                BlockType.FILE -> {
                    val originalName = importBlock.alt?.takeIf { it.isNotBlank() } ?: "attachment"
                    // 附件加密落盘到应用私有目录（与图片/音频同一条加密路径）
                    val saved = importBlock.mediaFile
                        ?.takeIf { it.exists() }
                        ?.let {
                            runCatching { AttachmentUtils.importAttachment(context, it, originalName) }
                                .onFailure { e ->
                                    SecurityLog.e("NoteRepository", "Failed to store imported attachment", e)
                                }
                                .getOrNull()
                        }

                    if (saved != null) {
                        NoteBlock(
                            id = UUID.randomUUID().toString(),
                            noteId = noteId,
                            type = BlockType.FILE,
                            order = importBlock.order,
                            // 兜底文本与导出契约一致：老客户端/降级时仍能看懂这里挂了什么
                            text = "[附件] $originalName",
                            url = saved.path,
                            alt = saved.name,
                            size = saved.size,
                            createdAt = importNote.createdAt,
                            updatedAt = importNote.updatedAt
                        )
                    } else {
                        // 落盘失败（例如超出加解密上限）→ 退化成文本块，不留空壳、不崩溃
                        NoteBlock(
                            id = UUID.randomUUID().toString(),
                            noteId = noteId,
                            type = BlockType.TEXT,
                            order = importBlock.order,
                            text = "[附件丢失] $originalName",
                            createdAt = importNote.createdAt,
                            updatedAt = importNote.updatedAt
                        )
                    }
                }
            }
            blocks.add(block)
        }
        return blocks
    }

    /** 删除一组块引用的本地媒体文件（覆盖更新时清理旧媒体，避免残留）。 */
    private fun deleteBlockMedia(blocks: List<NoteBlock>) {
        for (block in blocks) {
            val path = block.url ?: continue
            try {
                val file = File(path)
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                SecurityLog.w("NoteRepository", "Failed to delete old media on import update")
            }
        }
    }
    
    /**
     * 导入记事时解析分类 id：
     *  1. 本地已有同 id 的分类（默认分类 daily/work/thoughts 固定 id 跨设备一致）→ 用之
     *  2. 按名字在本地查找（用户自建分类跨设备 UUID 不同但名字一致）→ 用之
     *  3. 有非空名字但本地没有 → 创建一个非默认分类，id 用新 UUID，名字原样保留
     *  4. 无任何可用信息 → 回落到"日常"
     */
    private suspend fun resolveImportCategoryId(
        importCategoryId: String,
        importCategoryName: String
    ): String {
        // 1) id 命中
        if (importCategoryId.isNotBlank() && categoryDao.categoryExists(importCategoryId) > 0) {
            return importCategoryId
        }

        val trimmedName = importCategoryName.trim()
        if (trimmedName.isNotEmpty()) {
            // 2) 名字命中
            val byName = categoryDao.getAllCategoriesOnce()
                .firstOrNull { it.name.trim() == trimmedName }
            if (byName != null) return byName.id

            // 3) 自建
            val newId = UUID.randomUUID().toString()
            categoryDao.insertCategory(
                Category(
                    id = newId,
                    name = trimmedName,
                    isDefault = false,
                    createdAt = System.currentTimeMillis()
                )
            )
            SecurityLog.i("NoteRepository", "Created category on import: $trimmedName")
            return newId
        }

        // 4) 兜底
        return "daily"
    }

    // 分类相关方法
    suspend fun getAllCategoriesOnce(): List<Category> = categoryDao.getAllCategoriesOnce()
    
    suspend fun getCategoryById(categoryId: String): Category? = categoryDao.getCategoryById(categoryId)
    
    /**
     * 新建分类。若已存在同名分类（trim + 忽略大小写），直接复用其 id。
     */
    suspend fun createCategory(categoryName: String): CategoryCreateResult {
        val trimmed = categoryName.trim()
        val existing = categoryDao.getAllCategoriesOnce()
            .firstOrNull { it.name.trim().equals(trimmed, ignoreCase = true) }
        if (existing != null) {
            return CategoryCreateResult(existing.id, isNew = false)
        }

        val categoryId = UUID.randomUUID().toString()
        val category = Category(
            id = categoryId,
            name = trimmed,
            isDefault = false,
            createdAt = System.currentTimeMillis()
        )
        categoryDao.insertCategory(category)
        return CategoryCreateResult(categoryId, isNew = true)
    }

    data class CategoryCreateResult(val id: String, val isNew: Boolean)
    
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

    // ---------- 标签页（tab）相关方法 ----------

    suspend fun getAllNotebooksOnce(): List<Notebook> {
        // 兜底：保证默认标签页始终存在（覆盖全新安装时种子协程尚未完成的竞态）
        ensureDefaultNotebookExists()
        return notebookDao.getAllNotebooksOnce()
    }

    fun getAllNotebooksFlow(): Flow<List<Notebook>> = notebookDao.getAllNotebooks()

    suspend fun getNotebookById(notebookId: String): Notebook? =
        notebookDao.getNotebookById(notebookId)

    /** 新建一个空标签页，排在末尾。返回新建的标签页。 */
    suspend fun createNotebook(name: String): Notebook {
        val notebook = Notebook(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            order = notebookDao.getMaxOrder() + 1,
            createdAt = System.currentTimeMillis()
        )
        notebookDao.insertNotebook(notebook)
        return notebook
    }

    suspend fun renameNotebook(notebookId: String, newName: String) {
        val notebook = notebookDao.getNotebookById(notebookId) ?: return
        notebookDao.updateNotebook(notebook.copy(name = newName.trim()))
    }

    /**
     * 删除标签页：默认标签页不可删除；删除普通标签页时，其下纪事全部迁回默认标签页（不删除纪事）。
     */
    suspend fun deleteNotebook(notebookId: String) {
        if (notebookId == DEFAULT_NOTEBOOK_ID) {
            throw IllegalArgumentException("Cannot delete default notebook")
        }
        val notebook = notebookDao.getNotebookById(notebookId) ?: return
        // 纪事整体迁回默认标签页
        noteDao.moveNotesToNotebook(notebookId, DEFAULT_NOTEBOOK_ID)
        notebookDao.deleteNotebook(notebook)
    }
}