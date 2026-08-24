package com.example.xnote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.xnote.data.Category
import com.example.xnote.data.Notebook
import com.example.xnote.data.NoteSummary
import com.example.xnote.repository.NoteRepository
import com.example.xnote.utils.ExportImportUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import java.io.File

class MainViewModel(
    private val repository: NoteRepository
) : ViewModel() {

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    private val _selectedCategoryId = MutableStateFlow<String?>(null)
    val selectedCategoryId: StateFlow<String?> = _selectedCategoryId.asStateFlow()

    // ---------- 标签页（tab）状态 ----------
    private val _notebooks = MutableStateFlow<List<Notebook>>(emptyList())
    val notebooks: StateFlow<List<Notebook>> = _notebooks.asStateFlow()

    private val _selectedNotebookId = MutableStateFlow(NoteRepository.DEFAULT_NOTEBOOK_ID)
    val selectedNotebookId: StateFlow<String> = _selectedNotebookId.asStateFlow()

    private val _notes = MutableStateFlow<List<NoteSummary>>(emptyList())
    val notes: StateFlow<List<NoteSummary>> = _notes.asStateFlow()

    private val _isSearchMode = MutableStateFlow(false)
    val isSearchMode: StateFlow<Boolean> = _isSearchMode.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private var searchJob: Job? = null

    init {
        loadCategories()
        loadNotebooks()
        loadNotes()
    }

    private fun loadCategories() {
        viewModelScope.launch {
            _categories.value = repository.getAllCategories()
        }
    }

    fun refreshCategories() {
        loadCategories()
    }

    private fun loadNotebooks() {
        viewModelScope.launch {
            val list = repository.getAllNotebooksOnce()
            _notebooks.value = list
            // 当前选中的标签页若已不存在（如被删除），回落到默认标签页
            if (list.none { it.id == _selectedNotebookId.value }) {
                _selectedNotebookId.value = NoteRepository.DEFAULT_NOTEBOOK_ID
            }
        }
    }

    fun refreshNotebooks() {
        loadNotebooks()
    }

    fun selectNotebook(notebookId: String) {
        if (_selectedNotebookId.value == notebookId) return
        _selectedNotebookId.value = notebookId
        if (_isSearchMode.value) {
            search(_searchQuery.value)
        } else {
            loadNotes()
        }
    }

    private fun loadNotes() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val notebookId = _selectedNotebookId.value
            val selectedCategory = _selectedCategoryId.value
            val notesFlow = if (selectedCategory == null) {
                repository.getNoteSummariesByNotebook(notebookId)
            } else {
                repository.getNoteSummariesByNotebookAndCategory(notebookId, selectedCategory)
            }

            notesFlow.collect { noteList ->
                _notes.value = noteList
            }
        }
    }

    fun selectCategory(categoryId: String?) {
        _selectedCategoryId.value = categoryId
        if (!_isSearchMode.value) {
            loadNotes()
        }
    }

    fun search(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()

        if (query.isBlank()) {
            // 如果搜索为空，显示当前标签页内的所有记事
            loadNotes()
        } else {
            // 执行标签页作用域内的智能搜索
            searchJob = viewModelScope.launch {
                repository.smartSearchNoteSummariesInNotebook(_selectedNotebookId.value, query)
                    .collect { searchResults ->
                        _notes.value = searchResults
                    }
            }
        }
    }
    
    fun enterSearchMode() {
        _isSearchMode.value = true
    }
    
    fun exitSearchMode() {
        _isSearchMode.value = false
        _searchQuery.value = ""
        loadNotes() // 重新加载所有记事
    }
    
    //./suspend fun createNewNote(title: String = "无标题"): String {
    suspend fun createNewNote(title: String = ""): String {
        // 新建纪事归入当前所在标签页
        return repository.createNewNote(title, _selectedNotebookId.value)
    }

    // ---------- 标签页（tab）管理 ----------

    suspend fun createNotebook(name: String): Notebook {
        val notebook = repository.createNotebook(name)
        loadNotebooks()
        return notebook
    }

    suspend fun renameNotebook(notebookId: String, newName: String) {
        repository.renameNotebook(notebookId, newName)
        loadNotebooks()
    }

    suspend fun deleteNotebook(notebookId: String) {
        repository.deleteNotebook(notebookId)
        // 若删除的是当前标签页，切回默认标签页
        if (_selectedNotebookId.value == notebookId) {
            _selectedNotebookId.value = NoteRepository.DEFAULT_NOTEBOOK_ID
        }
        loadNotebooks()
        loadNotes()
    }
    
    suspend fun deleteCategory(categoryId: String) {
        repository.deleteCategory(categoryId)
        // 重新加载分类列表
        loadCategories()
        // 如果当前选中的分类被删除，切换到全部
        if (_selectedCategoryId.value == categoryId) {
            selectCategory(null)
        }
    }
    
    suspend fun toggleNotePinStatus(noteId: String, isPinned: Boolean) {
        repository.updateNotePinStatus(noteId, isPinned)
    }

    suspend fun batchTogglePin(noteIds: Set<String>, isPinned: Boolean) {
        noteIds.forEach { noteId ->
            repository.updateNotePinStatus(noteId, isPinned)
        }
    }

    suspend fun deleteNote(noteId: String) {
        repository.deleteNote(noteId)
    }
    
    suspend fun deleteNotes(noteIds: Set<String>) {
        viewModelScope.launch {
            noteIds.forEach { noteId ->
                repository.deleteNote(noteId)
            }
        }
    }
    
    suspend fun exportNotes(
        noteIds: Set<String>, 
        password: String,
        onProgress: (String) -> Unit,
        onSuccess: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val fullNotes = noteIds.mapNotNull { repository.getFullNote(it) }
        val categoryNameById = repository.getAllCategoriesOnce().associate { it.id to it.name }
        val exportUtils = ExportImportUtils(repository.context)
        // exportNotes 是普通函数：解密全部媒体 + AES-256 压缩整个 ZIP 都在调用线程上跑。
        // 调用方是 lifecycleScope.launch（Dispatchers.Main），笔记一多必 ANR。
        // 三个回调在 MainActivity 里都套了 runOnUiThread，切到 IO 后语义反而正确。
        withContext(Dispatchers.IO) {
            exportUtils.exportNotes(fullNotes, categoryNameById, password, onProgress, onSuccess, onError)
        }
    }
    
    /**
     * 导入备份 ZIP 到指定标签页并去重。
     *
     * @param existingNotebookId 目标已有标签页 id；与 [newNotebookName] 二选一。
     * @param newNotebookName    新建标签页名（仅在密码校验通过后才真正创建，避免残留空标签页）。
     * @param onSuccess          返回去重结果（新增/更新/跳过条数）与最终目标标签页 id。
     */
    suspend fun importNotes(
        zipFile: File,
        password: String,
        existingNotebookId: String?,
        newNotebookName: String?,
        onProgress: (String) -> Unit,
        onSuccess: (NoteRepository.ImportSummary, String) -> Unit,
        onError: (String) -> Unit
    ) {
        val exportUtils = ExportImportUtils(repository.context)
        // 同 exportNotes：解压 + 校验 + 解密同样是重活，必须离开主线程。
        // 内层的 viewModelScope.launch 仍会把入库那段调度回主线程，语义不变。
        withContext(Dispatchers.IO) {
            // 解析（含密码校验）成功后才进入导入与去重；密码错误走 onError，不会创建新标签页。
            exportUtils.importNotes(zipFile, password, onProgress,
                onSuccess = { importNotes ->
                    viewModelScope.launch {
                        try {
                            // 密码已校验通过：此时才创建新标签页
                            val targetNotebookId = when {
                                !newNotebookName.isNullOrBlank() ->
                                    repository.createNotebook(newNotebookName).id
                                !existingNotebookId.isNullOrBlank() -> existingNotebookId
                                else -> NoteRepository.DEFAULT_NOTEBOOK_ID
                            }

                            val summary = repository.importNotesIntoNotebook(importNotes, targetNotebookId)

                            // 清理临时文件
                            importNotes.firstOrNull()?.tempDir?.deleteRecursively()

                            // 刷新标签页清单，并切换到导入目标标签页
                            loadNotebooks()
                            _selectedNotebookId.value = targetNotebookId
                            loadNotes()

                            onSuccess(summary, targetNotebookId)
                        } catch (e: Exception) {
                            onError("导入失败：${e.message}")
                        }
                    }
                },
                onError = onError
            )
        }
    }
}

class MainViewModelFactory(
    private val repository: NoteRepository
) : ViewModelProvider.Factory {
    
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            return MainViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}