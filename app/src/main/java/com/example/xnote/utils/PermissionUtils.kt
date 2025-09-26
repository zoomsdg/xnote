package com.example.xnote.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 权限工具类
 */
object PermissionUtils {
    
    const val REQUEST_AUDIO_PERMISSION = 1001
    const val REQUEST_STORAGE_PERMISSION = 1002
    const val REQUEST_CAMERA_PERMISSION = 1003
    
    /**
     * 检查录音权限
     */
    fun hasAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
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
     * 检查存储权限
     */
    fun hasStoragePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * 请求存储权限
     */
    fun requestStoragePermission(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ),
            REQUEST_STORAGE_PERMISSION
        )
    }
    
    /**
     * 检查相机权限
     */
    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
    
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
    fun hasAllPermissions(context: Context): Boolean {
        return hasAudioPermission(context) && 
               hasStoragePermission(context) && 
               hasCameraPermission(context)
    }
    
    /**
     * 请求所有权限
     */
    fun requestAllPermissions(activity: Activity) {
        val permissions = mutableListOf<String>()
        
        if (!hasAudioPermission(activity)) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        
        if (!hasStoragePermission(activity)) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        
        if (!hasCameraPermission(activity)) {
            permissions.add(Manifest.permission.CAMERA)
        }
        
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                permissions.toTypedArray(),
                REQUEST_STORAGE_PERMISSION
            )
        }
    }
}