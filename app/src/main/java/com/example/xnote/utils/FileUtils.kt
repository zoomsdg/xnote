package com.example.xnote.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*

/**
 * 文件工具类
 */
object FileUtils {
    
    /**
     * 保存图片到应用私有目录
     */
    fun saveImageToPrivateStorage(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            
            val filename = "img_${UUID.randomUUID()}.jpg"
            val file = File(context.filesDir, "images")
            if (!file.exists()) {
                file.mkdirs()
            }
            
            val imageFile = File(file, filename)
            val outputStream = FileOutputStream(imageFile)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            outputStream.close()
            
            imageFile.absolutePath
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 保存音频到应用私有目录（从文件路径）
     */
    fun saveAudioToPrivateStorage(context: Context, sourcePath: String): String? {
        return try {
            val filename = "audio_${UUID.randomUUID()}.m4a"
            val file = File(context.filesDir, "audios")
            if (!file.exists()) {
                file.mkdirs()
            }
            
            val audioFile = File(file, filename)
            val sourceFile = File(sourcePath)
            
            sourceFile.copyTo(audioFile, true)
            audioFile.absolutePath
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 保存音频到应用私有目录（从Uri）
     */
    fun saveAudioToPrivateStorage(context: Context, uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val filename = "audio_${UUID.randomUUID()}.m4a"
            val file = File(context.filesDir, "audios")
            if (!file.exists()) {
                file.mkdirs()
            }
            
            val audioFile = File(file, filename)
            val outputStream = FileOutputStream(audioFile)
            
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()
            
            audioFile.absolutePath
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 获取音频时长（秒）
     */
    fun getAudioDuration(filePath: String): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(filePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            // MediaMetadataRetriever returns milliseconds, convert to seconds
            (duration?.toLong() ?: 0L) / 1000
        } catch (e: Exception) {
            e.printStackTrace()
            0L
        }
    }
    
    /**
     * 获取图片尺寸
     */
    fun getImageSize(filePath: String): Pair<Int, Int> {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(filePath, options)
            Pair(options.outWidth, options.outHeight)
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(0, 0)
        }
    }
    
    /**
     * 删除文件
     */
    fun deleteFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            file.delete()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 获取文件大小（字节）
     */
    fun getFileSize(filePath: String): Long {
        return try {
            val file = File(filePath)
            file.length()
        } catch (e: Exception) {
            0L
        }
    }
}