package com.edith.app.voice.handsfree

/** Scores 80 ms of 16 kHz audio at a time for the wake word: [OpenWakeWord] on the phone, a fake in tests. */
interface WakeWordDetector : AutoCloseable {
    /** [chunk] is [CHUNK_SAMPLES] of 16-bit PCM. Returns 0..1; at or above the threshold means the wake word was said. */
    fun score(chunk: ShortArray): Float
    /** Forgets the audio heard so far (after a detection, so one "Hey Edith" doesn't fire twice). */
    fun reset()

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 80 ms, the step openWakeWord works in. */
        const val CHUNK_SAMPLES = 1280

        /** Sensitivity 0..1 (Settings) to the score threshold. 0.5 gives openWakeWord's default threshold of 0.5. */
        fun threshold(sensitivity: Float): Float = (0.9f - 0.8f * sensitivity.coerceIn(0f, 1f))
    }
}
