package com.example.xnote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.xnote.data.Category
import com.example.xnote.data.NoteSummary
import com.example.xnote.repository.NoteRepository
import com.example.xnote.utils.ExportImportUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.io.File

class MainViewModel(
    private val repository: NoteRepository
) : ViewModel() {
    
    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()
    
    private val _selectedCategoryId = MutableStateFlow<String?>(null)
    val selectedCategoryId: StateFlow<String?> = _selectedCategoryId.asStateFlow()
    
    private val _notes = MutableStateFlow<List<NoteSummary>>(emptyList())
    val notes: StateFlow<List<NoteSummary>> = _notes.asStateFlow()
    
    private val _isSearchMode = MutableStateFlow(false)
    val isSearchMode: StateFlow<Boolean> = _isSearchMode.asStateFlow()
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    
    private var searchJob: Job? = null
    
    init {
        loadCategories()
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
    
    private fun loadNotes() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val selectedCategory = _selectedCategoryId.value
            val notesFlow = if (selectedCategory == null) {
                repository.getNoteSummaries()
            } else {
                repository.getNoteSummariesByCategory(selectedCategory)
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
            // 如果搜索为空，显示所有记事
            loadNotes()
        } else {
            // 执行智能搜索
            searchJob = viewModelScope.launch {
                repository.smartSearchNoteSummaries(query).collect { searchResults ->
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
        return repository.createNewNote(title)
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
        exportUtils.exportNotes(fullNotes, categoryNameById, password, onProgress, onSuccess, onError)
    }
    
    suspend fun importNotes(
        zipFile: File,
        password: String,
        overwrite: Boolean,
        onProgress: (String) -> Unit,
        onSuccess: (Int) -> Unit,
        onError: (String) -> Unit
    ) {
        val exportUtils = ExportImportUtils(repository.context)
        exportUtils.importNotes(zipFile, password, onProgress, 
            onSuccess = { importNotes ->
                viewModelScope.launch {
                    try {
                        var importedCount = 0
                        
                        if (overwrite) {
                            // 清空所有现有记事
                            repository.deleteAllNotes()
                        }
                        
                        for (importNote in importNotes) {
                            repository.importNote(importNote)
                            importedCount++
                        }
                        
                        // 清理临时文件
                        importNotes.firstOrNull()?.tempDir?.deleteRecursively()
                        
                        onSuccess(importedCount)
                    } catch (e: Exception) {
                        onError("导入失败：${e.message}")
                    }
                }
            },
            onError = onError
        )
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