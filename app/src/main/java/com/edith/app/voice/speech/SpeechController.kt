package com.edith.app.voice.speech

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Speaks one message aloud. The chat's Play/Stop buttons use it (C07 §5); C13 provides the real voice. */
interface SpeechController {
    /** The message being spoken, or null. Drives the Play button highlight and enables Stop. */
    val speakingMessageId: StateFlow<Long?>
    fun play(messageId: Long, text: String)
    fun stop()
}

/** Until C13: nothing is spoken, so nothing is ever "speaking". */
object SilentSpeechController : SpeechController {
    private val none = MutableStateFlow<Long?>(null)
    override val speakingMessageId: StateFlow<Long?> = none.asStateFlow()
    override fun play(messageId: Long, text: String) {}
    override fun stop() {}
}
