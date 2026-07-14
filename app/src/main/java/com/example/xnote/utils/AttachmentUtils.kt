package com.example.xnote.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.example.xnote.data.NoteBlock
import com.example.xnote.security.MediaCryptor
import java.io.File
import java.util.UUID

/**
 * 任意格式附件的本地处理。
 *
 * 本项目不解析附件内容，只做四件事：挂入（加密落盘）、显示、交给系统程序打开、另存回本机。
 *
 * 落盘：与图片/音频同路径——[MediaCryptor] 的 AES-GCM（Keystore 主密钥），绝不明文落盘。
 * 块实体复用 url（加密后的本地路径）与 alt（原始文件名），size 存明文字节数仅供显示。
 */
object AttachmentUtils {

    /** 本机挂入的单个附件上限。注意：从 ZIP 导入既有附件时不受此限制。 */
    const val MAX_ATTACHMENT_SIZE: Long = 50L * 1024 * 1024

    /** 私有目录下存放加密附件的子目录 */
    private const val ATTACH_DIR = "files"

    /** "打开"时解密出的明文缓存根目录，位于 app 私有 cache，下次启动时统一清理 */
    private const val OPEN_CACHE_DIR = "attach_open"

    /** 挂入结果：加密后的本地路径 + 原始文件名 + 明文字节数 */
    data class Attachment(val path: String, val name: String, val size: Long)

    /** 选文件失败的原因，交给 UI 决定提示文案 */
    sealed class PickResult {
        data class Ok(val attachment: Attachment) : PickResult()
        data class TooLarge(val size: Long) : PickResult()
        data class Failed(val message: String) : PickResult()
    }

    /**
     * 把 SAF 选中的文件加密保存到私有目录。
     * 原始文件名/大小用 DocumentFile 取，取不到时回落 ContentResolver 的 OpenableColumns。
     */
    fun saveAttachmentToPrivateStorage(context: Context, uri: Uri): PickResult {
        return try {
            val name = queryDisplayName(context, uri) ?: "attachment"
            val declaredSize = queryFileSize(context, uri)
            if (declaredSize != null && declaredSize > MAX_ATTACHMENT_SIZE) {
                return PickResult.TooLarge(declaredSize)
            }

            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return PickResult.Failed("无法读取所选文件")

            // 有些 provider 不申报大小，读完再兜底校验一次
            if (bytes.size.toLong() > MAX_ATTACHMENT_SIZE) {
                return PickResult.TooLarge(bytes.size.toLong())
            }

            val dir = File(context.filesDir, ATTACH_DIR).apply { mkdirs() }
            // 落盘名不含原始文件名（原名只存库里的 alt），避免奇怪字符污染文件系统
            val target = File(dir, "file_${UUID.randomUUID()}${extensionSuffix(name)}")
            MediaCryptor.encryptBytes(bytes, target)

            PickResult.Ok(Attachment(target.absolutePath, name, bytes.size.toLong()))
        } catch (e: Exception) {
            SecurityLog.e("AttachmentUtils", "saveAttachmentToPrivateStorage failed", e)
            PickResult.Failed("附件保存失败")
        }
    }

    /**
     * 导入用：把 ZIP 里解出的明文附件加密落盘到私有目录。不卡 [MAX_ATTACHMENT_SIZE]（宁可收下也不丢数据）。
     * 返回加密后的本地路径与明文字节数。
     */
    fun importAttachment(context: Context, plainFile: File, originalName: String): Attachment {
        val dir = File(context.filesDir, ATTACH_DIR).apply { mkdirs() }
        val target = File(dir, "file_${UUID.randomUUID()}${extensionSuffix(originalName)}")
        val bytes = plainFile.readBytes()
        MediaCryptor.encryptBytes(bytes, target)
        return Attachment(target.absolutePath, originalName, bytes.size.toLong())
    }

    /**
     * "打开"：把附件解密到 cache 下的独立子目录并保留原始文件名（同名附件互不覆盖），
     * 返回可交给系统程序的 content:// Uri。
     */
    fun decryptToOpenCache(context: Context, block: NoteBlock): Uri? {
        val path = block.url ?: return null
        val src = File(path)
        if (!src.exists()) return null

        // 每次打开一个独立子目录：保留原名的同时避免同名覆盖
        val dir = File(File(context.cacheDir, OPEN_CACHE_DIR), UUID.randomUUID().toString())
        dir.mkdirs()

        val safeName = sanitizeFileName(block.alt ?: src.name)
        val plain = File(dir, safeName)
        plain.writeBytes(MediaCryptor.readAll(src))

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            plain
        )
    }

    /**
     * 交给系统里已安装的程序打开附件。调用方负责 catch [ActivityNotFoundException]
     * （没有可处理的程序时友好提示，而不是崩溃）。
     */
    fun viewIntentFor(uri: Uri, fileName: String?): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeTypeOf(fileName))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /** "另存为"：让用户选目标位置。原始文件名作为默认名带过去。 */
    fun createDocumentIntent(fileName: String?): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeTypeOf(fileName)
            putExtra(Intent.EXTRA_TITLE, fileName ?: "attachment")
        }

    /**
     * "另存为"：把附件解密后写入 ACTION_CREATE_DOCUMENT 选定的目标 Uri。
     */
    fun exportAttachmentTo(context: Context, block: NoteBlock, dest: Uri): Boolean {
        return try {
            val src = File(block.url ?: return false)
            if (!src.exists()) return false
            context.contentResolver.openOutputStream(dest)?.use { out ->
                out.write(MediaCryptor.readAll(src))
                true
            } ?: false
        } catch (e: Exception) {
            SecurityLog.e("AttachmentUtils", "exportAttachmentTo failed", e)
            false
        }
    }

    /** 启动时清理上次遗留的明文附件缓存（打开附件时解密出来的） */
    fun cleanupOpenCache(context: Context) {
        try {
            File(context.cacheDir, OPEN_CACHE_DIR).deleteRecursively()
        } catch (e: Exception) {
            SecurityLog.w("AttachmentUtils", "Failed to clean attachment open cache")
        }
    }

    /** 由原始文件名猜 MIME，猜不到就交给系统让用户自己挑程序 */
    fun mimeTypeOf(fileName: String?): String {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext.isEmpty()) return "*/*"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    /** 人类可读的大小，用于块上的显示 */
    fun formatSize(bytes: Long?): String {
        val size = bytes ?: return ""
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
            size < 1024L * 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024))
            else -> String.format("%.1f GB", size / (1024.0 * 1024 * 1024))
        }
    }

    /**
     * 原始扩展名（含点号），用于 ZIP 内的 media/file_<uuid><原扩展名>；无法安全取用时返回空串。
     *
     * 只接受 ASCII 字母数字扩展名：ZIP 条目名要跨平台传递，非 ASCII 扩展名可能被另一端解成乱码，
     * 从而按 mediaFileName 找不到附件实体。原始文件名本来就完整存在 alt 里，
     * 这里丢掉一个古怪的扩展名不会丢任何信息。
     */
    fun extensionSuffix(fileName: String?): String {
        val ext = fileName?.substringAfterLast('.', "").orEmpty()
        val isSafe = ext.isNotEmpty() &&
            ext.length <= 16 &&
            ext.all { (it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') }
        return if (isSafe) ".${ext.lowercase()}" else ""
    }

    /** 写入 cache 前清掉路径分隔符等危险字符，防止原始文件名逃出目录 */
    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>| ]"), "_").trim()
        return cleaned.ifBlank { "attachment" }.take(120)
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        DocumentFile.fromSingleUri(context, uri)?.name?.let { if (it.isNotBlank()) return it }
        return context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
    }

    private fun queryFileSize(context: Context, uri: Uri): Long? {
        DocumentFile.fromSingleUri(context, uri)?.length()?.let { if (it > 0) return it }
        return context.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
    }
}
