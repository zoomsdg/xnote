package com.example.xnote.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.IOException

/**
 * 音频播放器
 */
class AudioPlayer {
    
    private var mediaPlayer: MediaPlayer? = null
    private var _isPlaying = false
    private var isPrepared = false
    private var currentFilePath: String? = null
    
    private var onCompletionListener: (() -> Unit)? = null
    private var onProgressListener: ((current: Int, total: Int) -> Unit)? = null
    
    /**
     * 设置播放完成监听器
     */
    fun setOnCompletionListener(listener: () -> Unit) {
        onCompletionListener = listener
    }
    
    /**
     * 设置播放进度监听器
     */
    fun setOnProgressListener(listener: (current: Int, total: Int) -> Unit) {
        onProgressListener = listener
    }
    
    /**
     * 准备播放文件
     */
    fun prepare(filePath: String): Boolean {
        return try {
            if (currentFilePath == filePath && isPrepared) {
                return true
            }
            
            release()
            
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                
                setDataSource(filePath)
                prepareAsync()
                
                setOnPreparedListener {
                    isPrepared = true
                }
                
                setOnCompletionListener {
                    _isPlaying = false
                    onCompletionListener?.invoke()
                }
                
                setOnErrorListener { _, what, extra ->
                    isPrepared = false
                    _isPlaying = false
                    false
                }
            }
            
            currentFilePath = filePath
            true
        } catch (e: IOException) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 开始播放
     */
    fun play(): Boolean {
        return try {
            if (isPrepared && mediaPlayer != null) {
                mediaPlayer?.start()
                _isPlaying = true
                startProgressTracking()
                true
            } else {
                false
            }
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 暂停播放
     */
    fun pause(): Boolean {
        return try {
            if (_isPlaying && mediaPlayer != null) {
                mediaPlayer?.pause()
                _isPlaying = false
                true
            } else {
                false
            }
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 停止播放
     */
    fun stop(): Boolean {
        return try {
            if (mediaPlayer != null) {
                if (_isPlaying) {
                    mediaPlayer?.stop()
                }
                _isPlaying = false
                isPrepared = false
                true
            } else {
                false
            }
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 跳转到指定位置
     */
    fun seekTo(position: Int): Boolean {
        return try {
            if (isPrepared && mediaPlayer != null) {
                mediaPlayer?.seekTo(position)
                true
            } else {
                false
            }
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 获取当前播放位置
     */
    fun getCurrentPosition(): Int {
        return try {
            if (isPrepared && mediaPlayer != null) {
                mediaPlayer?.currentPosition ?: 0
            } else {
                0
            }
        } catch (e: IllegalStateException) {
            0
        }
    }
    
    /**
     * 获取总时长
     */
    fun getDuration(): Int {
        return try {
            if (isPrepared && mediaPlayer != null) {
                mediaPlayer?.duration ?: 0
            } else {
                0
            }
        } catch (e: IllegalStateException) {
            0
        }
    }
    
    /**
     * 是否正在播放
     */
    fun isPlaying(): Boolean = _isPlaying
    
    /**
     * 是否已准备
     */
    fun isPrepared(): Boolean = isPrepared
    
    /**
     * 释放资源
     */
    fun release() {
        try {
            mediaPlayer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        mediaPlayer = null
        _isPlaying = false
        isPrepared = false
        currentFilePath = null
    }
    
    private fun startProgressTracking() {
        if (!_isPlaying) return
        
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                if (_isPlaying && isPrepared) {
                    val current = getCurrentPosition()
                    val total = getDuration()
                    
                    onProgressListener?.invoke(current, total)
                    
                    handler.postDelayed(this, 100) // 更新频率：100ms
                }
            }
        })
    }
}