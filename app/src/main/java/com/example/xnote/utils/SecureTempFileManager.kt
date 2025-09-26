package com.example.xnote.utils

import android.content.Context
import com.example.xnote.utils.SecurityLog
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import kotlin.random.Random

/**
 * 安全临时文件管理工具
 * 确保临时文件的安全创建和删除
 */
object SecureTempFileManager {
    
    private val secureRandom = SecureRandom()
    private val activeTempFiles = mutableSetOf<File>()
    private val activeTempDirs = mutableSetOf<File>()
    
    /**
     * 创建安全的临时目录
     */
    fun createSecureTempDir(context: Context, prefix: String = "secure_temp"): File {
        val timestamp = System.currentTimeMillis()
        val randomSuffix = secureRandom.nextInt(100000)
        val dirName = "${prefix}_${timestamp}_${randomSuffix}"
        
        val tempDir = File(context.cacheDir, dirName)
        if (!tempDir.mkdirs()) {
            throw SecurityException("Failed to create secure temp directory")
        }
        
        // 设置目录权限（仅应用可访问）
        tempDir.setReadable(false, false)
        tempDir.setWritable(false, false)
        tempDir.setExecutable(false, false)
        
        tempDir.setReadable(true, true)
        tempDir.setWritable(true, true)
        tempDir.setExecutable(true, true)
        
        synchronized(activeTempDirs) {
            activeTempDirs.add(tempDir)
        }
        
        SecurityLog.d("SecureTempFileManager", "Created secure temp directory")
        return tempDir
    }
    
    /**
     * 创建安全的临时文件
     */
    fun createSecureTempFile(context: Context, prefix: String = "secure", suffix: String = ".tmp"): File {
        val timestamp = System.currentTimeMillis()
        val randomSuffix = secureRandom.nextInt(100000)
        val fileName = "${prefix}_${timestamp}_${randomSuffix}${suffix}"
        
        val tempFile = File(context.cacheDir, fileName)
        if (!tempFile.createNewFile()) {
            throw SecurityException("Failed to create secure temp file")
        }
        
        // 设置文件权限（仅应用可访问）
        tempFile.setReadable(false, false)
        tempFile.setWritable(false, false)
        
        tempFile.setReadable(true, true)
        tempFile.setWritable(true, true)
        
        synchronized(activeTempFiles) {
            activeTempFiles.add(tempFile)
        }
        
        SecurityLog.d("SecureTempFileManager", "Created secure temp file")
        return tempFile
    }
    
    /**
     * 安全删除文件（覆写后删除）
     */
    fun secureDelete(file: File): Boolean {
        return try {
            if (file.exists() && file.isFile) {
                // 多次覆写文件内容
                overwriteFile(file)
                SecurityLog.d("SecureTempFileManager", "Securely overwritten file content")
            }
            
            val deleted = file.delete()
            if (deleted) {
                synchronized(activeTempFiles) {
                    activeTempFiles.remove(file)
                }
                SecurityLog.d("SecureTempFileManager", "Successfully deleted file")
            } else {
                SecurityLog.w("SecureTempFileManager", "Failed to delete file")
            }
            deleted
        } catch (e: Exception) {
            SecurityLog.e("SecureTempFileManager", "Error during secure delete", e)
            false
        }
    }
    
    /**
     * 安全删除目录（递归覆写后删除）
     */
    fun secureDeleteDir(dir: File): Boolean {
        return try {
            if (dir.exists() && dir.isDirectory) {
                // 递归处理目录内容
                dir.walkBottomUp().forEach { file ->
                    if (file.isFile) {
                        overwriteFile(file)
                        file.delete()
                    } else if (file.isDirectory && file != dir) {
                        file.delete()
                    }
                }
            }
            
            val deleted = dir.delete()
            if (deleted) {
                synchronized(activeTempDirs) {
                    activeTempDirs.remove(dir)
                }
                SecurityLog.d("SecureTempFileManager", "Successfully deleted directory")
            } else {
                SecurityLog.w("SecureTempFileManager", "Failed to delete directory")
            }
            deleted
        } catch (e: Exception) {
            SecurityLog.e("SecureTempFileManager", "Error during secure directory delete", e)
            false
        }
    }
    
    /**
     * 覆写文件内容
     */
    private fun overwriteFile(file: File) {
        try {
            val fileSize = file.length()
            if (fileSize > 0) {
                // 使用随机数据覆写文件3次
                repeat(3) {
                    val randomData = ByteArray(minOf(fileSize.toInt(), 8192))
                    secureRandom.nextBytes(randomData)
                    
                    file.outputStream().use { output ->
                        var remaining = fileSize
                        while (remaining > 0) {
                            val writeSize = minOf(remaining, randomData.size.toLong()).toInt()
                            output.write(randomData, 0, writeSize)
                            remaining -= writeSize
                        }
                        output.flush()
                        output.fd.sync() // 强制写入磁盘
                    }
                }
            }
        } catch (e: Exception) {
            SecurityLog.e("SecureTempFileManager", "Error overwriting file", e)
        }
    }
    
    /**
     * 清理所有活动的临时文件
     */
    fun cleanupAll() {
        synchronized(activeTempFiles) {
            activeTempFiles.toList().forEach { file ->
                secureDelete(file)
            }
            activeTempFiles.clear()
        }
        
        synchronized(activeTempDirs) {
            activeTempDirs.toList().forEach { dir ->
                secureDeleteDir(dir)
            }
            activeTempDirs.clear()
        }
        
        SecurityLog.d("SecureTempFileManager", "Cleaned up all temporary files")
    }
    
    /**
     * 获取活动临时文件数量（用于监控）
     */
    fun getActiveTempFileCount(): Int {
        return synchronized(activeTempFiles) { activeTempFiles.size } +
               synchronized(activeTempDirs) { activeTempDirs.size }
    }
}