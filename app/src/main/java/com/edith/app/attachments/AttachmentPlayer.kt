package com.edith.app.attachments

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Plays an audio attachment from a chat bubble (C15 §4), one at a time. Main thread. */
class AttachmentPlayer(private val stopSpeaking: () -> Unit) {
    private var player: MediaPlayer? = null
    private val _playing = MutableStateFlow<Long?>(null)
    /** The attachment being played, or null. */
    val playing: StateFlow<Long?> = _playing.asStateFlow()

    fun play(id: Long, file: File) {
        stop()
        stopSpeaking()   // Edith's voice and the recording shouldn't talk over each other
        val p = MediaPlayer()
        player = p
        _playing.value = id
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(file.path)
            p.setOnPreparedListener { it.start() }
            p.setOnCompletionListener { if (player === it) stop() }
            p.setOnErrorListener { mp, what, extra ->
                Log.w(LogTag.Chat, "Couldn't play attachment $id ($what/$extra)")
                if (player === mp) stop()
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            Log.w(LogTag.Chat, "Couldn't play attachment $id", e)
            stop()
        }
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        _playing.value = null
    }
}
