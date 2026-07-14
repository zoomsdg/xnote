package com.example.xnote.utils

import android.content.Context
import android.os.Environment
import com.example.xnote.data.FullNote
import com.example.xnote.data.NoteBlock
import com.example.xnote.data.BlockType
import com.example.xnote.security.MediaCryptor
import com.example.xnote.utils.SecurityLog
import com.example.xnote.utils.SecureFileValidator
import com.example.xnote.utils.SecureTempFileManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

/**
 * 记事导出导入工具类
 */
class ExportImportUtils(private val context: Context) {
    
    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    private val modifiedFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val createdFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private fun timeInfo(updatedAt: Long, createdAt: Long): String =
        "修改于 ${modifiedFormat.format(Date(updatedAt))} / 创建于 ${createdFormat.format(Date(createdAt))}"
    
    /**
     * 导出记事为加密ZIP文件
     *
     * @param categoryNameById  id→分类名的映射，用于把分类信息写进 ZIP
     *                          （跨设备重导入时按名字回落匹配）
     */
    fun exportNotes(
        notes: List<FullNote>,
        categoryNameById: Map<String, String>,
        password: String,
        onProgress: (String) -> Unit = {},
        onSuccess: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        var tempDir: File? = null
        try {
            onProgress("准备导出数据...")
            
            // 创建安全的临时目录
            tempDir = SecureTempFileManager.createSecureTempDir(context, "export_temp")
            
            // 创建媒体文件目录
            val mediaDir = File(tempDir, "media")
            mediaDir.mkdirs()
            
            onProgress("处理记事数据...")
            
            // 导出记事数据
            val exportData = mutableListOf<ExportNote>()
            
            for (note in notes) {
                val title = note.note.title.ifBlank { "无标题" }
                onProgress("导出：$title (${timeInfo(note.note.updatedAt, note.note.createdAt)})")

                val exportBlocks = mutableListOf<ExportBlock>()

                for (block in note.blocks) {
                    val exportBlock = when (block.type) {
                        BlockType.TEXT -> {
                            ExportBlock(
                                type = "text",
                                order = block.order,
                                text = block.text
                            )
                        }
                        BlockType.IMAGE -> {
                            // 解密落到导出临时目录（ZIP 内必须是明文 JPEG）
                            val originalFile = File(block.url ?: "")
                            if (originalFile.exists()) {
                                val fileValidation = SecureFileValidator.validateFileSize(originalFile)
                                if (fileValidation is SecureFileValidator.ValidationResult.Valid) {
                                    val fileName = "image_${UUID.randomUUID()}.${originalFile.extension}"
                                    val targetFile = SecureFileValidator.createSecureExtractPath(mediaDir, fileName)
                                    targetFile.writeBytes(MediaCryptor.readAll(originalFile))

                                    ExportBlock(
                                        type = "image",
                                        order = block.order,
                                        mediaFileName = fileName,
                                        alt = block.alt,
                                        width = block.width,
                                        height = block.height
                                    )
                                } else {
                                    SecurityLog.w("ExportImportUtils", "Skipping invalid image file")
                                    ExportBlock(type = "text", order = block.order, text = "[图片文件无效或过大]")
                                }
                            } else {
                                ExportBlock(type = "text", order = block.order, text = "[图片文件丢失]")
                            }
                        }
                        BlockType.AUDIO -> {
                            // 解密落到导出临时目录（ZIP 内必须是明文音频）
                            val originalFile = File(block.url ?: "")
                            if (originalFile.exists()) {
                                val fileValidation = SecureFileValidator.validateFileSize(originalFile)
                                if (fileValidation is SecureFileValidator.ValidationResult.Valid) {
                                    val fileName = "audio_${UUID.randomUUID()}.${originalFile.extension}"
                                    val targetFile = SecureFileValidator.createSecureExtractPath(mediaDir, fileName)
                                    targetFile.writeBytes(MediaCryptor.readAll(originalFile))

                                    ExportBlock(
                                        type = "audio",
                                        order = block.order,
                                        mediaFileName = fileName,
                                        duration = block.duration
                                    )
                                } else {
                                    SecurityLog.w("ExportImportUtils", "Skipping invalid audio file")
                                    ExportBlock(type = "text", order = block.order, text = "[音频文件无效或过大]")
                                }
                            } else {
                                ExportBlock(type = "text", order = block.order, text = "[音频文件丢失]")
                            }
                        }
                        BlockType.FILE -> {
                            // 附件：解密落到导出临时目录（ZIP 内必须是明文原文件）
                            val originalFile = File(block.url ?: "")
                            val originalName = block.alt?.takeIf { it.isNotBlank() } ?: "attachment"
                            if (originalFile.exists()) {
                                // 附件可为任意格式、任意大小（本机挂入时已卡 50MB），此处不再套用媒体的大小校验
                                val fileName =
                                    "file_${UUID.randomUUID()}${AttachmentUtils.extensionSuffix(originalName)}"
                                val targetFile = SecureFileValidator.createSecureExtractPath(mediaDir, fileName)
                                targetFile.writeBytes(MediaCryptor.readAll(originalFile))

                                ExportBlock(
                                    type = "file",
                                    order = block.order,
                                    // 兜底：不认识 "file" 的老客户端会把它当文本块显示，不能省
                                    text = "[附件] $originalName",
                                    mediaFileName = fileName,
                                    alt = originalName
                                )
                            } else {
                                ExportBlock(
                                    type = "text",
                                    order = block.order,
                                    text = "[附件丢失] $originalName"
                                )
                            }
                        }
                    }
                    exportBlocks.add(exportBlock)
                }
                
                exportData.add(
                    ExportNote(
                        id = note.note.id,
                        title = note.note.title,
                        categoryId = note.note.categoryId,
                        categoryName = categoryNameById[note.note.categoryId] ?: "",
                        isPinned = note.note.isPinned,
                        createdAt = note.note.createdAt,
                        updatedAt = note.note.updatedAt,
                        version = note.note.version,
                        blocks = exportBlocks
                    )
                )
            }
            
            // 写入JSON数据文件
            val dataFile = File(tempDir, "notes_data.json")
            FileWriter(dataFile).use { writer ->
                gson.toJson(exportData, writer)
            }
            
            onProgress("创建加密ZIP文件...")
            
            // 创建输出ZIP文件到下载目录
            val timestamp = dateFormat.format(Date())
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            
            // 确保下载目录存在
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            
            val outputFile = File(downloadsDir, "XNote_Export_$timestamp.zip")
            
            // 创建加密ZIP
            val zipFile = ZipFile(outputFile)
            val zipParameters = ZipParameters().apply {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
            
            zipFile.setPassword(password.toCharArray())
            
            // 添加数据文件
            zipFile.addFile(dataFile, zipParameters)
            
            // 添加媒体文件目录（如果有文件）
            if (mediaDir.listFiles()?.isNotEmpty() == true) {
                zipFile.addFolder(mediaDir, zipParameters)
            }
            
            onProgress("导出完成！")
            onSuccess(outputFile)
            SecurityLog.i("ExportImportUtils", "Successfully exported notes")
            
        } catch (e: Exception) {
            SecurityLog.e("ExportImportUtils", "Export failed", e)
            onError("导出失败：${e.message}")
        } finally {
            // 安全清理临时文件
            tempDir?.let { SecureTempFileManager.secureDeleteDir(it) }
        }
    }
    
    /**
     * 从加密ZIP文件导入记事
     */
    fun importNotes(
        zipFile: File,
        password: String,
        onProgress: (String) -> Unit = {},
        onSuccess: (List<ImportNote>) -> Unit,
        onError: (String) -> Unit
    ) {
        var tempDir: File? = null
        try {
            onProgress("验证ZIP文件安全性...")
            
            // 首先验证ZIP文件安全性
            val zipValidation = SecureFileValidator.validateZipFile(zipFile)
            if (zipValidation !is SecureFileValidator.ValidationResult.Valid) {
                val reason = (zipValidation as SecureFileValidator.ValidationResult.Invalid).reason
                SecurityLog.w("ExportImportUtils", "ZIP validation failed: $reason")
                onError(reason)
                return
            }
            
            val zip = ZipFile(zipFile)
            zip.setPassword(password.toCharArray())
            
            onProgress("解密文件...")
            
            // 创建安全的临时解压目录
            tempDir = SecureTempFileManager.createSecureTempDir(context, "import_temp")
            
            try {
                // 安全解压文件
                val headers = zip.fileHeaders
                for (header in headers) {
                    val fileName = header.fileName
                    
                    // 再次验证每个文件名的安全性
                    val nameValidation = SecureFileValidator.validateFileName(fileName)
                    if (nameValidation !is SecureFileValidator.ValidationResult.Valid) {
                        SecurityLog.w("ExportImportUtils", "Unsafe filename detected: $fileName")
                        continue // 跳过不安全的文件
                    }
                    
                    // 创建安全的解压路径
                    val targetFile = SecureFileValidator.createSecureExtractPath(tempDir, fileName)
                    targetFile.parentFile?.mkdirs()
                    
                    // 解压单个文件
                    zip.extractFile(header, tempDir.absolutePath)
                }
            } catch (e: Exception) {
                SecurityLog.e("ExportImportUtils", "Decryption failed", e)
                onError("解密失败，请检查密码是否正确")
                return
            }
            
            onProgress("读取记事数据...")
            
            // 读取数据文件
            val dataFile = File(tempDir, "notes_data.json")
            if (!dataFile.exists()) {
                SecurityLog.w("ExportImportUtils", "Data file missing")
                onError("ZIP文件格式不正确，缺少数据文件")
                return
            }
            
            // 验证数据文件大小
            val dataValidation = SecureFileValidator.validateFileSize(dataFile, 5 * 1024 * 1024) // 5MB限制
            if (dataValidation !is SecureFileValidator.ValidationResult.Valid) {
                SecurityLog.w("ExportImportUtils", "Data file too large")
                onError("数据文件过大")
                return
            }
            
            val exportData: List<ExportNote> = try {
                val json = dataFile.readText()
                val type = object : TypeToken<List<ExportNote>>() {}.type
                gson.fromJson(json, type)
            } catch (e: Exception) {
                SecurityLog.e("ExportImportUtils", "Data parsing failed", e)
                onError("数据文件格式错误：${e.message}")
                return
            }
            
            // 验证导入数据的合理性
            if (exportData.size > 10000) { // 限制记事数量
                SecurityLog.w("ExportImportUtils", "Too many notes to import: ${exportData.size}")
                onError("导入的记事数量过多")
                return
            }
            
            onProgress("处理媒体文件...")
            
            val mediaDir = File(tempDir, "media")
            val importNotes = mutableListOf<ImportNote>()
            
            for (exportNote in exportData) {
                val title = exportNote.title.ifBlank { "无标题" }
                onProgress("导入：$title (${timeInfo(exportNote.updatedAt, exportNote.createdAt)})")

                val importBlocks = mutableListOf<ImportBlock>()

                for (exportBlock in exportNote.blocks) {
                    val importBlock = when (exportBlock.type) {
                        "text" -> ImportBlock(
                            type = BlockType.TEXT,
                            order = exportBlock.order,
                            text = exportBlock.text?.take(10000) // 限制文本长度
                        )
                        "image" -> {
                            var mediaFile: File? = null
                            if (exportBlock.mediaFileName != null) {
                                val potentialFile = SecureFileValidator.createSecureExtractPath(mediaDir, exportBlock.mediaFileName)
                                if (potentialFile.exists()) {
                                    // 验证媒体文件
                                    val mediaValidation = SecureFileValidator.validateFileSize(potentialFile)
                                    if (mediaValidation is SecureFileValidator.ValidationResult.Valid) {
                                        mediaFile = potentialFile
                                    } else {
                                        SecurityLog.w("ExportImportUtils", "Skipping invalid media file: ${exportBlock.mediaFileName}")
                                    }
                                }
                            }
                            
                            ImportBlock(
                                type = BlockType.IMAGE,
                                order = exportBlock.order,
                                mediaFile = mediaFile,
                                alt = exportBlock.alt,
                                width = exportBlock.width,
                                height = exportBlock.height
                            )
                        }
                        "audio" -> {
                            var mediaFile: File? = null
                            if (exportBlock.mediaFileName != null) {
                                val potentialFile = SecureFileValidator.createSecureExtractPath(mediaDir, exportBlock.mediaFileName)
                                if (potentialFile.exists()) {
                                    // 验证媒体文件
                                    val mediaValidation = SecureFileValidator.validateFileSize(potentialFile)
                                    if (mediaValidation is SecureFileValidator.ValidationResult.Valid) {
                                        mediaFile = potentialFile
                                    } else {
                                        SecurityLog.w("ExportImportUtils", "Skipping invalid media file: ${exportBlock.mediaFileName}")
                                    }
                                }
                            }
                            
                            ImportBlock(
                                type = BlockType.AUDIO,
                                order = exportBlock.order,
                                mediaFile = mediaFile,
                                duration = exportBlock.duration
                            )
                        }
                        "file" -> {
                            // 原始文件名在 alt；alt 缺失时退而用 ZIP 内的名字，保证有东西可显示
                            val originalName = exportBlock.alt?.takeIf { it.isNotBlank() }
                                ?: exportBlock.mediaFileName?.takeIf { it.isNotBlank() }
                                ?: "attachment"

                            // 附件不套用媒体的大小校验：导入既有附件时不卡 50MB 上限，宁可收下也不丢数据
                            val mediaFile = exportBlock.mediaFileName
                                ?.takeIf { it.isNotBlank() }
                                ?.let { SecureFileValidator.createSecureExtractPath(mediaDir, it) }
                                ?.takeIf { it.exists() }

                            if (mediaFile != null) {
                                ImportBlock(
                                    type = BlockType.FILE,
                                    order = exportBlock.order,
                                    mediaFile = mediaFile,
                                    alt = originalName
                                )
                            } else {
                                // media/ 里找不到实体 → 退化成文本块，不留空壳
                                SecurityLog.w("ExportImportUtils", "Attachment missing in media/")
                                ImportBlock(
                                    type = BlockType.TEXT,
                                    order = exportBlock.order,
                                    text = "[附件丢失] $originalName"
                                )
                            }
                        }
                        else -> {
                            // 未知 type 一律回落文本块，绝不抛异常。
                            // 优先用块自带的兜底文本（新版本写入的 text 字段就是给老客户端看的）
                            SecurityLog.w("ExportImportUtils", "Unknown block type: ${exportBlock.type}")
                            ImportBlock(
                                type = BlockType.TEXT,
                                order = exportBlock.order,
                                text = exportBlock.text?.take(10000) ?: "[未知类型的内容]"
                            )
                        }
                    }
                    importBlocks.add(importBlock)
                }
                
                importNotes.add(
                    ImportNote(
                        id = exportNote.id,
                        title = exportNote.title.take(200), // 限制标题长度
                        // 向后兼容：老版本导出的 ZIP 没有分类字段，回落到 "daily"/"日常"
                        categoryId = exportNote.categoryId?.takeIf { it.isNotBlank() } ?: "daily",
                        categoryName = exportNote.categoryName?.take(50)?.trim().orEmpty(),
                        // 向后兼容：老版本 ZIP 没有置顶字段，回落到 false
                        isPinned = exportNote.isPinned ?: false,
                        createdAt = exportNote.createdAt,
                        updatedAt = exportNote.updatedAt,
                        version = exportNote.version,
                        blocks = importBlocks,
                        tempDir = tempDir
                    )
                )
            }
            
            onProgress("导入完成！")
            onSuccess(importNotes)
            SecurityLog.i("ExportImportUtils", "Successfully imported ${importNotes.size} notes")
            
        } catch (e: Exception) {
            SecurityLog.e("ExportImportUtils", "Import failed", e)
            onError("导入失败：${e.message}")
            // 发生异常时立即清理临时文件
            tempDir?.let { SecureTempFileManager.secureDeleteDir(it) }
        }
    }
    
    // 导出数据结构
    data class ExportNote(
        val id: String,
        val title: String,
        /** 原始分类 id；老版本 ZIP 可能为 null */
        val categoryId: String? = null,
        /** 原始分类显示名（如"工作"/"感悟"/"日常"或用户自建名）；老版本 ZIP 可能为 null */
        val categoryName: String? = null,
        /** 是否置顶；老版本 ZIP 可能为 null（导入时回落到 false） */
        val isPinned: Boolean? = null,
        val createdAt: Long,
        val updatedAt: Long,
        val version: Int,
        val blocks: List<ExportBlock>
    )
    
    data class ExportBlock(
        val type: String,
        val order: Int,
        val text: String? = null,
        val mediaFileName: String? = null,
        val alt: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val duration: Long? = null
    )
    
    // 导入数据结构
    data class ImportNote(
        val id: String,
        val title: String,
        /** 解析出的分类 id（老版本 ZIP 回落到 "daily"） */
        val categoryId: String,
        /** 解析出的分类名；无名时为空串。导入端优先用名字回落匹配/自建 */
        val categoryName: String,
        /** 是否置顶；老版本 ZIP 回落到 false */
        val isPinned: Boolean,
        val createdAt: Long,
        val updatedAt: Long,
        val version: Int,
        val blocks: List<ImportBlock>,
        val tempDir: File
    )
    
    data class ImportBlock(
        val type: BlockType,
        val order: Int,
        val text: String? = null,
        val mediaFile: File? = null,
        val alt: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val duration: Long? = null
    )
}