package com.edith.app.voice.capture

import kotlinx.coroutines.flow.StateFlow

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
