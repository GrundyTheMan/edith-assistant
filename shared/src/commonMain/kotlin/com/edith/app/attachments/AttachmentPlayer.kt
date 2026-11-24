package com.edith.app.attachments

import kotlinx.coroutines.flow.StateFlow

/**
 * Plays an audio attachment from a chat bubble (C15 §4), one at a time (K2.10: the interface; Android's is `AndroidAttachmentPlayer`, the
 * iPhone's is written in K4.3 on `AVAudioPlayer`). Called from the main thread.
 */
interface AttachmentPlayer {
    /** The id of the attachment being played, or null. */
    val playing: StateFlow<Long?>

    /** Plays the file at [path], stopping anything already playing and Edith's own voice first. */
    fun play(id: Long, path: String)

    fun stop()
}
