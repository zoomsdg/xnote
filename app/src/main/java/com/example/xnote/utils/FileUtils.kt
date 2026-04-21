package com.example.xnote.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.example.xnote.security.MediaCryptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * 文件工具类。
 * 图片/音频在私有目录中以 [MediaCryptor] 的 AES-GCM 格式保存，
 * 即便设备 root，文件落盘内容也是加密的。
 */
object FileUtils {

    /**
     * 保存图片到应用私有目录（加密）
     */
    fun saveImageToPrivateStorage(context: Context, uri: Uri): String? {
        return try {
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return null
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
            bitmap.recycle()

            val dir = File(context.filesDir, "images").apply { mkdirs() }
            val target = File(dir, "img_${UUID.randomUUID()}.jpg")
            MediaCryptor.encryptBytes(baos.toByteArray(), target)
            target.absolutePath
        } catch (e: IOException) {
            SecurityLog.e("FileUtils", "saveImageToPrivateStorage failed", e)
            null
        }
    }

    /**
     * 保存音频到应用私有目录（加密，从已有文件路径）
     */
    fun saveAudioToPrivateStorage(context: Context, sourcePath: String): String? {
        return try {
            val src = File(sourcePath)
            if (!src.exists()) return null
            val dir = File(context.filesDir, "audios").apply { mkdirs() }
            val target = File(dir, "audio_${UUID.randomUUID()}.m4a")
            MediaCryptor.encryptBytes(src.readBytes(), target)
            target.absolutePath
        } catch (e: IOException) {
            SecurityLog.e("FileUtils", "saveAudioToPrivateStorage(path) failed", e)
            null
        }
    }

    /**
     * 保存音频到应用私有目录（加密，从 Uri）
     */
    fun saveAudioToPrivateStorage(context: Context, uri: Uri): String? {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null
            val dir = File(context.filesDir, "audios").apply { mkdirs() }
            val target = File(dir, "audio_${UUID.randomUUID()}.m4a")
            MediaCryptor.encryptBytes(bytes, target)
            target.absolutePath
        } catch (e: IOException) {
            SecurityLog.e("FileUtils", "saveAudioToPrivateStorage(uri) failed", e)
            null
        }
    }

    /**
     * 获取音频时长（秒）。需要解密到临时文件后让 MediaMetadataRetriever 读取。
     */
    fun getAudioDuration(context: Context, filePath: String): Long {
        var tmp: File? = null
        return try {
            val src = File(filePath)
            val target = if (MediaCryptor.isEncryptedFile(src)) {
                MediaCryptor.decryptToCache(context, src, ".m4a").also { tmp = it }
            } else src
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(target.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            (duration?.toLong() ?: 0L) / 1000
        } catch (e: Exception) {
            SecurityLog.e("FileUtils", "getAudioDuration failed", e)
            0L
        } finally {
            MediaCryptor.releaseDecryptedTemp(tmp)
        }
    }

    /**
     * 获取图片尺寸（不解码全图，只读 header）
     */
    fun getImageSize(filePath: String): Pair<Int, Int> {
        return try {
            val src = File(filePath)
            val opts = MediaCryptor.decodeBitmapBounds(src)
            Pair(opts.outWidth, opts.outHeight)
        } catch (e: Exception) {
            SecurityLog.e("FileUtils", "getImageSize failed", e)
            Pair(0, 0)
        }
    }

    /**
     * 删除文件
     */
    fun deleteFile(filePath: String): Boolean {
        return try {
            File(filePath).delete()
        } catch (e: Exception) {
            SecurityLog.e("FileUtils", "deleteFile failed", e)
            false
        }
    }

    /**
     * 获取（密文）文件大小
     */
    fun getFileSize(filePath: String): Long {
        return try {
            File(filePath).length()
        } catch (e: Exception) {
            0L
        }
    }
}
