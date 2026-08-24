package com.example.xnote.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 权限工具类。
 *
 * 存储/媒体权限在 Android 13(API 33) 发生了断层：READ_EXTERNAL_STORAGE 从这一版起
 * 对 targetSdk>=33 的应用完全失效——申请时系统不弹窗、直接回 DENIED，
 * 权限列表里也不会出现。必须改申请按类型细分的 READ_MEDIA_IMAGES / READ_MEDIA_AUDIO。
 * 下面所有"存储"相关方法都按版本分流，调用方无需自己判断。
 */
object PermissionUtils {

    const val REQUEST_AUDIO_PERMISSION = 1001
    const val REQUEST_STORAGE_PERMISSION = 1002
    const val REQUEST_CAMERA_PERMISSION = 1003

    /** API 33 起新增的分类媒体权限；用字面量以便在 compileSdk 33 以下也能编译 */
    private const val PERMISSION_READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    private const val PERMISSION_READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO"

    /** API 34 起的"仅选中的照片"部分授权 */
    private const val PERMISSION_READ_MEDIA_VISUAL_USER_SELECTED =
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    private const val SDK_TIRAMISU = 33
    private const val SDK_UPSIDE_DOWN_CAKE = 34

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * 检查录音权限
     */
    fun hasAudioPermission(context: Context): Boolean =
        granted(context, Manifest.permission.RECORD_AUDIO)

    /**
     * 请求录音权限
     */
    fun requestAudioPermission(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_AUDIO_PERMISSION
        )
    }

    /**
     * 读取本机图片需要的权限。Android 13 起是 READ_MEDIA_IMAGES；
     * Android 14 起用户可能只给了"选中的照片"，那也算有权访问。
     */
    fun imagePermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= SDK_UPSIDE_DOWN_CAKE ->
            arrayOf(PERMISSION_READ_MEDIA_IMAGES, PERMISSION_READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= SDK_TIRAMISU ->
            arrayOf(PERMISSION_READ_MEDIA_IMAGES)
        else ->
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /**
     * 读取本机音频需要的权限。Android 13 起是 READ_MEDIA_AUDIO。
     */
    fun audioMediaPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= SDK_TIRAMISU) {
            arrayOf(PERMISSION_READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun hasImagePermission(context: Context): Boolean =
        imagePermissions().any { granted(context, it) }

    fun hasAudioMediaPermission(context: Context): Boolean =
        audioMediaPermissions().any { granted(context, it) }

    /**
     * 检查存储权限。Android 13 以下看 READ_EXTERNAL_STORAGE，
     * 之后看图片/音频两类媒体权限是否至少有一项到手。
     */
    fun hasStoragePermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= SDK_TIRAMISU) {
            hasImagePermission(context) || hasAudioMediaPermission(context)
        } else {
            granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    /**
     * 请求存储权限（按运行版本自动选择正确的权限名）
     */
    fun requestStoragePermission(activity: Activity) {
        val permissions = if (Build.VERSION.SDK_INT >= SDK_TIRAMISU) {
            (imagePermissions() + audioMediaPermissions()).distinct().toTypedArray()
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
        ActivityCompat.requestPermissions(activity, permissions, REQUEST_STORAGE_PERMISSION)
    }

    /**
     * 检查相机权限
     */
    fun hasCameraPermission(context: Context): Boolean =
        granted(context, Manifest.permission.CAMERA)

    /**
     * 请求相机权限
     */
    fun requestCameraPermission(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.CAMERA),
            REQUEST_CAMERA_PERMISSION
        )
    }

    /**
     * 检查所有需要的权限
     */
    fun hasAllPermissions(context: Context): Boolean =
        hasAudioPermission(context) && hasStoragePermission(context) && hasCameraPermission(context)

    /**
     * 请求所有权限
     */
    fun requestAllPermissions(activity: Activity) {
        val permissions = mutableListOf<String>()

        if (!hasAudioPermission(activity)) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (!hasStoragePermission(activity)) {
            if (Build.VERSION.SDK_INT >= SDK_TIRAMISU) {
                permissions.addAll(imagePermissions())
                permissions.addAll(audioMediaPermissions())
            } else {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (!hasCameraPermission(activity)) {
            permissions.add(Manifest.permission.CAMERA)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                permissions.distinct().toTypedArray(),
                REQUEST_STORAGE_PERMISSION
            )
        }
    }
}
