package com.example.xnote.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.xnote.data.Category
import com.example.xnote.data.FullNote
import com.example.xnote.data.Note
import com.example.xnote.data.NoteBlock
import com.example.xnote.repository.NoteRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NoteEditViewModel(
    private val repository: NoteRepository
) : ViewModel() {
    
    sealed class SaveState {
        object Idle : SaveState()
        object Saving : SaveState()
        object Success : SaveState()
        data class Error(val message: String) : SaveState()
    }
    
    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()
    
    suspend fun loadNote(noteId: String): FullNote? {
        return try {
            repository.getFullNote(noteId)
        } catch (e: Exception) {
            _saveState.value = SaveState.Error("加载记事失败: ${e.message}")
            null
        }
    }
    
    fun saveNote(noteId: String, title: String, blocks: List<NoteBlock>, categoryId: String = "daily") {
        viewModelScope.launch {
            try {
                _saveState.value = SaveState.Saving
                
                val note = Note(
                    id = noteId,
                    title = title,
                    categoryId = categoryId,
                    updatedAt = System.currentTimeMillis()
                )
                
                // 为每个块分配正确的noteId和order
                val updatedBlocks = blocks.mapIndexed { index, block ->
                    block.copy(
                        noteId = noteId,
                        order = index,
                        updatedAt = System.currentTimeMillis()
                    )
                }
                
                val fullNote = FullNote(note, updatedBlocks)
                repository.saveFullNote(fullNote)
                
                _saveState.value = SaveState.Success
            } catch (e: Exception) {
                _saveState.value = SaveState.Error("保存失败: ${e.message}")
            }
        }
    }
    
    suspend fun getAllCategories(): List<Category> {
        return try {
            repository.getAllCategoriesOnce()
        } catch (e: Exception) {
            // 返回默认分类
            listOf(
                Category("daily", "日常", true),
                Category("work", "工作", true), 
                Category("thoughts", "感悟", true)
            )
        }
    }
    
    suspend fun createCategory(categoryName: String): String {
        return repository.createCategory(categoryName)
    }
    
    suspend fun getCategoryById(categoryId: String): Category? {
        return repository.getCategoryById(categoryId)
    }
    
    suspend fun deleteNote(noteId: String): Boolean {
        return try {
            repository.deleteNote(noteId)
            true
        } catch (e: Exception) {
            _saveState.value = SaveState.Error("删除失败: ${e.message}")
            false
        }
    }
}

class NoteEditViewModelFactory(
    private val repository: NoteRepository
) : ViewModelProvider.Factory {
    
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NoteEditViewModel::class.java)) {
            return NoteEditViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}