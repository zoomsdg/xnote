package com.example.xnote

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
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
import java.io.File

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var noteAdapter: NoteAdapter
    private var isBatchMode = false
    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(NoteRepository(this))
    }
    
    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleImportFile(it) }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupRecyclerView()
        setupUI()
        observeViewModel()
    }
    
    override fun onResume() {
        super.onResume()
        // 重新加载分类数据，确保从编辑页面返回时能看到新创建的分类
        viewModel.refreshCategories()
    }
    
    private fun setupUI() {
        setSupportActionBar(binding.toolbar)
        
        binding.fabNewNote.setOnClickListener {
            createNewNote()
        }

        // 搜索按钮：左下角 FAB，触发原菜单搜索项相同的逻辑
        binding.fabSearch.setOnClickListener {
            viewModel.enterSearchMode()
            invalidateOptionsMenu()
        }
        
        // 初始隐藏搜索栏
        binding.searchLayout.visibility = View.GONE
        
        // 搜索相关事件
        setupSearchFunctionality()
        
        // 设置分类过滤
        setupCategoryFilter()

        // 设置批量操作栏事件
        setupBatchActionBar()
    }
    
    private fun setupSearchFunctionality() {
        // 搜索框文本变化监听
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                viewModel.search(query)
                
                // 显示/隐藏清除按钮
                binding.btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
            }
        })
        
        // 搜索框键盘搜索按钮监听
        binding.searchEditText.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || 
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
        
        // 清除搜索按钮
        binding.btnClearSearch.setOnClickListener {
            binding.searchEditText.setText("")
            hideKeyboard()
        }
    }
    
    private fun setupRecyclerView() {
        noteAdapter = NoteAdapter(
            onNoteClick = { noteSummary ->
                if (!isBatchMode) {
                    openNote(noteSummary.id)
                }
            },
            onNoteLongClick = { noteSummary ->
                enterBatchMode()
                noteAdapter.toggleSelection(noteSummary.id)
            },
            onPinClick = { noteSummary ->
                lifecycleScope.launch {
                    viewModel.toggleNotePinStatus(noteSummary.id, !noteSummary.isPinned)
                    val message = if (!noteSummary.isPinned) "已置顶" else "已取消置顶"
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                }
            },
            onSelectionChanged = { selectedNotes ->
                updateBatchModeUI(selectedNotes)
            }
        )
        
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = noteAdapter
        }
    }
    
    private fun setupCategoryFilter() {
        // 初始化分类过滤UI将在observeViewModel中处理
    }

    private fun setupBatchActionBar() {
        binding.btnSelectAll.setOnClickListener {
            noteAdapter.selectAll()
        }

        binding.btnPinSelected.setOnClickListener {
            val selectedNotes = noteAdapter.getSelectedNotes()
            if (selectedNotes.isEmpty()) {
                Toast.makeText(this, "请先选择记事", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                batchTogglePin(selectedNotes)
            }
        }

        binding.btnExportSelected.setOnClickListener {
            val selectedNotes = noteAdapter.getSelectedNotes()
            if (selectedNotes.isEmpty()) {
                Toast.makeText(this, "请先选择记事", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showExportPasswordDialog(selectedNotes)
        }

        binding.btnDeleteSelected.setOnClickListener {
            val selectedNotes = noteAdapter.getSelectedNotes()
            if (selectedNotes.isEmpty()) {
                Toast.makeText(this, "请先选择记事", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showBatchDeleteConfirmDialog(selectedNotes)
        }
    }

    private fun createCategoryChips(categories: List<com.example.xnote.data.Category>, selectedCategoryId: String?) {
        binding.categoryChipsContainer.removeAllViews()
        
        // 添加"全部"选项
        val allButton = createCategoryChip("全部", null, selectedCategoryId == null)
        binding.categoryChipsContainer.addView(allButton)
        
        // 添加分类选项
        categories.forEach { category ->
            val chipButton = createCategoryChip(category.name, category.id, category.id == selectedCategoryId)
            binding.categoryChipsContainer.addView(chipButton)
        }
    }
    
    private fun createCategoryChip(text: String, categoryId: String?, isSelected: Boolean): Button {
        return Button(this).apply {
            this.text = text
            this.isSelected = isSelected
            setBackgroundResource(R.drawable.category_chip_selector)
            setTextColor(if (isSelected) 
                getColor(R.color.white) else getColor(R.color.primary_text))
            setPadding(28, 12, 28, 12)  // 增大padding，让按钮更大更易点击
            textSize = 14f  // 增大字体，提高可读性
            
            // 重要：移除默认的最小尺寸限制
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            
            // 设置文字居中
            gravity = android.view.Gravity.CENTER
            
            val params = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(16, 0, 0, 0)  // 增大按钮间距
            layoutParams = params
            
            setOnClickListener {
                viewModel.selectCategory(categoryId)
            }
            
            // 长按删除分类（除了"全部"和默认分类）
            if (categoryId != null && !isDefaultCategory(categoryId)) {
                setOnLongClickListener {
                    showDeleteCategoryDialog(text, categoryId)
                    true
                }
            }
        }
    }
    
    private fun isDefaultCategory(categoryId: String): Boolean {
        return categoryId == "daily" || categoryId == "work" || categoryId == "thoughts"
    }

    private fun showContextMenuForNote(noteSummary: NoteSummary) {
        val popupMenu = android.widget.PopupMenu(this, binding.recyclerView)
        popupMenu.menuInflater.inflate(R.menu.menu_note_context, popupMenu.menu)

        // 根据置顶状态显示/隐藏菜单项
        val pinItem = popupMenu.menu.findItem(R.id.action_pin)
        val unpinItem = popupMenu.menu.findItem(R.id.action_unpin)

        pinItem.isVisible = !noteSummary.isPinned
        unpinItem.isVisible = noteSummary.isPinned

        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_pin -> {
                    lifecycleScope.launch {
                        viewModel.toggleNotePinStatus(noteSummary.id, true)
                        Toast.makeText(this@MainActivity, "已置顶", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                R.id.action_unpin -> {
                    lifecycleScope.launch {
                        viewModel.toggleNotePinStatus(noteSummary.id, false)
                        Toast.makeText(this@MainActivity, "已取消置顶", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                R.id.action_edit -> {
                    openNote(noteSummary.id)
                    true
                }
                R.id.action_delete -> {
                    showDeleteSingleNoteDialog(noteSummary)
                    true
                }
                else -> false
            }
        }

        popupMenu.show()
    }

    private fun showDeleteSingleNoteDialog(noteSummary: NoteSummary) {
        AlertDialog.Builder(this)
            .setTitle("删除记事")
            .setMessage("确定要删除这条记事吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    viewModel.deleteNote(noteSummary.id)
                    Toast.makeText(this@MainActivity, "记事已删除", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteCategoryDialog(categoryName: String, categoryId: String) {
        AlertDialog.Builder(this)
            .setTitle("删除分类")
            .setMessage("确定要删除分类「$categoryName」吗？\n\n该分类下的所有记事将转移到「日常」分类。")
            .setPositiveButton("删除") { _, _ ->
                deleteCategoryWithConfirmation(categoryId, categoryName)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun deleteCategoryWithConfirmation(categoryId: String, categoryName: String) {
        lifecycleScope.launch {
            try {
                viewModel.deleteCategory(categoryId)
                Toast.makeText(this@MainActivity, "已删除分类「$categoryName」", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                when {
                    e.message?.contains("Cannot delete default categories") == true -> {
                        Toast.makeText(this@MainActivity, "不能删除默认分类", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        Toast.makeText(this@MainActivity, "删除分类失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    
    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.notes.collect { notes ->
                noteAdapter.submitList(notes)
                updateEmptyState(notes.isEmpty())
            }
        }
        
        lifecycleScope.launch {
            viewModel.categories.collect { categories ->
                // 创建分类过滤chips
                createCategoryChips(categories, viewModel.selectedCategoryId.value)
            }
        }
        
        lifecycleScope.launch {
            viewModel.selectedCategoryId.collect { selectedCategoryId ->
                // 更新分类chips选中状态
                createCategoryChips(viewModel.categories.value, selectedCategoryId)
            }
        }
        
        lifecycleScope.launch {
            viewModel.isSearchMode.collect { isSearchMode ->
                updateSearchModeUI(isSearchMode)
            }
        }
    }
    
    private fun updateSearchModeUI(isSearchMode: Boolean) {
        binding.searchLayout.visibility = if (isSearchMode) View.VISIBLE else View.GONE
        binding.fabSearch.visibility = if (isSearchMode) View.GONE else View.VISIBLE
        if (isSearchMode) {
            binding.searchEditText.requestFocus()
            showKeyboard()
        } else {
            binding.searchEditText.setText("")
            hideKeyboard()
        }
    }
    
    private fun showKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm.showSoftInput(binding.searchEditText, InputMethodManager.SHOW_IMPLICIT)
    }
    
    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
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
    
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }
    
    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val isSearchMode = viewModel.isSearchMode.value
        val selectedCount = if (isBatchMode) noteAdapter.getSelectedNotes().size else 0
        
       //./ menu?.findItem(R.id.action_delete_mode)?.isVisible = !isBatchMode && !isSearchMode
        menu?.findItem(R.id.action_cancel_delete)?.isVisible = isBatchMode
        menu?.findItem(R.id.action_export)?.isVisible = !isBatchMode && !isSearchMode
        menu?.findItem(R.id.action_import)?.isVisible = !isBatchMode && !isSearchMode
        menu?.findItem(R.id.action_export_selected)?.isVisible = isBatchMode
        menu?.findItem(R.id.action_delete_selected)?.isVisible = isBatchMode && selectedCount > 0
        
        return super.onPrepareOptionsMenu(menu)
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export -> {
                showExportDialog()
                true
            }
            R.id.action_import -> {
                showImportOptions()
                true
            }
            R.id.action_export_selected -> {
                showExportSelectedDialog()
                true
            }
            R.id.action_delete_mode -> {
                enterBatchMode()
                true
            }
            R.id.action_cancel_delete -> {
                exitBatchMode()
                true
            }
            R.id.action_delete_selected -> {
                showDeleteConfirmDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun enterBatchMode() {
        isBatchMode = true
        noteAdapter.setSelectionMode(true)
        binding.fabNewNote.visibility = View.GONE
        binding.fabSearch.visibility = View.GONE
        binding.batchActionBar.visibility = View.VISIBLE
        invalidateOptionsMenu()

        // 更新标题
        supportActionBar?.title = "选择记事"
    }
    
    private fun exitBatchMode() {
        isBatchMode = false
        noteAdapter.setSelectionMode(false)
        binding.fabNewNote.visibility = View.VISIBLE
        binding.fabSearch.visibility = View.VISIBLE
        binding.batchActionBar.visibility = View.GONE
        invalidateOptionsMenu()

        // 恢复标题
        supportActionBar?.title = getString(R.string.app_name)
    }
    
    private fun updateBatchModeUI(selectedNotes: Set<String>) {
        val count = selectedNotes.size
        supportActionBar?.title = if (count > 0) "已选择 $count 项" else "选择记事"

        // 刷新菜单状态，让删除按钮根据选择数量启用/禁用
        invalidateOptionsMenu()
    }

    private suspend fun batchTogglePin(selectedNotes: Set<String>) {
        // 检查选中的记事中是否有置顶的
        val notes = viewModel.notes.value
        val selectedNoteSummaries = notes.filter { selectedNotes.contains(it.id) }
        val hasPinnedNotes = selectedNoteSummaries.any { it.isPinned }

        // 如果有置顶的记事，则取消置顶；否则置顶
        val shouldPin = !hasPinnedNotes

        viewModel.batchTogglePin(selectedNotes, shouldPin)

        val message = if (shouldPin) {
            "已批量置顶 ${selectedNotes.size} 条记事"
        } else {
            "已批量取消置顶 ${selectedNotes.size} 条记事"
        }

        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        exitBatchMode()
    }

    private fun showBatchDeleteConfirmDialog(selectedNotes: Set<String>) {
        val count = selectedNotes.size

        AlertDialog.Builder(this)
            .setTitle("批量删除记事")
            .setMessage("确定要删除这 $count 条记事吗？此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                deleteSelectedNotes(selectedNotes)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteConfirmDialog() {
        val selectedNotes = noteAdapter.getSelectedNotes()
        val count = selectedNotes.size
        
        AlertDialog.Builder(this)
            .setTitle("删除记事")
            .setMessage("确定要删除这 $count 条记事吗？此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                deleteSelectedNotes(selectedNotes)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun deleteSelectedNotes(noteIds: Set<String>) {
        lifecycleScope.launch {
            viewModel.deleteNotes(noteIds)
            exitBatchMode()
        }
    }
    
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            viewModel.isSearchMode.value -> {
                viewModel.exitSearchMode()
                invalidateOptionsMenu()
            }
            isBatchMode -> {
                exitBatchMode()
            }
            else -> {
                super.onBackPressed()
            }
        }
    }
    
    private fun showExportDialog() {
        val notes = viewModel.notes.value
        if (notes.isEmpty()) {
            Toast.makeText(this, "没有记事可以导出", Toast.LENGTH_SHORT).show()
            return
        }
        
        val noteIds = notes.map { it.id }.toSet()
        showExportPasswordDialog(noteIds)
    }
    
    private fun showExportSelectedDialog() {
        val selectedNotes = noteAdapter.getSelectedNotes()
        if (selectedNotes.isEmpty()) {
            Toast.makeText(this, "请选择要导出的记事", Toast.LENGTH_SHORT).show()
            return
        }
        
        showExportPasswordDialog(selectedNotes)
    }
    
    private fun showExportPasswordDialog(noteIds: Set<String>) {
        val editText = EditText(this).apply {
            hint = "请输入导出密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        
        AlertDialog.Builder(this)
            .setTitle("导出记事")
            .setMessage("请设置一个密码来加密您的记事文件")
            .setView(editText)
            .setPositiveButton("导出") { _, _ ->
                val password = editText.text.toString()
                if (password.isNotEmpty()) {
                    performExport(noteIds, password)
                } else {
                    Toast.makeText(this, "密码不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun performExport(noteIds: Set<String>, password: String) {
        lifecycleScope.launch {
            viewModel.exportNotes(
                noteIds = noteIds,
                password = password,
                onProgress = { progress ->
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, progress, Toast.LENGTH_SHORT).show()
                    }
                },
                onSuccess = { file ->
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "导出成功：${file.name}", Toast.LENGTH_LONG).show()
                        exitBatchMode()
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
    }
    
    private fun showImportOptions() {
        importFileLauncher.launch("application/zip")
    }
    
    private fun handleImportFile(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val tempFile = File(cacheDir, "import_temp.zip")
            tempFile.outputStream().use { output ->
                inputStream?.copyTo(output)
            }
            
            showImportPasswordDialog(tempFile)
        } catch (e: Exception) {
            Toast.makeText(this, "读取文件失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun showImportPasswordDialog(zipFile: File) {
        val editText = EditText(this).apply {
            hint = "请输入导入密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        
        AlertDialog.Builder(this)
            .setTitle("导入记事")
            .setMessage("请输入文件的解密密码")
            .setView(editText)
            .setPositiveButton("导入") { _, _ ->
                val password = editText.text.toString()
                if (password.isNotEmpty()) {
                    showImportModeDialog(zipFile, password)
                } else {
                    Toast.makeText(this, "密码不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消") { _, _ ->
                zipFile.delete()
            }
            .show()
    }
    
    private fun showImportModeDialog(zipFile: File, password: String) {
        AlertDialog.Builder(this)
            .setTitle("导入模式")
            .setMessage("请选择导入模式：\n\n覆盖导入：删除所有现有记事，导入新记事\n追加导入：保留现有记事，添加新记事")
            .setPositiveButton("覆盖导入") { _, _ ->
                performImport(zipFile, password, true)
            }
            .setNeutralButton("追加导入") { _, _ ->
                performImport(zipFile, password, false)
            }
            .setNegativeButton("取消") { _, _ ->
                zipFile.delete()
            }
            .show()
    }
    
    private fun performImport(zipFile: File, password: String, overwrite: Boolean) {
        lifecycleScope.launch {
            viewModel.importNotes(
                zipFile = zipFile,
                password = password,
                overwrite = overwrite,
                onProgress = { progress ->
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, progress, Toast.LENGTH_SHORT).show()
                    }
                },
                onSuccess = { count ->
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "成功导入 $count 条记事", Toast.LENGTH_LONG).show()
                        zipFile.delete()
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                        zipFile.delete()
                    }
                }
            )
        }
    }
}
