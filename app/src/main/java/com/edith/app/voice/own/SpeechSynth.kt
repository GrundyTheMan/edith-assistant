package com.edith.app.voice.own

import android.os.Debug
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/** The neural voice behind [SherpaTts]. Hidden behind an interface so JVM tests never load the native library. */
interface SpeechSynth {
    val sampleRate: Int

    /**
     * Turns [text] into audio at [speed] (1.0 normal, larger is faster), calling [onSamples] as it is produced; return
     * false from it to stop. Blocking.
     */
    fun generate(text: String, speed: Float, onSamples: (FloatArray) -> Boolean)

    fun close()
}

/** The real one: sherpa-onnx's `OfflineTts` running a Piper (VITS) voice from [OwnVoice]'s files. */
class SherpaSynth private constructor(private val tts: OfflineTts, private val speakerId: Int) : SpeechSynth {
    override val sampleRate: Int = tts.sampleRate()

    override fun generate(text: String, speed: Float, onSamples: (FloatArray) -> Boolean) {
        tts.generateWithCallback(text, speakerId, speed, SherpaCallback(onSamples))
    }

    override fun close() = tts.release()

    companion object {
        /** Two threads for ONNX (C36 §1). Throws if the files are missing or broken. */
        fun load(dir: File, speakerId: Int, threads: Int = 2): SherpaSynth {
            val startedAt = System.nanoTime()
            val nativeBefore = Debug.getNativeHeapAllocatedSize()
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = File(dir, OwnVoice.MODEL).path,
                        tokens = File(dir, OwnVoice.TOKENS).path,
                        dataDir = File(dir, OwnVoice.ESPEAK_DATA).path,
                    ),
                    numThreads = threads,
                    debug = false,
                ),
            )
            val synth = SherpaSynth(OfflineTts(null, config), speakerId)
            // Numbers only, never text (C36 trap 4).
            Log.i(
                LogTag.Voice,
                "Own voice loaded in ${(System.nanoTime() - startedAt) / 1_000_000} ms, " +
                    "native memory +${(Debug.getNativeHeapAllocatedSize() - nativeBefore) shr 20} MB, ${synth.sampleRate} Hz",
            )
            return synth
        }
    }
}
