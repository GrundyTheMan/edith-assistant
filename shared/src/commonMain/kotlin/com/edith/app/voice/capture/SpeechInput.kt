package com.edith.app.voice.capture

/** Live speech recognition: [LiveSpeech] on the phone, a fake in tests. */
interface SpeechInput {
    val isAvailable: Boolean
    fun start(listener: Listener)
    /** Stops listening; the final result still arrives. */
    fun stop()
    /** Stops without a result. */
    fun cancel()

    interface Listener {
        fun onPartial(text: String) {}
        fun onLevel(level: Float) {}
        /** The final text; "" when nothing was recognised. */
        fun onFinal(text: String)
        /** A short message for the owner. */
        fun onError(message: String)
    }
}
