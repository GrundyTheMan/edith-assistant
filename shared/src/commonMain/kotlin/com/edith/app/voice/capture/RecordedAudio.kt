package com.edith.app.voice.capture

/** A finished recording: the WAV file's path and its length. */
data class RecordedAudio(val path: String, val durationMs: Long)
