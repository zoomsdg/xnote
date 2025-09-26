package com.example.xnote.utils

import android.util.Log
import com.example.xnote.BuildConfig

/**
 * 安全日志工具类
 * 在生产环境中禁用敏感信息日志
 */
object SecurityLog {
    
    private const val TAG_PREFIX = "XNote"
    
    /**
     * Debug级别日志 - 仅在调试模式下输出
     */
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d("$TAG_PREFIX-$tag", message)
        }
    }
    
    /**
     * Info级别日志 - 可在生产环境输出的安全信息
     */
    fun i(tag: String, message: String) {
        Log.i("$TAG_PREFIX-$tag", message)
    }
    
    /**
     * Warning级别日志 - 警告信息
     */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w("$TAG_PREFIX-$tag", message, throwable)
        } else {
            Log.w("$TAG_PREFIX-$tag", message)
        }
    }
    
    /**
     * Error级别日志 - 错误信息（不包含敏感数据）
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val safeMessage = sanitizeMessage(message)
        if (throwable != null && BuildConfig.DEBUG) {
            // 完整堆栈信息仅在调试模式下显示
            Log.e("$TAG_PREFIX-$tag", safeMessage, throwable)
        } else {
            // 生产环境只记录安全的错误信息
            Log.e("$TAG_PREFIX-$tag", safeMessage)
        }
    }
    
    /**
     * 清理敏感信息
     */
    private fun sanitizeMessage(message: String): String {
        return if (BuildConfig.DEBUG) {
            message
        } else {
            // 在生产环境中移除可能的敏感信息
            message
                .replace(Regex("id=\\w+"), "id=***")
                .replace(Regex("'[^']*'"), "'***'")
                .replace(Regex("password|token|key", RegexOption.IGNORE_CASE), "***")
        }
    }
}