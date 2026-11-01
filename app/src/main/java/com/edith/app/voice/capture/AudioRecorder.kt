package com.edith.app.voice.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import kotlin.math.log10
import kotlin.math.sqrt

/** Records the microphone into a WAV (C12 §2). One recording at a time. */
interface Recorder {
    /** 0..1, for the level meter. */
    val level: StateFlow<Float>
    /** False if the microphone couldn't be opened. */
    fun start(): Boolean
    /** Null if nothing was recording. */
    fun stop(): RecordedAudio?
    /** Stops and deletes the file. */
    fun cancel()
}

/**
 * `AudioRecord` at 16 kHz mono 16-bit, `VOICE_RECOGNITION` source (falling back to `MIC`), written on its own
 * thread to `cacheDir/recordings/<uuid>.wav`. Needs `RECORD_AUDIO`, which the chat screen asks for first.
 */
class AudioRecorder(private val dir: File) : Recorder {
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level.asStateFlow()

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var writer: WavWriter? = null
    private var file: File? = null

    @SuppressLint("MissingPermission")
    @Synchronized
    override fun start(): Boolean {
        if (running) return true
        val minBuffer = AudioRecord.getMinBufferSize(WavWriter.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        val bufferBytes = maxOf(minBuffer, WavWriter.SAMPLE_RATE / 5 * 2)   // at least 200 ms
        val rec = listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC).firstNotNullOfOrNull { source ->
            try {
                AudioRecord(source, WavWriter.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
                    .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            } catch (e: SecurityException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        } ?: return false.also { Log.w(LogTag.Voice, "Couldn't open the microphone") }
        dir.mkdirs()
        val f = File(dir, "${UUID.randomUUID()}.wav")
        val w = WavWriter(f)
        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            rec.release(); w.close(); f.delete()
            return false
        }
        record = rec; writer = w; file = f; running = true
        thread = Thread({ pump(rec, w, bufferBytes / 2) }, "edith-recorder").apply { start() }
        return true
    }

    private fun pump(rec: AudioRecord, w: WavWriter, samples: Int) {
        val buf = ShortArray(samples)
        while (running) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) continue
            w.write(buf, n)
            _level.value = levelOf(buf, n)
        }
    }

    @Synchronized
    override fun stop(): RecordedAudio? {
        if (!running) return null
        running = false
        thread?.join(1_000)
        record?.run { runCatching { stop() }; release() }
        val w = writer!!
        w.close()
        val result = RecordedAudio(file!!, w.durationMs)
        record = null; writer = null; file = null; thread = null
        _level.value = 0f
        return result
    }

    override fun cancel() {
        stop()?.file?.delete()
    }

    companion object {
        /** RMS as 0..1 on a -60..0 dBFS scale, so normal speech moves the meter. */
        fun levelOf(samples: ShortArray, count: Int): Float {
            if (count <= 0) return 0f
            var sum = 0.0
            for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
            val rms = sqrt(sum / count) / Short.MAX_VALUE
            if (rms <= 0.0) return 0f
            return ((20 * log10(rms) + 60) / 60).toFloat().coerceIn(0f, 1f)
        }

        /** Recordings left behind by a crash or a killed process: none should survive (C12 acceptance). */
        fun deleteLeftovers(dir: File) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }
}
