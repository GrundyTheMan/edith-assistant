package com.edith.app.voice.handsfree

/** Listens for the wake word: [MicWakeListener] on the phone, a fake in tests. */
interface WakeListener {
    /**
     * Starts listening. [onWake] is called (on the listener's own thread) each time the wake word is heard, until
     * [stop]. [onFailed] is called if listening stops on its own (the models wouldn't load, the microphone failed).
     * False if the microphone couldn't be opened.
     */
    fun start(threshold: Float, onWake: () -> Unit, onFailed: () -> Unit): Boolean
    /** Stops and releases the microphone. Safe to call when not listening. */
    fun stop()
}
