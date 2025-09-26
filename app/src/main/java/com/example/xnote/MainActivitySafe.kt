package com.example.xnote

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.xnote.adapter.NoteAdapter
import com.example.xnote.data.NoteSummary
import com.example.xnote.databinding.ActivityMainBinding
import com.example.xnote.repository.NoteRepository
import com.example.xnote.viewmodel.MainViewModel
import com.example.xnote.viewmodel.MainViewModelFactory
import kotlinx.coroutines.launch

class MainActivitySafe : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var noteAdapter: NoteAdapter
    
    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(NoteRepository(this))
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupRecyclerView()
        setupUI()
        observeViewModel()
    }
    
    private fun setupRecyclerView() {
        noteAdapter = NoteAdapter(
            onNoteClick = { noteSummary ->
                openNote(noteSummary.id)
            }
        )
        
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivitySafe)
            adapter = noteAdapter
        }
    }
    
    private fun setupUI() {
        // 不设置 ActionBar，避免可能的冲突
        
        binding.fabNewNote.setOnClickListener {
            createNewNote()
        }
        
        // 删除功能已移至菜单，无需额外UI设置
    }
    
    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.notes.collect { notes ->
                noteAdapter.submitList(notes)
                updateEmptyState(notes.isEmpty())
            }
        }
    }
    
    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyLayout.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }
    
    private fun createNewNote() {
        lifecycleScope.launch {
            val noteId = viewModel.createNewNote()
            openNote(noteId)
        }
    }
    
    private fun openNote(noteId: String) {
        val intent = Intent(this, NoteEditActivity::class.java).apply {
            putExtra(NoteEditActivity.EXTRA_NOTE_ID, noteId)
        }
        startActivity(intent)
    }
}