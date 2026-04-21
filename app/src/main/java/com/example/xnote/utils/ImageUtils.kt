package com.example.xnote.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.xnote.security.MediaCryptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * 图片工具类。落盘均为 [MediaCryptor] AES-GCM 加密。
 */
object ImageUtils {

    /**
     * 从 URI 加载位图（外部 URI，仍为明文，仅用于解码）
     */
    fun loadBitmapFromUri(context: Context, uri: Uri, maxWidth: Int = 1024, maxHeight: Int = 1024): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (e: Exception) {
            SecurityLog.e("ImageUtils", "loadBitmapFromUri failed", e)
            null
        }
    }

    /**
     * 保存位图到本地文件（加密）
     */
    fun saveBitmapToFile(context: Context, bitmap: Bitmap, quality: Int = 85): String? {
        return try {
            val imageDir = File(context.filesDir, "images").apply { mkdirs() }
            val file = File(imageDir, "img_${UUID.randomUUID()}.jpg")
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            MediaCryptor.encryptBytes(baos.toByteArray(), file)
            file.absolutePath
        } catch (e: IOException) {
            SecurityLog.e("ImageUtils", "saveBitmapToFile failed", e)
            null
        }
    }

    /**
     * 创建临时拍照文件。这一步必须给系统相机一个明文可写文件，
     * 由 [FileProvider] 授权后让相机把原始 JPEG 写入；上层在拿到结果后
     * 应调用 [encryptCameraTempInPlace] 转为加密格式再持久化。
     */
    fun createTempCameraFile(context: Context): Pair<File, Uri>? {
        return try {
            val imageDir = File(context.cacheDir, "camera").apply { mkdirs() }
            val file = File(imageDir, "camera_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            Pair(file, uri)
        } catch (e: Exception) {
            SecurityLog.e("ImageUtils", "createTempCameraFile failed", e)
            null
        }
    }

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
     * 获取图片尺寸（兼容明文与 XNC1 加密两种文件）
     */
    fun getImageDimensions(filePath: String): Pair<Int, Int> {
        return try {
            val opts = MediaCryptor.decodeBitmapBounds(File(filePath))
            Pair(opts.outWidth, opts.outHeight)
        } catch (e: Exception) {
            SecurityLog.e("ImageUtils", "getImageDimensions failed", e)
            Pair(0, 0)
        }
    }

    /**
     * 就地压缩图片：读入（自动解密）→ 缩放 → 写回（加密）。
     * 返回原路径表示成功；失败返回 null。
     */
    fun compressImageFile(
        filePath: String,
        maxWidth: Int = 1024,
        maxHeight: Int = 1024,
        quality: Int = 85
    ): String? {
        return try {
            val file = File(filePath)
            val raw = MediaCryptor.readAll(file)

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight)

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return null
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            bitmap.recycle()

            MediaCryptor.encryptBytes(baos.toByteArray(), file)
            filePath
        } catch (e: Exception) {
            SecurityLog.e("ImageUtils", "compressImageFile failed", e)
            null
        }
    }
}
