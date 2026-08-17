package com.example.xnote

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.xnote.audio.AudioPlayer
import com.example.xnote.audio.AudioRecorder
import com.example.xnote.data.BlockType
import com.example.xnote.data.Category
import com.example.xnote.data.FullNote
import com.example.xnote.data.NoteBlock
import com.example.xnote.databinding.ActivityNoteEditBinding
import com.example.xnote.repository.NoteRepository
import com.example.xnote.utils.AttachmentUtils
import com.example.xnote.utils.FileUtils
import com.example.xnote.utils.PermissionUtils
import com.example.xnote.utils.SecurityLog
import com.example.xnote.viewmodel.NoteEditViewModel
import com.example.xnote.viewmodel.NoteEditViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class NoteEditActivity : AppCompatActivity() {
    
    companion object {
        const val EXTRA_NOTE_ID = "note_id"

        /** 记住选择器上次所在位置用的偏好文件 */
        private const val PREFS_PICKER = "picker_prefs"
        private const val KEY_LAST_IMAGE_URI = "last_image_uri"
    }
    
    private lateinit var binding: ActivityNoteEditBinding
    private lateinit var noteId: String
    private var currentNote: FullNote? = null
    private var initialTitle: String = ""
    private var initialBlocks: List<NoteBlock> = emptyList()
    private var initialCategoryId: String = "daily"
    private var hasContentChanged = false
    private var currentCategoryId: String = "daily"
    
    private val viewModel: NoteEditViewModel by viewModels {
        NoteEditViewModelFactory(NoteRepository(this))
    }
    
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var audioPlayer: AudioPlayer
    private var isRecording = false
    private var currentPlayingBlockId: String? = null
    private var debugStep = 0
    
    private val recordingHandler = Handler(Looper.getMainLooper())
    private var recordingRunnable: Runnable? = null
    
    // Activity Result Launchers
    /** 选图片：直接开 SAF 文档选择器，选完记下位置，下次从这里开始 */
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                rememberLastImageLocation(uri)
                handleImageSelected(uri)
            }
        }
    }

    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { handleAudioSelected(it) } }

    /** 挂附件：任意格式，交给 SAF 选文件 */
    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { handleFileSelected(it) } }

    /** "另存为"目标位置选好后，把待保存的附件写过去 */
    private var pendingSaveAsBlock: NoteBlock? = null

    private val saveAsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val block = pendingSaveAsBlock
        pendingSaveAsBlock = null
        if (result.resultCode == Activity.RESULT_OK && block != null) {
            result.data?.data?.let { dest -> writeAttachmentTo(block, dest) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoteEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        noteId = intent.getStringExtra(EXTRA_NOTE_ID) ?: return
        
        initComponents()
        setupUI()
        loadNote()
        observeViewModel()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleNoteBeforeExit()
                finish()
            }
        })
    }
    
    private fun initComponents() {
        audioRecorder = AudioRecorder(this)
        audioPlayer = AudioPlayer(this)
        
        audioPlayer.setOnCompletionListener {
            // Handle audio playback completion
            currentPlayingBlockId?.let { blockId ->
                binding.richEditText.updateAudioPlaybackState(blockId, false, 0f)
                currentPlayingBlockId = null
            }
        }
        
        // 使用AudioPlayer内置的进度监听器
        audioPlayer.setOnProgressListener { current, total ->
            currentPlayingBlockId?.let { blockId ->
                val progress = if (total > 0) current.toFloat() / total.toFloat() else 0f
                android.util.Log.d("NoteEditActivity", "Progress update: current=$current, total=$total, progress=$progress")
                binding.richEditText.updateAudioPlaybackState(blockId, true, progress)
            }
        }
    }
    
    private fun setupUI() {
       //./ binding.toolbar.setNavigationOnClickListener {
         //   finish()
       // }
        
        binding.btnCategory.setOnClickListener {
            showCategoryDialog()
        }
        
        binding.btnSave.setOnClickListener {
            saveNote()
        }
        
        binding.btnAddImage.setOnClickListener {
            selectImageFromGallery()
        }
        
        binding.btnAddAudio.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                showAudioOptions()
            }
        }
        
        binding.btnAddFile.setOnClickListener {
            // 任意格式：不限定 MIME，让用户自己挑
            pickFileLauncher.launch(arrayOf("*/*"))
        }

        binding.btnStopRecording.setOnClickListener {
            stopRecording()
        }
        
        binding.btnCancelRecording.setOnClickListener {
            cancelRecording()
        }
        
        // 富文本编辑器设置
        binding.richEditText.setOnContentChangedListener { _ ->
            // 内容变更时的处理
            hasContentChanged = true
        }
        
        binding.richEditText.setOnMediaClickListener { block ->
            handleMediaClick(block)
        }
        
        // 默认隐藏调试信息，如需显示可以改为View.VISIBLE
        binding.debugStatusText.visibility = android.view.View.GONE
        
        // 文本变更监听
        binding.editTitle.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                // 标题内容有变更
                hasContentChanged = true
            }
        })
        
        // 标题回车键监听
        binding.editTitle.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN) {
                binding.richEditText.requestFocus()
                binding.richEditText.setSelection(0)
                true
            } else {
                false
            }
        }
    }
    
    private fun loadNote() {
        lifecycleScope.launch {
            try {
                currentNote = viewModel.loadNote(noteId)
                currentNote?.let { note ->
                    // 记录初始状态
                    initialTitle = note.note.title
                    initialBlocks = note.blocks.toList()
                    initialCategoryId = note.note.categoryId
                    currentCategoryId = note.note.categoryId
                    
                    binding.editTitle.setText(note.note.title)
                    binding.richEditText.loadFromBlocks(note.blocks)
                //./    binding.toolbar.title = if (note.note.title.isEmpty()) "编辑记事" else note.note.title
                    updateModifiedTime(note.note.updatedAt)
                    // updateCategoryDisplay() 已在 updateModifiedTime 中调用
                }
            } catch (e: Exception) {
                Toast.makeText(this@NoteEditActivity, "加载记事失败", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
    
    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.saveState.collect { state ->
                when (state) {
                    is NoteEditViewModel.SaveState.Saving -> {
                        binding.tvStatus.text = "正在保存..."
                        binding.tvStatus.visibility = View.VISIBLE
                    }
                    is NoteEditViewModel.SaveState.Success -> {
                        binding.tvStatus.text = "已保存"
                        binding.tvStatus.visibility = View.VISIBLE
                        // 更新修改时间
                        updateModifiedTime(System.currentTimeMillis())
                        // 重置变更标记和初始状态
                        hasContentChanged = false
                        initialTitle = binding.editTitle.text.toString()
                        initialBlocks = binding.richEditText.toBlocks()
                        initialCategoryId = currentCategoryId // 更新初始分类ID
                        // 1秒后隐藏状态
                        Handler(Looper.getMainLooper()).postDelayed({
                            binding.tvStatus.visibility = View.GONE
                        }, 1000)
                    }
                    is NoteEditViewModel.SaveState.Error -> {
                        binding.tvStatus.visibility = View.GONE
                        Toast.makeText(this@NoteEditActivity, "保存失败: ${state.message}", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        binding.tvStatus.visibility = View.GONE
                    }
                }
            }
        }
    }
    
    private fun saveNote() {
        val title = binding.editTitle.text.toString().trim()
        val blocks = binding.richEditText.toBlocks()
        
        lifecycleScope.launch {
            viewModel.saveNote(noteId, title, blocks, currentCategoryId)
        }
    }
    
    private fun updateModifiedTime(timestamp: Long) {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val formattedTime = formatter.format(Date(timestamp))
        val createdText = currentNote?.note?.createdAt?.let {
            " / 创建于 ${dateFormatter.format(Date(it))}"
        } ?: ""
        binding.tvModifiedTime.text = "修改于 $formattedTime$createdText"
        binding.tvModifiedTime.visibility = View.VISIBLE
        
        // 确保分类标签也显示
        updateCategoryDisplay()
    }
    
    private fun updateCategoryDisplay() {
        lifecycleScope.launch {
            try {
                SecurityLog.d("NoteEditActivity", "Updating category display")
                val category = viewModel.getCategoryById(currentCategoryId)
                if (category != null) {
                    SecurityLog.d("NoteEditActivity", "Category found and displayed")
                    binding.tvCategoryTag.text = category.name
                    binding.tvCategoryTag.visibility = View.VISIBLE
                } else {
                    SecurityLog.d("NoteEditActivity", "Category not found")
                    binding.tvCategoryTag.visibility = View.GONE
                }
            } catch (e: Exception) {
                SecurityLog.e("NoteEditActivity", "Failed to update category display", e)
                binding.tvCategoryTag.visibility = View.GONE
            }
        }
    }
    
    private fun checkContentChanged(): Boolean {
        val currentTitle = binding.editTitle.text.toString()
        val currentBlocks = binding.richEditText.toBlocks()
        
        // 检查标题是否改变
        if (currentTitle != initialTitle) {
            SecurityLog.d("NoteEditActivity", "Title content changed")
            return true
        }
        
        // 检查分类是否改变
        if (currentCategoryId != initialCategoryId) {
            SecurityLog.d("NoteEditActivity", "Category changed")
            return true
        }
        
        // 检查块数量是否改变
        if (currentBlocks.size != initialBlocks.size) {
            SecurityLog.d("NoteEditActivity", "Block count changed")
            return true
        }
        
        // 检查每个块是否改变
        for (i in currentBlocks.indices) {
            val current = currentBlocks[i]
            val initial = initialBlocks.getOrNull(i)
            
            if (initial == null) return true
            
            // 比较块的关键属性
            if (current.type != initial.type ||
                current.text != initial.text ||
                current.url != initial.url ||
                current.order != initial.order ||
                current.alt != initial.alt ||
                current.duration != initial.duration ||
                current.width != initial.width ||
                current.height != initial.height ||
                current.size != initial.size) {
                SecurityLog.d("NoteEditActivity", "Block content changed")
                return true
            }
        }
        
        return false
    }
    
    private fun isContentEmpty(): Boolean {
        val title = binding.editTitle.text.toString().trim()
        val blocks = binding.richEditText.toBlocks()
        
        // 如果标题不为空，认为有内容
        if (title.isNotEmpty()) {
            return false
        }
        
        // 检查所有文本块是否为空
        for (block in blocks) {
            when (block.type) {
                BlockType.TEXT -> {
                    if (!block.text.isNullOrBlank()) {
                        return false
                    }
                }
                BlockType.IMAGE, BlockType.AUDIO, BlockType.FILE -> {
                    // 有媒体/附件就认为有内容
                    return false
                }
            }
        }
        
        return true
    }
    
    private fun showAudioOptions() {
        val options = arrayOf("录音", "本地音频")
        val dialog = AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                when (which) {
                    1 -> selectAudioFromFiles()
                    0 -> startRecording()
                }
            }
            .create()
        
        dialog.setOnShowListener {
            dialog.listView?.let { listView ->
                for (i in 0 until listView.count) {
                    val textView = listView.getChildAt(i) as? android.widget.TextView
                    textView?.textSize = 18f
                    textView?.setPadding(48, 32, 48, 32)
                }
            }
        }
        
        dialog.show()
    }
    
    /**
     * 直接打开本地图片选择器（不再询问拍照）。
     * 若之前选过图，就把上次那张图的 URI 作为起始位置传进去，选择器会定位到它所在的目录。
     * 走 SAF，不需要存储权限。
     */
    private fun selectImageFromGallery() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                lastImageLocation()?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            }
        }
        try {
            pickImageLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "未找到可用的图片选择器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun lastImageLocation(): Uri? =
        getSharedPreferences(PREFS_PICKER, Context.MODE_PRIVATE)
            .getString(KEY_LAST_IMAGE_URI, null)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    private fun rememberLastImageLocation(uri: Uri) {
        getSharedPreferences(PREFS_PICKER, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_IMAGE_URI, uri.toString())
            .apply()
    }

    private fun selectAudioFromFiles() {
        if (PermissionUtils.hasStoragePermission(this)) {
            pickAudioLauncher.launch("audio/*")
        } else {
            PermissionUtils.requestStoragePermission(this)
        }
    }
    
    private fun handleAudioSelected(uri: Uri) {
        lifecycleScope.launch {
            try {
                val filePath = FileUtils.saveAudioToPrivateStorage(this@NoteEditActivity, uri)
                if (filePath != null) {
                    val duration = FileUtils.getAudioDuration(this@NoteEditActivity, filePath)
                    val block = NoteBlock(
                        id = UUID.randomUUID().toString(),
                        noteId = noteId,
                        type = BlockType.AUDIO,
                        order = 0,
                        url = filePath,
                        duration = duration
                    )
                    binding.richEditText.insertAudio(block)
                } else {
                    Toast.makeText(this@NoteEditActivity, "音频保存失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@NoteEditActivity, "处理音频失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun handleImageSelected(uri: Uri) {
        lifecycleScope.launch {
            try {
                val filePath = FileUtils.saveImageToPrivateStorage(this@NoteEditActivity, uri)
                if (filePath != null) {
                    val (width, height) = FileUtils.getImageSize(filePath)
                    val block = NoteBlock(
                        id = UUID.randomUUID().toString(),
                        noteId = noteId,
                        type = BlockType.IMAGE,
                        order = 0,
                        url = filePath,
                        alt = "图片",
                        width = width,
                        height = height
                    )
                    binding.richEditText.insertImage(block)
                } else {
                    Toast.makeText(this@NoteEditActivity, "图片保存失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@NoteEditActivity, "处理图片失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 挂入本机任意格式的文件。不解析内容，只加密落盘并作为一个附件块插入。
     */
    private fun handleFileSelected(uri: Uri) {
        lifecycleScope.launch {
            // 附件可达 50MB，加密落盘必须离开主线程
            val result = withContext(Dispatchers.IO) {
                AttachmentUtils.saveAttachmentToPrivateStorage(this@NoteEditActivity, uri)
            }

            when (result) {
                is AttachmentUtils.PickResult.Ok -> {
                    val block = NoteBlock(
                        id = UUID.randomUUID().toString(),
                        noteId = noteId,
                        type = BlockType.FILE,
                        order = 0,
                        // 兜底文本与导出契约一致
                        text = "[附件] ${result.attachment.name}",
                        url = result.attachment.path,
                        alt = result.attachment.name,
                        size = result.attachment.size
                    )
                    binding.richEditText.insertFile(block)
                }
                is AttachmentUtils.PickResult.TooLarge -> {
                    val limit = AttachmentUtils.formatSize(AttachmentUtils.MAX_ATTACHMENT_SIZE)
                    Toast.makeText(
                        this@NoteEditActivity,
                        "附件过大（${AttachmentUtils.formatSize(result.size)}），单个附件不能超过 $limit",
                        Toast.LENGTH_LONG
                    ).show()
                }
                is AttachmentUtils.PickResult.Failed -> {
                    Toast.makeText(this@NoteEditActivity, result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 附件块的操作菜单：打开 / 另存为 / 上移 / 下移 / 删除。
     */
    private fun showAttachmentOptions(block: NoteBlock) {
        val options = arrayOf("打开", "另存为", "上移", "下移", "删除")

        AlertDialog.Builder(this)
            .setTitle(block.alt ?: "附件")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openAttachment(block)
                    1 -> saveAttachmentAs(block)
                    2 -> if (!binding.richEditText.moveBlock(block.id, -1)) {
                        Toast.makeText(this, "已经在最前面了", Toast.LENGTH_SHORT).show()
                    }
                    3 -> if (!binding.richEditText.moveBlock(block.id, 1)) {
                        Toast.makeText(this, "已经在最后面了", Toast.LENGTH_SHORT).show()
                    }
                    4 -> confirmDeleteAttachment(block)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteAttachment(block: NoteBlock) {
        AlertDialog.Builder(this)
            .setTitle("删除附件")
            .setMessage("确定从这条纪事移除「${block.alt ?: "附件"}」吗？")
            .setPositiveButton("删除") { _, _ ->
                binding.richEditText.removeBlock(block.id)
                // 本地加密文件随之清理，避免留下孤儿文件
                block.url?.let { FileUtils.deleteFile(it) }
                hasContentChanged = true
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 打开：解密到 cache 的独立子目录（保留原文件名），再用 FileProvider 交给系统里已装的程序。
     */
    private fun openAttachment(block: NoteBlock) {
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    AttachmentUtils.decryptToOpenCache(this@NoteEditActivity, block)
                }.onFailure {
                    SecurityLog.e("NoteEditActivity", "Failed to decrypt attachment", it)
                }.getOrNull()
            }

            if (uri == null) {
                Toast.makeText(this@NoteEditActivity, "附件文件不存在或已损坏", Toast.LENGTH_SHORT).show()
                return@launch
            }

            try {
                startActivity(AttachmentUtils.viewIntentFor(uri, block.alt))
            } catch (e: ActivityNotFoundException) {
                // 没有能处理这种格式的程序：友好提示，不崩溃
                Toast.makeText(
                    this@NoteEditActivity,
                    "没有找到能打开「${block.alt ?: "该附件"}」的程序，可先「另存为」再用其他应用打开",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun saveAttachmentAs(block: NoteBlock) {
        pendingSaveAsBlock = block
        try {
            saveAsLauncher.launch(AttachmentUtils.createDocumentIntent(block.alt))
        } catch (e: ActivityNotFoundException) {
            pendingSaveAsBlock = null
            Toast.makeText(this, "系统没有可用的文件管理器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun writeAttachmentTo(block: NoteBlock, dest: Uri) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                AttachmentUtils.exportAttachmentTo(this@NoteEditActivity, block, dest)
            }
            val msg = if (ok) "已保存「${block.alt ?: "附件"}」" else "保存失败"
            Toast.makeText(this@NoteEditActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRecording() {
        if (PermissionUtils.hasAudioPermission(this)) {
            val filePath = audioRecorder.startRecording()
            if (filePath != null) {
                isRecording = true
                updateRecordingUI(true)
                startRecordingTimer()
            } else {
                Toast.makeText(this, "开始录音失败", Toast.LENGTH_SHORT).show()
            }
        } else {
            PermissionUtils.requestAudioPermission(this)
        }
    }
    
    private fun stopRecording() {
        val (filePath, duration) = audioRecorder.stopRecording()
        isRecording = false
        updateRecordingUI(false)
        stopRecordingTimer()
        
        if (filePath != null) {
            val block = NoteBlock(
                id = UUID.randomUUID().toString(),
                noteId = noteId,
                type = BlockType.AUDIO,
                order = 0,
                url = filePath,
                duration = duration
            )
            binding.richEditText.insertAudio(block)
        } else {
            Toast.makeText(this, "录音保存失败", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun cancelRecording() {
        audioRecorder.cancelRecording()
        isRecording = false
        updateRecordingUI(false)
        stopRecordingTimer()
    }
    
    private fun updateRecordingUI(recording: Boolean) {
        binding.recordingPanel.visibility = if (recording) View.VISIBLE else View.GONE
        binding.btnAddAudio.setImageResource(
            if (recording) android.R.drawable.ic_media_pause 
            else android.R.drawable.ic_btn_speak_now
        )
    }
    
    private fun startRecordingTimer() {
        recordingRunnable = object : Runnable {
            override fun run() {
                if (isRecording) {
                    val duration = audioRecorder.getCurrentDuration()
                    val timeText = String.format("%02d:%02d", duration / 60, duration % 60)
                    binding.tvRecordingTime.text = timeText
                    recordingHandler.postDelayed(this, 1000)
                }
            }
        }
        recordingHandler.post(recordingRunnable!!)
    }
    
    private fun stopRecordingTimer() {
        recordingRunnable?.let { recordingHandler.removeCallbacks(it) }
        recordingRunnable = null
    }
    
    private fun handleMediaClick(block: NoteBlock) {
        updateDebugStatus("点击${block.type}: ${block.id.take(8)}")
        
        when (block.type) {
            BlockType.AUDIO -> {
                block.url?.let { url ->
                    updateDebugStatus("音频文件: ${url.substringAfterLast("/")}")
                    if (currentPlayingBlockId == block.id) {
                        // 当前正在播放这个音频，切换播放/暂停
                        if (audioPlayer.isPlaying()) {
                            audioPlayer.pause()
                            val progress = if (audioPlayer.isPrepared()) {
                                val current = audioPlayer.getCurrentPosition()
                                val duration = audioPlayer.getDuration()
                                if (duration > 0) current.toFloat() / duration.toFloat() else 0f
                            } else 0f
                            updateDebugStatus("暂停播放, 进度: ${(progress * 100).toInt()}%")
                            binding.richEditText.updateAudioPlaybackState(block.id, false, progress)
                        } else {
                            audioPlayer.play()
                            val progress = if (audioPlayer.isPrepared()) {
                                val current = audioPlayer.getCurrentPosition()
                                val duration = audioPlayer.getDuration()
                                if (duration > 0) current.toFloat() / duration.toFloat() else 0f
                            } else 0f
                            updateDebugStatus("恢复播放, 进度: ${(progress * 100).toInt()}%")
                            binding.richEditText.updateAudioPlaybackState(block.id, true, progress)
                        }
                    } else {
                        // 切换到新音频
                        currentPlayingBlockId?.let { oldBlockId ->
                            binding.richEditText.updateAudioPlaybackState(oldBlockId, false, 0f)
                            updateDebugStatus("停止旧音频: ${oldBlockId.take(8)}")
                        }
                        
                        currentPlayingBlockId = block.id
                        updateDebugStatus("准备播放新音频...")
                        
                        // 准备并播放新音频
                        if (audioPlayer.prepare(url)) {
                            // 等待异步准备完成后播放
                            playWhenPrepared(block.id)
                        }
                    }
                }
            }
            BlockType.IMAGE -> {
                updateDebugStatus("显示图片大图")
                // 显示大图
                block.url?.let { imagePath ->
                    showImageViewer(imagePath)
                }
            }
            BlockType.FILE -> {
                updateDebugStatus("附件: ${block.alt}")
                showAttachmentOptions(block)
            }
            else -> {
                updateDebugStatus("未知媒体类型: ${block.type}")
            }
        }
    }
    
    private fun playWhenPrepared(blockId: String) {
        val handler = Handler(Looper.getMainLooper())
        val checkRunnable = object : Runnable {
            override fun run() {
                if (audioPlayer.isPrepared()) {
                    if (currentPlayingBlockId == blockId) { // 确保还是要播放这个音频
                        audioPlayer.play()
                        binding.richEditText.updateAudioPlaybackState(blockId, true, 0f)
                    }
                } else {
                    // 继续等待准备完成
                    handler.postDelayed(this, 50)
                }
            }
        }
        handler.postDelayed(checkRunnable, 50)
    }
    
    private fun updateDebugStatus(message: String) {
        // 可以通过设置visibility来控制是否显示调试信息
        if (binding.debugStatusText.visibility == android.view.View.VISIBLE) {
            runOnUiThread {
                debugStep++
                val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                binding.debugStatusText.text = "[$debugStep] $timestamp: $message"
            }
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        when (requestCode) {
            PermissionUtils.REQUEST_AUDIO_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startRecording()
                } else {
                    Toast.makeText(this, getString(R.string.audio_permission_required), Toast.LENGTH_SHORT).show()
                }
            }
            // 图片走 SAF 不再需要存储权限，这里只剩本地音频
            PermissionUtils.REQUEST_STORAGE_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    selectAudioFromFiles()
                } else {
                    Toast.makeText(this, getString(R.string.storage_permission_required), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        handleNoteBeforeExit()
        if (isRecording) {
            stopRecording()
        }
        audioPlayer.pause()
        currentPlayingBlockId?.let { blockId ->
            val progress = if (audioPlayer.isPrepared()) {
                val current = audioPlayer.getCurrentPosition()
                val duration = audioPlayer.getDuration()
                if (duration > 0) current.toFloat() / duration.toFloat() else 0f
            } else 0f
            binding.richEditText.updateAudioPlaybackState(blockId, false, progress)
        }
    }
    
    private fun handleNoteBeforeExit() {
        if (isContentEmpty()) {
            // 内容为空，删除记事
            lifecycleScope.launch {
                try {
                    viewModel.deleteNote(noteId)
                } catch (e: Exception) {
                    // 静默处理删除失败
                }
            }
        } else if (checkContentChanged()) {
            // 有内容且有变更，保存记事
            saveNote()
        }
    }
    
    private fun showCategoryDialog() {
        lifecycleScope.launch {
            try {
                val categories = viewModel.getAllCategories()
                val categoryNames = categories.map { it.name }.toMutableList()
                categoryNames.add("+ 自定义分类")
                
                // 找到当前分类的索引
                val currentCategory = categories.find { it.id == currentCategoryId }
                val currentIndex = if (currentCategory != null) {
                    categories.indexOf(currentCategory)
                } else {
                    0 // 默认选择第一个
                }
                
                AlertDialog.Builder(this@NoteEditActivity)
                    .setTitle("选择分类")
                    .setSingleChoiceItems(categoryNames.toTypedArray(), currentIndex) { dialog, which ->
                        if (which == categoryNames.size - 1) {
                            // 选择了"自定义分类"
                            dialog.dismiss()
                            showAddCategoryDialog()
                        } else {
                            // 选择了现有分类
                            val selectedCategory = categories[which]
                            currentCategoryId = selectedCategory.id
                            hasContentChanged = true
                            updateCategoryDisplay()
                            dialog.dismiss()
                            Toast.makeText(this@NoteEditActivity, "已设置分类: ${selectedCategory.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
                    
            } catch (e: Exception) {
                Toast.makeText(this@NoteEditActivity, "加载分类失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun showAddCategoryDialog() {
        val editText = android.widget.EditText(this).apply {
            hint = "输入新分类名称"
            maxLines = 1
        }
        
        AlertDialog.Builder(this)
            .setTitle("新建分类")
            .setView(editText)
            .setPositiveButton("创建") { _, _ ->
                val categoryName = editText.text.toString().trim()
                if (categoryName.isNotEmpty()) {
                    createNewCategory(categoryName)
                } else {
                    Toast.makeText(this, "分类名称不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun createNewCategory(categoryName: String) {
        lifecycleScope.launch {
            try {
                val result = viewModel.createCategory(categoryName)
                currentCategoryId = result.id
                hasContentChanged = true
                updateCategoryDisplay()
                val msg = if (result.isNew) {
                    "已创建并设置分类: $categoryName"
                } else {
                    "已存在同名分类，已使用现有: $categoryName"
                }
                Toast.makeText(this@NoteEditActivity, msg, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@NoteEditActivity, "创建分类失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun showImageViewer(imagePath: String) {
        try {
            // 先检查文件是否存在
            val file = java.io.File(imagePath)
            if (!file.exists()) {
                Toast.makeText(this, "图片文件不存在", Toast.LENGTH_SHORT).show()
                return
            }
            
            // 使用简单的全屏对话框
            val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialog.setContentView(R.layout.dialog_image_viewer)
            
            // 获取视图
            val imageView = dialog.findViewById<android.widget.ImageView>(R.id.imageView)
            val btnClose = dialog.findViewById<android.widget.ImageButton>(R.id.btnClose)
            val layoutImageInfo = dialog.findViewById<android.widget.LinearLayout>(R.id.layoutImageInfo)
            val tvImageSize = dialog.findViewById<android.widget.TextView>(R.id.tvImageSize)
            val tvImagePath = dialog.findViewById<android.widget.TextView>(R.id.tvImagePath)
            
            // 加载并显示图片（自动判别明文/XNC1 加密）
            val bitmap = com.example.xnote.security.MediaCryptor.decodeBitmapFull(file)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
                
                // 显示图片信息
                tvImageSize.text = "${bitmap.width} × ${bitmap.height}"
                tvImagePath.text = file.name
                layoutImageInfo.visibility = View.VISIBLE
                
                SecurityLog.d("ImageViewer", "Successfully loaded bitmap: ${bitmap.width}x${bitmap.height}")
            } else {
                // 图片加载失败，显示占位图
                imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                tvImageSize.text = "无法加载"
                tvImagePath.text = file.name
                layoutImageInfo.visibility = View.VISIBLE
                
                SecurityLog.e("ImageViewer", "Failed to decode bitmap")
            }
            
            // 关闭按钮
            btnClose.setOnClickListener {
                dialog.dismiss()
            }
            
            // 点击图片关闭
            imageView.setOnClickListener {
                dialog.dismiss()
            }
            
            // 点击背景关闭
            val rootView = dialog.findViewById<View>(android.R.id.content)
            rootView?.setOnClickListener {
                dialog.dismiss()
            }
            
            // 显示对话框
            dialog.show()
            
        } catch (e: Exception) {
            SecurityLog.e("ImageViewer", "Exception in showImageViewer", e)
            Toast.makeText(this, "显示图片时出错: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        audioRecorder.cancelRecording()
        audioPlayer.release()
        stopRecordingTimer()
    }
}
