package com.example.xnote.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.io.IOException
import java.util.*

/**
 * 音频录制器
 */
class AudioRecorder(private val context: Context) {
    
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false
    private var startTime: Long = 0
    
    /**
     * 开始录音
     */
    fun startRecording(): String? {
        return try {
            // 创建输出文件
            val audioDir = File(context.filesDir, "audios")
            if (!audioDir.exists()) {
                audioDir.mkdirs()
            }
            
            val filename = "record_${UUID.randomUUID()}.m4a"
            outputFile = File(audioDir, filename)
            
            // 配置MediaRecorder
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(outputFile?.absolutePath)
                
                prepare()
                start()
            }
            
            isRecording = true
            startTime = System.currentTimeMillis()
            
            outputFile?.absolutePath
        } catch (e: IOException) {
            e.printStackTrace()
            cleanup()
            null
        }
    }
    
    /**
     * 停止录音
     */
    fun stopRecording(): Pair<String?, Long> {
        return try {
            if (isRecording && mediaRecorder != null) {
                mediaRecorder?.stop()
                mediaRecorder?.reset()
                
                val duration = (System.currentTimeMillis() - startTime) / 1000
                val filePath = outputFile?.absolutePath
                
                cleanup()
                
                Pair(filePath, duration)
            } else {
                Pair(null, 0L)
            }
        } catch (e: RuntimeException) {
            e.printStackTrace()
            cleanup()
            Pair(null, 0L)
        }
    }
    
    /**
     * 取消录音
     */
    fun cancelRecording() {
        try {
            if (isRecording && mediaRecorder != null) {
                mediaRecorder?.stop()
                mediaRecorder?.reset()
                
                // 删除录制的文件
                outputFile?.delete()
            }
        } catch (e: RuntimeException) {
            e.printStackTrace()
        } finally {
            cleanup()
        }
    }
    
    /**
     * 获取当前录音时长（秒）
     */
    fun getCurrentDuration(): Long {
        return if (isRecording) {
            (System.currentTimeMillis() - startTime) / 1000
        } else {
            0L
        }
    }
    
    /**
     * 是否正在录音
     */
    fun isRecording(): Boolean = isRecording
    
    private fun cleanup() {
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        mediaRecorder = null
        outputFile = null
        isRecording = false
        startTime = 0
    }
}