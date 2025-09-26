package com.example.xnote.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*

/**
 * 图片工具类
 */
object ImageUtils {
    
    /**
     * 从URI加载位图
     */
    fun loadBitmapFromUri(context: Context, uri: Uri, maxWidth: Int = 1024, maxHeight: Int = 1024): Bitmap? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()
            
            // 计算缩放比例
            val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, maxWidth, maxHeight)
            
            // 重新加载实际位图
            val actualInputStream = context.contentResolver.openInputStream(uri)
            val actualOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            
            val bitmap = BitmapFactory.decodeStream(actualInputStream, null, actualOptions)
            actualInputStream?.close()
            
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 保存位图到本地文件
     */
    fun saveBitmapToFile(context: Context, bitmap: Bitmap, quality: Int = 85): String? {
        return try {
            val imageDir = File(context.filesDir, "images")
            if (!imageDir.exists()) {
                imageDir.mkdirs()
            }
            
            val filename = "img_${UUID.randomUUID()}.jpg"
            val file = File(imageDir, filename)
            
            val outputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            outputStream.close()
            
            file.absolutePath
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 创建临时拍照文件
     */
    fun createTempCameraFile(context: Context): Pair<File, Uri>? {
        return try {
            val imageDir = File(context.cacheDir, "camera")
            if (!imageDir.exists()) {
                imageDir.mkdirs()
            }
            
            val filename = "camera_${System.currentTimeMillis()}.jpg"
            val file = File(imageDir, filename)
            
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            
            Pair(file, uri)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 计算缩放比例
     */
    private fun calculateSampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
        var sampleSize = 1
        
        if (width > maxWidth || height > maxHeight) {
            val halfWidth = width / 2
            val halfHeight = height / 2
            
            while (halfWidth / sampleSize >= maxWidth && halfHeight / sampleSize >= maxHeight) {
                sampleSize *= 2
            }
        }
        
        return sampleSize
    }
    
    /**
     * 获取图片尺寸信息
     */
    fun getImageDimensions(filePath: String): Pair<Int, Int> {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(filePath, options)
            Pair(options.outWidth, options.outHeight)
        } catch (e: Exception) {
            Pair(0, 0)
        }
    }
    
    /**
     * 压缩图片文件
     */
    fun compressImageFile(filePath: String, maxWidth: Int = 1024, maxHeight: Int = 1024, quality: Int = 85): String? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(filePath, options)
            
            val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, maxWidth, maxHeight)
            
            val actualOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            
            val bitmap = BitmapFactory.decodeFile(filePath, actualOptions)
            
            val outputStream = FileOutputStream(filePath)
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            outputStream.close()
            
            bitmap.recycle()
            filePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}