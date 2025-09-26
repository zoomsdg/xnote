package com.example.xnote

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.xnote.data.BlockType
import com.example.xnote.data.Note
import com.example.xnote.data.NoteBlock
import com.example.xnote.repository.NoteRepository
import com.example.xnote.utils.FileUtils
import com.example.xnote.utils.ImageUtils
import com.example.xnote.utils.SecurityLog
import kotlinx.coroutines.launch
import java.io.File
import java.util.*

/**
 * 处理其他应用分享过来的内容
 */
class ShareTargetActivity : AppCompatActivity() {

    private lateinit var repository: NoteRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = NoteRepository(this)

        val intent = intent
        val action = intent.action
        val type = intent.type

        SecurityLog.d("ShareTarget", "Received share intent: action=$action, type=$type")

        when (action) {
            Intent.ACTION_SEND -> {
                when {
                    type == "text/plain" -> handleSharedText(intent)
                    type?.startsWith("image/") == true -> handleSharedImage(intent)
                    else -> {
                        Toast.makeText(this, "不支持的分享类型", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (type?.startsWith("image/") == true) {
                    handleSharedMultipleImages(intent)
                } else {
                    Toast.makeText(this, "不支持的分享类型", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            else -> {
                Toast.makeText(this, "无效的分享请求", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun handleSharedText(intent: Intent) {
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val sharedSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT)

        if (sharedText != null) {
            lifecycleScope.launch {
                try {
                    val title = if (!sharedSubject.isNullOrBlank()) {
                        sharedSubject
                    } else {
                        // 从文本中提取前几个字作为标题
                        val firstLine = sharedText.split("\n").firstOrNull()?.trim()
                        if (firstLine?.length ?: 0 > 30) {
                            firstLine?.substring(0, 30) + "..."
                        } else {
                            firstLine ?: "分享的文本"
                        }
                    }

                    val noteId = createNoteFromSharedContent(
                        title = title,
                        textContent = sharedText
                    )

                    showSuccessAndNavigate(noteId)
                } catch (e: Exception) {
                    SecurityLog.e("ShareTarget", "Failed to create note from text", e)
                    showErrorAndFinish("保存分享内容失败")
                }
            }
        } else {
            showErrorAndFinish("未找到分享的文本内容")
        }
    }

    private fun handleSharedImage(intent: Intent) {
        val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)

        if (imageUri != null) {
            lifecycleScope.launch {
                try {
                    val noteId = createNoteFromSharedContent(
                        title = "分享的图片",
                        imageUris = listOf(imageUri)
                    )
                    showSuccessAndNavigate(noteId)
                } catch (e: Exception) {
                    SecurityLog.e("ShareTarget", "Failed to create note from image", e)
                    showErrorAndFinish("保存分享图片失败")
                }
            }
        } else {
            showErrorAndFinish("未找到分享的图片")
        }
    }

    private fun handleSharedMultipleImages(intent: Intent) {
        val imageUris = mutableListOf<Uri>()

        // 从ClipData获取多张图片
        intent.clipData?.let { clipData ->
            for (i in 0 until clipData.itemCount) {
                val item = clipData.getItemAt(i)
                item.uri?.let { uri ->
                    imageUris.add(uri)
                }
            }
        }

        if (imageUris.isNotEmpty()) {
            lifecycleScope.launch {
                try {
                    val noteId = createNoteFromSharedContent(
                        title = "分享的图片 (${imageUris.size}张)",
                        imageUris = imageUris
                    )
                    showSuccessAndNavigate(noteId)
                } catch (e: Exception) {
                    SecurityLog.e("ShareTarget", "Failed to create note from multiple images", e)
                    showErrorAndFinish("保存分享图片失败")
                }
            }
        } else {
            showErrorAndFinish("未找到分享的图片")
        }
    }

    private suspend fun createNoteFromSharedContent(
        title: String,
        textContent: String? = null,
        imageUris: List<Uri>? = null
    ): String {
        val noteId = UUID.randomUUID().toString()
        val currentTime = System.currentTimeMillis()

        // 创建记事
        val note = Note(
            id = noteId,
            title = title,
            categoryId = "daily",
            createdAt = currentTime,
            updatedAt = currentTime
        )

        val blocks = mutableListOf<NoteBlock>()
        var order = 0

        // 添加文本块
        if (!textContent.isNullOrBlank()) {
            // 检查是否包含URL
            val urlPattern = Regex("https?://[^\\s]+")
            val urls = urlPattern.findAll(textContent).map { it.value }.toList()

            if (urls.isNotEmpty()) {
                // 如果包含链接，分别处理文本和链接
                var remainingText: String = textContent
                for (url in urls) {
                    // 添加链接前的文本
                    val parts = remainingText.split(url, limit = 2)
                    if (parts[0].isNotBlank()) {
                        blocks.add(createTextBlock(noteId, order++, parts[0].trim()))
                    }

                    // 添加链接说明
                    blocks.add(createTextBlock(noteId, order++, "链接: $url"))

                    remainingText = if (parts.size > 1) parts[1] else ""
                }

                // 添加剩余文本
                if (remainingText.isNotBlank()) {
                    blocks.add(createTextBlock(noteId, order++, remainingText.trim()))
                }
            } else {
                // 普通文本
                blocks.add(createTextBlock(noteId, order++, textContent))
            }
        }

        // 添加图片块
        imageUris?.forEach { imageUri ->
            try {
                val savedImagePath = saveSharedImage(imageUri)
                if (savedImagePath != null) {
                    blocks.add(createImageBlock(noteId, order++, savedImagePath))
                }
            } catch (e: Exception) {
                SecurityLog.e("ShareTarget", "Failed to save shared image", e)
                // 添加错误占位块
                blocks.add(createTextBlock(noteId, order++, "[图片保存失败]"))
            }
        }

        // 如果没有任何内容块，添加一个空文本块
        if (blocks.isEmpty()) {
            blocks.add(createTextBlock(noteId, order, ""))
        }

        // 保存记事
        repository.saveNote(note)

        // 保存所有块
        blocks.forEach { block ->
            val data = mutableMapOf<String, Any?>()
            block.text?.let { data["text"] = it }
            block.url?.let { data["url"] = it }
            block.alt?.let { data["alt"] = it }
            block.width?.let { data["width"] = it }
            block.height?.let { data["height"] = it }

            repository.addBlock(noteId, block.type, block.order, data)
        }

        return noteId
    }

    private fun createTextBlock(noteId: String, order: Int, text: String): NoteBlock {
        return NoteBlock(
            id = UUID.randomUUID().toString(),
            noteId = noteId,
            type = BlockType.TEXT,
            order = order,
            text = text
        )
    }

    private fun createImageBlock(noteId: String, order: Int, imagePath: String): NoteBlock {
        return NoteBlock(
            id = UUID.randomUUID().toString(),
            noteId = noteId,
            type = BlockType.IMAGE,
            order = order,
            url = imagePath,
            alt = "分享的图片"
        )
    }

    private suspend fun saveSharedImage(imageUri: Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(imageUri)
            if (inputStream != null) {
                val fileName = "shared_image_${System.currentTimeMillis()}.jpg"
                val targetFile = File(filesDir, "images/$fileName")
                targetFile.parentFile?.mkdirs()

                inputStream.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                // 压缩图片
                val compressedPath = ImageUtils.compressImageFile(targetFile.absolutePath)
                if (compressedPath != null && compressedPath != targetFile.absolutePath) {
                    targetFile.delete() // 删除原文件
                    compressedPath
                } else {
                    targetFile.absolutePath
                }
            } else {
                null
            }
        } catch (e: Exception) {
            SecurityLog.e("ShareTarget", "Failed to save shared image", e)
            null
        }
    }

    private fun showSuccessAndNavigate(noteId: String) {
        Toast.makeText(this, "分享内容已保存为新记事", Toast.LENGTH_SHORT).show()

        // 跳转到编辑页面
        val editIntent = Intent(this, NoteEditActivity::class.java).apply {
            putExtra(NoteEditActivity.EXTRA_NOTE_ID, noteId)
        }
        startActivity(editIntent)
        finish()
    }

    private fun showErrorAndFinish(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}