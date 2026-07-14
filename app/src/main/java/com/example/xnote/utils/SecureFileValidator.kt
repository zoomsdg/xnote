package com.example.xnote.utils

import com.example.xnote.utils.SecurityLog
import net.lingala.zip4j.ZipFile
import java.io.File
import java.nio.file.Paths
import kotlin.math.min

/**
 * 安全文件验证工具类
 * 防止ZIP炸弹、路径遍历等攻击
 */
object SecureFileValidator {
    
    // 安全限制常量
    // 上限须容得下附件：单个附件本机上限 50MB，一个 ZIP 可能携带多个
    private const val MAX_ZIP_SIZE = 200 * 1024 * 1024L // 200MB
    private const val MAX_UNCOMPRESSED_SIZE = 400 * 1024 * 1024L // 400MB
    private const val MAX_FILE_COUNT = 1000
    private const val MAX_COMPRESSION_RATIO = 100 // 压缩比不能超过100:1
    private const val MAX_FILENAME_LENGTH = 255
    private const val MAX_PATH_DEPTH = 10

    // 允许的文件扩展名（附件除外，见 ATTACHMENT_PREFIX）
    private val ALLOWED_EXTENSIONS = setOf(
        "json", "txt", "jpg", "jpeg", "png", "webp", "mp3", "wav", "m4a", "aac"
    )

    /**
     * 附件在 ZIP 内的固定前缀（media/file_<uuid><原扩展名>，跨平台契约）。
     *
     * 附件按设计可以是任意格式（csv/pdf/xlsx/zip…），因此豁免扩展名白名单——
     * 白名单对附件没有意义。其余检查（路径遍历、危险字符、长度、层级）一律照旧；
     * 本项目从不解析、不执行附件内容，只在用户明确点击时以 content:// 交给系统程序。
     */
    private const val ATTACHMENT_PREFIX = "file_"

    /**
     * 验证ZIP文件安全性
     */
    fun validateZipFile(zipFile: File): ValidationResult {
        try {
            // 检查文件大小
            if (zipFile.length() > MAX_ZIP_SIZE) {
                SecurityLog.w("SecureFileValidator", "ZIP file too large: ${zipFile.length()} bytes")
                return ValidationResult.Invalid("ZIP文件过大，超过50MB限制")
            }
            
            val zip = ZipFile(zipFile)
            if (!zip.isValidZipFile) {
                SecurityLog.w("SecureFileValidator", "Invalid ZIP file format")
                return ValidationResult.Invalid("不是有效的ZIP文件")
            }
            
            val headers = zip.fileHeaders
            if (headers.size > MAX_FILE_COUNT) {
                SecurityLog.w("SecureFileValidator", "Too many files in ZIP: ${headers.size}")
                return ValidationResult.Invalid("ZIP文件包含文件数量过多")
            }
            
            var totalUncompressedSize = 0L
            val fileNames = mutableSetOf<String>()
            
            for (header in headers) {
                val fileName = header.fileName
                val uncompressedSize = header.uncompressedSize
                val compressedSize = header.compressedSize
                
                // 检查文件名安全性
                val nameValidation = validateFileName(fileName)
                if (nameValidation !is ValidationResult.Valid) {
                    return nameValidation
                }
                
                // 检查重复文件名
                val normalizedName = fileName.lowercase().replace("\\", "/")
                if (normalizedName in fileNames) {
                    SecurityLog.w("SecureFileValidator", "Duplicate file name: $fileName")
                    return ValidationResult.Invalid("ZIP文件包含重复的文件名")
                }
                fileNames.add(normalizedName)
                
                // 检查压缩比防ZIP炸弹。
                // 附件豁免：csv/txt/log 这类文本附件天然就能压到 100:1 以上，按压缩比拦会误杀正常附件。
                // 真正兜底的是下面的解压总大小上限（以及文件数上限），它与压缩比无关，仍然挡得住 ZIP 炸弹。
                if (compressedSize > 0 && !isAttachmentEntry(fileName)) {
                    val compressionRatio = uncompressedSize.toDouble() / compressedSize.toDouble()
                    if (compressionRatio > MAX_COMPRESSION_RATIO) {
                        SecurityLog.w("SecureFileValidator", "Suspicious compression ratio: $compressionRatio for $fileName")
                        return ValidationResult.Invalid("检测到可疑的压缩文件，可能是ZIP炸弹")
                    }
                }
                
                // 累计解压后大小
                totalUncompressedSize += uncompressedSize
                if (totalUncompressedSize > MAX_UNCOMPRESSED_SIZE) {
                    SecurityLog.w("SecureFileValidator", "Total uncompressed size too large: $totalUncompressedSize")
                    return ValidationResult.Invalid("解压后文件大小超过100MB限制")
                }
            }
            
            SecurityLog.d("SecureFileValidator", "ZIP file validation passed")
            return ValidationResult.Valid
            
        } catch (e: Exception) {
            SecurityLog.e("SecureFileValidator", "Error validating ZIP file", e)
            return ValidationResult.Invalid("验证ZIP文件时发生错误")
        }
    }
    
    /**
     * 验证文件名安全性，防止路径遍历攻击
     */
    fun validateFileName(fileName: String): ValidationResult {
        if (fileName.length > MAX_FILENAME_LENGTH) {
            SecurityLog.w("SecureFileValidator", "File name too long: $fileName")
            return ValidationResult.Invalid("文件名过长")
        }
        
        // 检查路径遍历攻击
        if (fileName.contains("..") || 
            fileName.contains("\\..\\") || 
            fileName.contains("/../") ||
            fileName.startsWith("/") ||
            fileName.startsWith("\\")) {
            SecurityLog.w("SecureFileValidator", "Path traversal attempt detected: $fileName")
            return ValidationResult.Invalid("检测到路径遍历攻击")
        }
        
        // 检查路径深度
        val pathDepth = fileName.count { it == '/' || it == '\\' }
        if (pathDepth > MAX_PATH_DEPTH) {
            SecurityLog.w("SecureFileValidator", "Path depth too deep: $pathDepth for $fileName")
            return ValidationResult.Invalid("文件路径层级过深")
        }
        
        // 检查文件扩展名。附件（file_ 前缀）按设计可为任意格式，豁免白名单
        if (!isAttachmentEntry(fileName)) {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            if (extension.isNotEmpty() && extension !in ALLOWED_EXTENSIONS) {
                SecurityLog.w("SecureFileValidator", "Unsupported file extension: $extension")
                return ValidationResult.Invalid("不支持的文件类型: .$extension")
            }
        }

        // 检查危险字符
        val dangerousChars = charArrayOf('<', '>', ':', '"', '|', '?', '*', '\u0000')
        if (fileName.indexOfAny(dangerousChars) != -1) {
            SecurityLog.w("SecureFileValidator", "Dangerous characters in filename: $fileName")
            return ValidationResult.Invalid("文件名包含非法字符")
        }
        
        return ValidationResult.Valid
    }
    
    /** ZIP 条目是否是附件（media/file_<uuid><ext>）。仅看基名前缀，路径安全性另有检查把关。 */
    private fun isAttachmentEntry(fileName: String): Boolean =
        fileName.substringAfterLast('/')
            .substringAfterLast('\\')
            .startsWith(ATTACHMENT_PREFIX)

    /**
     * 创建安全的解压路径
     */
    fun createSecureExtractPath(baseDir: File, fileName: String): File {
        // 清理文件名
        val cleanFileName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        
        // 使用绝对路径解析防止路径遍历
        val basePath = baseDir.absolutePath
        val targetFile = File(baseDir, cleanFileName)
        val targetPath = targetFile.absolutePath
        
        // 确保目标路径在基础目录内
        if (!targetPath.startsWith(basePath)) {
            throw SecurityException("Path traversal detected: $fileName")
        }
        
        return targetFile
    }
    
    /**
     * 验证文件大小
     */
    fun validateFileSize(file: File, maxSize: Long = 10 * 1024 * 1024): ValidationResult {
        return if (file.length() > maxSize) {
            SecurityLog.w("SecureFileValidator", "File too large: ${file.length()} bytes")
            ValidationResult.Invalid("文件过大")
        } else {
            ValidationResult.Valid
        }
    }
    
    sealed class ValidationResult {
        object Valid : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }
}