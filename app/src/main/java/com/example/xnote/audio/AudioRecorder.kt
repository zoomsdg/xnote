package com.example.xnote.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.example.xnote.security.MediaCryptor
import com.example.xnote.utils.SecurityLog
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * 音频录制器。
 * MediaRecorder 必须写到一个明文 File，因此先录制到 cacheDir 临时文件，
 * 停止时再用 [MediaCryptor] 加密落到 filesDir/audios/，并删除临时明文。
 */
class AudioRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var plaintextTempFile: File? = null
    private var isRecording = false
    private var startTime: Long = 0

    fun startRecording(): String? {
        return try {
            val cacheDir = File(context.cacheDir, "rec_tmp").apply { mkdirs() }
            val tmp = File(cacheDir, "rec_${UUID.randomUUID()}.m4a")
            plaintextTempFile = tmp

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(tmp.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            startTime = System.currentTimeMillis()
            tmp.absolutePath
        } catch (e: IOException) {
            SecurityLog.e("AudioRecorder", "start failed", e)
            cleanup(deleteTemp = true)
            null
        }
    }

    /**
     * 返回值：(加密后落盘的最终路径, 秒)。失败返回 (null, 0)。
     */
    fun stopRecording(): Pair<String?, Long> {
        return try {
            if (isRecording && mediaRecorder != null) {
                mediaRecorder?.stop()
                mediaRecorder?.reset()

                val durationSec = (System.currentTimeMillis() - startTime) / 1000
                val tmp = plaintextTempFile

                val encryptedPath: String? = if (tmp != null && tmp.exists() && tmp.length() > 0) {
                    val outDir = File(context.filesDir, "audios").apply { mkdirs() }
                    val out = File(outDir, "audio_${UUID.randomUUID()}.m4a")
                    try {
                        MediaCryptor.encryptBytes(tmp.readBytes(), out)
                        out.absolutePath
                    } catch (e: Exception) {
                        SecurityLog.e("AudioRecorder", "encrypt-on-stop failed", e)
                        null
                    }
                } else null

                cleanup(deleteTemp = true)
                Pair(encryptedPath, durationSec)
            } else {
                Pair(null, 0L)
            }
        } catch (e: RuntimeException) {
            SecurityLog.e("AudioRecorder", "stop failed", e)
            cleanup(deleteTemp = true)
            Pair(null, 0L)
        }
    }

    fun cancelRecording() {
        try {
            if (isRecording && mediaRecorder != null) {
                mediaRecorder?.stop()
                mediaRecorder?.reset()
            }
        } catch (e: RuntimeException) {
            SecurityLog.e("AudioRecorder", "cancel stop failed", e)
        } finally {
            cleanup(deleteTemp = true)
        }
    }

    fun getCurrentDuration(): Long =
        if (isRecording) (System.currentTimeMillis() - startTime) / 1000 else 0L

    fun isRecording(): Boolean = isRecording

    private fun cleanup(deleteTemp: Boolean) {
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        if (deleteTemp) {
            plaintextTempFile?.let {
                try { if (it.exists()) it.delete() } catch (_: Exception) {}
            }
        }
        plaintextTempFile = null
        isRecording = false
        startTime = 0
    }
}
