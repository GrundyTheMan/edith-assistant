package com.edith.app.voice.handsfree

/**
 * No wake word (K4.11): where there is no wake-word engine (the iPhone has none yet), the owner taps Talk instead, which calls `HandsFree.wakeWordHeard()`.
 * This listener never hears anything and never touches the microphone, so it "starts" at once, leaving the microphone to the speech recognizer.
 */
object TapWakeListener : WakeListener {
    override fun start(threshold: Float, onWake: () -> Unit, onFailed: () -> Unit): Boolean = true
    override fun stop() = Unit
}
