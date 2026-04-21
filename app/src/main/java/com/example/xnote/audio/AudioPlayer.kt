package com.example.xnote.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.example.xnote.security.MediaCryptor
import com.example.xnote.utils.SecurityLog
import java.io.File
import java.io.IOException

/**
 * 音频播放器。
 * MediaPlayer 必须读真实文件，因此把加密音频解密到 cacheDir 临时文件后再播放，
 * 释放时清理该临时文件。
 *
 * 调用方需要传入 Context，以便定位 cache 目录。为兼容旧调用，
 * 仍提供旧的 [prepare] 重载，但会缺少解密能力。
 */
class AudioPlayer(private val context: Context? = null) {

    private var mediaPlayer: MediaPlayer? = null
    private var _isPlaying = false
    private var isPrepared = false
    private var currentLogicalPath: String? = null
    private var currentDecryptedTemp: File? = null

    private var onCompletionListener: (() -> Unit)? = null
    private var onProgressListener: ((current: Int, total: Int) -> Unit)? = null

    fun setOnCompletionListener(listener: () -> Unit) { onCompletionListener = listener }
    fun setOnProgressListener(listener: (current: Int, total: Int) -> Unit) { onProgressListener = listener }

    fun prepare(filePath: String): Boolean {
        return try {
            if (currentLogicalPath == filePath && isPrepared) return true

            release()

            val srcFile = File(filePath)
            val playFile = if (context != null && MediaCryptor.isEncryptedFile(srcFile)) {
                MediaCryptor.decryptToCache(context, srcFile, ".m4a").also {
                    currentDecryptedTemp = it
                }
            } else {
                srcFile
            }

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(playFile.absolutePath)
                prepareAsync()
                setOnPreparedListener { isPrepared = true }
                setOnCompletionListener {
                    _isPlaying = false
                    onCompletionListener?.invoke()
                }
                setOnErrorListener { _, _, _ ->
                    isPrepared = false
                    _isPlaying = false
                    false
                }
            }

            currentLogicalPath = filePath
            true
        } catch (e: IOException) {
            SecurityLog.e("AudioPlayer", "prepare failed", e)
            false
        }
    }

    fun play(): Boolean {
        return try {
            if (isPrepared && mediaPlayer != null) {
                mediaPlayer?.start()
                _isPlaying = true
                startProgressTracking()
                true
            } else false
        } catch (e: IllegalStateException) {
            SecurityLog.e("AudioPlayer", "play failed", e); false
        }
    }

    fun pause(): Boolean {
        return try {
            if (_isPlaying && mediaPlayer != null) {
                mediaPlayer?.pause()
                _isPlaying = false
                true
            } else false
        } catch (e: IllegalStateException) { false }
    }

    fun stop(): Boolean {
        return try {
            if (mediaPlayer != null) {
                if (_isPlaying) mediaPlayer?.stop()
                _isPlaying = false
                isPrepared = false
                true
            } else false
        } catch (e: IllegalStateException) { false }
    }

    fun seekTo(position: Int): Boolean {
        return try {
            if (isPrepared && mediaPlayer != null) {
                mediaPlayer?.seekTo(position); true
            } else false
        } catch (e: IllegalStateException) { false }
    }

    fun getCurrentPosition(): Int = try {
        if (isPrepared && mediaPlayer != null) mediaPlayer?.currentPosition ?: 0 else 0
    } catch (e: IllegalStateException) { 0 }

    fun getDuration(): Int = try {
        if (isPrepared && mediaPlayer != null) mediaPlayer?.duration ?: 0 else 0
    } catch (e: IllegalStateException) { 0 }

    fun isPlaying(): Boolean = _isPlaying
    fun isPrepared(): Boolean = isPrepared

    fun release() {
        try { mediaPlayer?.release() } catch (e: Exception) { SecurityLog.e("AudioPlayer", "release", e) }
        mediaPlayer = null
        _isPlaying = false
        isPrepared = false
        currentLogicalPath = null
        MediaCryptor.releaseDecryptedTemp(currentDecryptedTemp)
        currentDecryptedTemp = null
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
                    handler.postDelayed(this, 100)
                }
            }
        })
    }
}
