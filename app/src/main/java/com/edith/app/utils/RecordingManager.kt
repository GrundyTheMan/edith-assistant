package com.edith.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Records the microphone to a 16 kHz mono WAV file.
 * WAV rather than a compressed format because Ollama's audio input only accepts WAV or MP3,
 * and Android has no built-in MP3 encoder.
 */
class RecordingManager(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRecording = false
    private var currentOutputFile: File? = null

    // Callers must hold RECORD_AUDIO; ChatScreen requests it before calling this
    @SuppressLint("MissingPermission")
    fun startRecording() {
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_ENCODING),
            SAMPLE_RATE // half a second of 16-bit audio
        )
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_ENCODING, bufferSize)
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("The microphone isn't available")
        }

        val file = getTempFile()
        audioRecord = recorder
        isRecording = true
        recorder.startRecording()

        recordingThread = thread(name = "EdithRecorder") {
            RandomAccessFile(file, "rw").use { out ->
                out.setLength(0)
                out.write(ByteArray(WAV_HEADER_SIZE)) // filled in once the length is known
                val buffer = ByteArray(bufferSize)
                var dataSize = 0L
                while (isRecording) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        out.write(buffer, 0, read)
                        dataSize += read
                    }
                }
                out.seek(0)
                out.write(wavHeader(dataSize))
            }
        }
    }

    fun stopRecording(): File? {
        val recorder = audioRecord ?: return null
        isRecording = false
        recorder.stop()
        recordingThread?.join()
        recorder.release()
        audioRecord = null
        recordingThread = null
        return currentOutputFile
    }

    private fun getTempFile(): File {
        val time = System.currentTimeMillis()
        val dir = File(context.cacheDir, "recordings").apply { mkdirs() }
        val file = File(dir, "edith_recording_$time.wav")
        currentOutputFile = file
        return file
    }

    private fun wavHeader(dataSize: Long): ByteArray {
        val byteRate = SAMPLE_RATE * BYTES_PER_SAMPLE
        return ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt((36 + dataSize).toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)                       // PCM format chunk size
            putShort(1)                      // PCM
            putShort(1)                      // mono
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort(BYTES_PER_SAMPLE.toShort())
            putShort(16)                     // bits per sample
            put("data".toByteArray())
            putInt(dataSize.toInt())
        }.array()
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2
        private const val WAV_HEADER_SIZE = 44

        /** Length of a recording made by this class, in seconds. */
        fun durationSeconds(file: File): Int =
            ((file.length() - WAV_HEADER_SIZE).coerceAtLeast(0) / (SAMPLE_RATE * BYTES_PER_SAMPLE)).toInt()
    }
}
