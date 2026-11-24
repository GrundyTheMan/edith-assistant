package com.edith.app.voice.speech

import kotlinx.coroutines.flow.StateFlow

/** The phone's text-to-speech, behind an interface so [SpeechOutput] can be tested with a fake. */
interface TtsEngine {
    /** False when the phone has no usable voice; [problem] says why. */
    val available: StateFlow<Boolean>
    val problem: StateFlow<String?>
    fun setListener(listener: Listener)
    /** Queues [text] after anything already queued. False if it couldn't be queued (no [Listener] call follows). */
    fun speak(text: String, utteranceId: String, rate: Float): Boolean
    /** Drops everything queued and playing. */
    fun stop()
    /** Nothing more to say for now: gives up audio focus. */
    fun idle()

    /** Called on any thread. */
    interface Listener {
        fun onStart(utteranceId: String)
        fun onDone(utteranceId: String)
        fun onError(utteranceId: String)
        /** Another app took the audio (a call, a navigation prompt that doesn't duck): stop speaking. */
        fun onInterrupted()
    }
}
