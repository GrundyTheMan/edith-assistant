package com.edith.app.utils

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import java.io.File
import java.io.IOException

class RecordingManager(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null

    fun startRecording() {
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setOutputFile(getTempFile().absolutePath)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            prepare()
            start()
        }
    }

    fun stopRecording(): File? {
        mediaRecorder?.apply {
            stop()
            release()
        }
        mediaRecorder = null
        return currentOutputFile
    }

    private fun getTempFile(): File {
        val time = System.currentTimeMillis()
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "edith_recording_$time.3gp")
        currentOutputFile = file
        return file
    }
}
