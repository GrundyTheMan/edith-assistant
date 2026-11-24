package com.edith.app.voice.handsfree

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag

/**
 * Reads the microphone (16 kHz mono PCM, 80 ms at a time) on its own thread and scores it with a [WakeWordDetector]
 * (C14 §1, Listening). The audio is only scored, never stored or sent (C14 §4). The detector is created on first use
 * and kept: loading the three models takes a moment.
 */
class MicWakeListener(private val newDetector: () -> WakeWordDetector) : WakeListener {
    private val lock = Any()
    private var thread: Thread? = null
    @Volatile private var running = false
    private var detector: WakeWordDetector? = null
    /** The session id of the current recording, so the service can tell when Android silences it. */
    @Volatile var audioSessionId: Int = 0; private set

    @SuppressLint("MissingPermission")   // checked by the screen before hands-free starts
    override fun start(threshold: Float, onWake: () -> Unit, onFailed: () -> Unit): Boolean = synchronized(lock) {
        if (running) return true
        val record = open() ?: return false
        running = true
        audioSessionId = record.audioSessionId
        thread = Thread({ loop(record, threshold, onWake, onFailed) }, "edith-wake-word").apply { start() }
        true
    }

    private fun loop(record: AudioRecord, threshold: Float, onWake: () -> Unit, onFailed: () -> Unit) {
        val chunk = ShortArray(WakeWordDetector.CHUNK_SAMPLES)
        var failed = false
        try {
            // Loaded here, off the main thread, the first time (three models, ~3.7 MB).
            val detector = synchronized(lock) { detector ?: newDetector().also { detector = it } }
            detector.reset()
            record.startRecording()
            while (running) {
                var filled = 0
                while (running && filled < chunk.size) {
                    val n = record.read(chunk, filled, chunk.size - filled)
                    if (n < 0) {
                        Log.w(LogTag.Voice, "Wake-word microphone read failed ($n)")
                        failed = running
                        running = false
                        break
                    }
                    filled += n
                }
                if (!running) break
                val score = detector.score(chunk)
                if (score >= threshold) {
                    Log.d(LogTag.Voice, "Wake word heard (score ${"%.2f".format(score)})")
                    detector.reset()
                    onWake()
                }
            }
        } catch (e: Exception) {
            // A model or microphone failure ends listening; it must never take the app down.
            Log.e(LogTag.Voice, "Wake-word listening failed", e)
            failed = running
            running = false
        } catch (e: LinkageError) {   // ONNX Runtime's native library couldn't load (L25: an Error, not an Exception)
            Log.e(LogTag.Voice, "Wake-word engine couldn't load", e)
            failed = running
            running = false
        } finally {
            runCatching { record.stop() }
            record.release()
        }
        if (failed) onFailed()
    }

    override fun stop() {
        val t = synchronized(lock) {
            running = false
            thread.also { thread = null }
        }
        if (t != null && t != Thread.currentThread()) t.join(JOIN_MS)
    }

    @SuppressLint("MissingPermission")
    private fun open(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(WakeWordDetector.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val size = maxOf(min, WakeWordDetector.CHUNK_SAMPLES * 2 * 4)
        for (source in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val record = try {
                AudioRecord(source, WakeWordDetector.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            } catch (e: Exception) {
                Log.w(LogTag.Voice, "Couldn't open the microphone (source $source)", e)
                continue
            }
            if (record.state == AudioRecord.STATE_INITIALIZED) return record
            record.release()
        }
        return null
    }

    companion object {
        private const val JOIN_MS = 1_000L
    }
}
