package com.androidprotect.helpers

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

class AudioHelper(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false

    fun startRecording(durationSeconds: Int, onComplete: (File?) -> Unit) {
        if (isRecording) {
            Log.w("AudioHelper", "Audio recording is already in progress")
            return
        }

        try {
            val cacheDir = context.cacheDir
            outputFile = File.createTempFile("audio_rec_", ".aac", cacheDir)
            
            @Suppress("DEPRECATION")
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44100)
                setAudioEncodingBitRate(64000)
                setOutputFile(outputFile!!.absolutePath)
                prepare()
                start()
            }
            
            isRecording = true
            Log.d("AudioHelper", "Started recording ambient audio to ${outputFile!!.absolutePath}")

            // Auto stop recording after duration
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                stopRecording(onComplete)
            }, durationSeconds * 1000L)

        } catch (e: Exception) {
            Log.e("AudioHelper", "Error during media recorder initialization: ${e.message}", e)
            cleanup()
            onComplete(null)
        }
    }

    private fun stopRecording(onComplete: (File?) -> Unit) {
        if (!isRecording) return
        
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            Log.d("AudioHelper", "Stopped audio recording successfully")
            onComplete(outputFile)
        } catch (e: Exception) {
            Log.e("AudioHelper", "Error stopping media recorder: ${e.message}", e)
            onComplete(null)
        } finally {
            cleanup()
        }
    }

    private fun cleanup() {
        mediaRecorder = null
        isRecording = false
    }
}
