package com.edith.app.voice.capture

/**
 * No live speech recognition on the iPhone yet (K4.3): voice memos are recorded and transcribed by the model, which is the way Edith's own
 * model hears audio. Dictation into the text field (Apple's `SFSpeechRecognizer`, which has its own permission) is a later step, so the
 * dictation button is not shown.
 */
object NoSpeechInput : SpeechInput {
    override val isAvailable: Boolean = false
    override fun start(listener: SpeechInput.Listener) = listener.onError(SpeechInput.UNAVAILABLE)
    override fun stop() = Unit
    override fun cancel() = Unit
}
