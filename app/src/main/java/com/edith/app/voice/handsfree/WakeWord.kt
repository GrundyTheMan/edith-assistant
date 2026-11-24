package com.edith.app.voice.handsfree

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.Closeable
import java.nio.FloatBuffer

/** Which wake word the app has: "Hey Edith" once the owner's model is in assets, else the "Hey Jarvis" stand-in. */
data class WakeWordModel(val asset: String, val phrase: String, val isStandIn: Boolean) {
    companion object {
        const val DIR = "wakeword"
        private val HEY_EDITH = WakeWordModel("$DIR/hey_edith.onnx", "Hey Edith", isStandIn = false)
        private val HEY_JARVIS = WakeWordModel("$DIR/hey_jarvis_v0.1.onnx", "Hey Jarvis", isStandIn = true)

        fun find(context: Context): WakeWordModel =
            if (context.assets.list(DIR).orEmpty().contains("hey_edith.onnx")) HEY_EDITH else HEY_JARVIS
    }
}

/**
 * openWakeWord (decision D4) with ONNX Runtime: three models run on every 80 ms of audio.
 * 1. `melspectrogram.onnx`: the new audio (plus 480 samples of overlap) → 8 frames of 32 mel bins.
 * 2. `embedding_model.onnx`: the latest 76 mel frames → one 96-number embedding.
 * 3. The wake-word model: the latest 16 embeddings (~1.3 s) → a score.
 *
 * The numbers are openWakeWord's own (its `utils.AudioFeatures`). Raw PCM values go in as floats, not scaled, and the
 * mel output is transformed with x/10 + 2, as openWakeWord does. Not thread-safe: one thread feeds it.
 */
class OpenWakeWord(context: Context, model: WakeWordModel) : WakeWordDetector {
    private val env = OrtEnvironment.getEnvironment()
    private val melspec = session(context, "${WakeWordModel.DIR}/melspectrogram.onnx")
    private val embedding = session(context, "${WakeWordModel.DIR}/embedding_model.onnx")
    private val wake = session(context, model.asset)

    /** The end of the previous chunk, which the mel model needs for overlap. */
    private val overlap = ShortArray(OVERLAP_SAMPLES)
    private val mel = ArrayDeque<FloatArray>()
    private val features = ArrayDeque<FloatArray>()

    init {
        reset()
    }

    private fun session(context: Context, asset: String): OrtSession =
        env.createSession(context.assets.open(asset).use { it.readBytes() }, sessionOptions())

    /**
     * One thread per model and no spin-waiting. ONNX Runtime's default thread pool spins between runs, which kept a
     * quarter of a core busy all the time on the emulator just listening; the models are small enough for one thread.
     */
    private fun sessionOptions() = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
        addConfigEntry("session.intra_op.allow_spinning", "0")
        addConfigEntry("session.inter_op.allow_spinning", "0")
    }

    override fun reset() {
        overlap.fill(0)
        mel.clear()
        repeat(EMBEDDING_FRAMES) { mel.addLast(FloatArray(MEL_BINS) { 1f }) }   // openWakeWord starts with ones
        features.clear()
    }

    override fun score(chunk: ShortArray): Float {
        require(chunk.size == WakeWordDetector.CHUNK_SAMPLES) { "Expected ${WakeWordDetector.CHUNK_SAMPLES} samples" }
        // 1. Mel frames for this chunk.
        val audio = FloatArray(OVERLAP_SAMPLES + chunk.size)
        for (i in overlap.indices) audio[i] = overlap[i].toFloat()
        for (i in chunk.indices) audio[OVERLAP_SAMPLES + i] = chunk[i].toFloat()
        chunk.copyInto(overlap, 0, chunk.size - OVERLAP_SAMPLES)
        val melOut = run(melspec, audio, longArrayOf(1, audio.size.toLong()))
        for (f in 0 until melOut.size / MEL_BINS) {
            mel.addLast(FloatArray(MEL_BINS) { melOut[f * MEL_BINS + it] / 10f + 2f })
        }
        while (mel.size > EMBEDDING_FRAMES) mel.removeFirst()

        // 2. One embedding from the latest 76 frames.
        val window = FloatArray(EMBEDDING_FRAMES * MEL_BINS)
        mel.forEachIndexed { f, frame -> frame.copyInto(window, f * MEL_BINS) }
        features.addLast(run(embedding, window, longArrayOf(1, EMBEDDING_FRAMES.toLong(), MEL_BINS.toLong(), 1)))
        while (features.size > FEATURE_FRAMES) features.removeFirst()
        if (features.size < FEATURE_FRAMES) return 0f   // still filling (~1.3 s after starting)

        // 3. The wake-word score from the latest 16 embeddings.
        val input = FloatArray(FEATURE_FRAMES * EMBEDDING_SIZE)
        features.forEachIndexed { i, e -> e.copyInto(input, i * EMBEDDING_SIZE) }
        return run(wake, input, longArrayOf(1, FEATURE_FRAMES.toLong(), EMBEDDING_SIZE.toLong())).first()
    }

    private fun run(session: OrtSession, data: FloatArray, shape: LongArray): FloatArray =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                FloatArray(out.remaining()).also { out.get(it) }
            }
        }

    override fun close() {
        melspec.close(); embedding.close(); wake.close()
    }

    companion object {
        const val MEL_BINS = 32
        /** Mel frames per embedding window. */
        const val EMBEDDING_FRAMES = 76
        const val EMBEDDING_SIZE = 96
        /** Embeddings the wake-word model looks at (16 × 80 ms). */
        const val FEATURE_FRAMES = 16
        /** 3 × 160-sample hops of overlap, as openWakeWord feeds its mel model. */
        const val OVERLAP_SAMPLES = 480

        /** Sensitivity 0..1 (Settings) → score threshold. 0.5 → openWakeWord's default threshold of 0.5. */
        fun threshold(sensitivity: Float): Float = WakeWordDetector.threshold(sensitivity)
    }
}
