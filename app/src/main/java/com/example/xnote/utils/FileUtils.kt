package com.example.xnote.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
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
     * 落盘图片的长边上限。现在手机随手一张就是四五千万像素，全尺寸解码必然爆内存。
     *
     * 采样率只能取 2 的幂，所以这个阈值的实际效果是跳变的：一张 4000px 的源图，
     * 阈值 2048 时采样率 2、存成 2000px；阈值 1600 时采样率 4、存成 1000px——
     * 像素数少 4 倍，JPEG 重编码和之后每一次显示解码都快约 4 倍。
     * 编辑区只显示 300x200 缩略图，全屏放大在 1080p 屏上最宽也就 1080px，
     * 所以按 1600 取。若嫌放大后偏软，调回 2048 即可（只影响此后新增的图片）。
     */
    private const val MAX_IMAGE_DIMENSION = 1600

    /** JPEG 重编码质量。90 以上收益很小、体积明显变大，而体积直接决定后续每次解码的耗时 */
    private const val JPEG_QUALITY = 85

    /**
     * 保存图片到应用私有目录（加密）。
     *
     * 这里不能直接 `BitmapFactory.decodeStream(全尺寸)`：
     * 新机型（Android 13+ 尤其 Android 16）默认拍 HEIC/超大分辨率，
     * 全尺寸解码要么返回 null，要么抛 OutOfMemoryError——而 OOM 是 Error 不是 Exception，
     * 原来的 `catch (e: IOException)` 连它的边都碰不到，表现就是"选了图但没进纪事"。
     * 改为：先读 header 算采样率再降采样解码，API 28+ 优先用 ImageDecoder（原生支持 HEIF/AVIF
     * 且自动应用 EXIF 方向），失败再退回 BitmapFactory 并手动纠正方向。
     */
    /** 落盘结果：加密后的路径，以及尺寸（直接取自内存里那张 bitmap，不必回读文件） */
    data class SavedImage(val path: String, val width: Int, val height: Int)

    fun saveImageToPrivateStorage(context: Context, uri: Uri): SavedImage? {
        return try {
            val t0 = System.currentTimeMillis()
            val bitmap = decodeScaledBitmap(context, uri) ?: run {
                SecurityLog.e("FileUtils", "图片解码失败(返回 null): $uri")
                return null
            }
            val width = bitmap.width
            val height = bitmap.height
            val t1 = System.currentTimeMillis()

            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
            bitmap.recycle()
            val jpeg = baos.toByteArray()
            val t2 = System.currentTimeMillis()

            val dir = File(context.filesDir, "images").apply { mkdirs() }
            val target = File(dir, "img_${UUID.randomUUID()}.jpg")
            MediaCryptor.encryptBytes(jpeg, target)
            val t3 = System.currentTimeMillis()

            SecurityLog.i(
                "Perf",
                "saveImage 解码=" + (t1 - t0) + "ms 编码=" + (t2 - t1) +
                    "ms 加密=" + (t3 - t2) + "ms 尺寸=" + width + "x" + height +
                    " 字节=" + jpeg.size
            )
            SavedImage(target.absolutePath, width, height)
        } catch (t: Throwable) {
            // 包含 OutOfMemoryError 与部分 ROM 在读 content:// 时抛的 SecurityException
            SecurityLog.e("FileUtils", "saveImageToPrivateStorage failed", t)
            null
        }
    }

    /**
     * 按 [MAX_IMAGE_DIMENSION] 降采样解码 content:// 图片，方向已按 EXIF 摆正。
     */
    private fun decodeScaledBitmap(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    // 必须要软件位图：硬件位图不能参与后续的 compress
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                    val sample = sampleSizeFor(info.size.width, info.size.height)
                    if (sample > 1) {
                        decoder.setTargetSampleSize(sample)
                    }
                }
            } catch (t: Throwable) {
                // 少数格式 ImageDecoder 不认，落到下面的 BitmapFactory 兜底
                SecurityLog.e("FileUtils", "ImageDecoder 解码失败，回退 BitmapFactory", t)
            }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        return applyExifRotation(context, uri, bitmap)
    }

    /** 长边超过上限时取 2 的幂次采样率 */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / sample > MAX_IMAGE_DIMENSION) {
            sample *= 2
        }
        return sample
    }

    /**
     * BitmapFactory 不看 EXIF，重编码成 JPEG 又会丢掉 EXIF，
     * 不纠正的话竖拍照片存进来会躺倒。
     */
    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val degrees = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                when (ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (t: Throwable) {
            0f
        }
        if (degrees == 0f) return bitmap

        return try {
            val matrix = Matrix().apply { postRotate(degrees) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } catch (t: Throwable) {
            SecurityLog.e("FileUtils", "旋转图片失败，按原方向保存", t)
            bitmap
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
        } catch (t: Throwable) {
            // SAF 的 content:// 读失败在部分 ROM 上是 SecurityException/IllegalStateException，
            // 大文件读进内存还可能 OOM，都不能漏掉
            SecurityLog.e("FileUtils", "saveAudioToPrivateStorage(uri) failed", t)
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
     * 获取图片尺寸（不解码全图，只读 header）。
     *
     * 注意：这会把整个文件解密一遍。新增图片时不要用它——
     * [saveImageToPrivateStorage] 已经在 [SavedImage] 里带回了尺寸。
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
