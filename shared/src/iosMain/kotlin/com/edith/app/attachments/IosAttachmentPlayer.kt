@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.edith.app.attachments

import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.darwin.NSObject

/**
 * Plays an audio attachment from a chat bubble on the iPhone (K4.3; Android's is `AndroidAttachmentPlayer`): `AVAudioPlayer`, one at a time,
 * on the `playback` audio session so it sounds with the silent switch on, as a voice memo should. [stopSpeech] silences Edith's own voice first.
 * Called from the main thread. Written without a Mac to try it on: CI compiles it, the owner's phone is the first real run.
 */
class IosAttachmentPlayer(private val stopSpeech: () -> Unit = {}) : AttachmentPlayer {
    private val _playing = MutableStateFlow<Long?>(null)
    override val playing: StateFlow<Long?> = _playing.asStateFlow()

    private var player: AVAudioPlayer? = null

    private val delegate = object : NSObject(), AVAudioPlayerDelegateProtocol {
        @ObjCSignatureOverride
        override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) = finished()

        override fun audioPlayerDecodeErrorDidOccur(player: AVAudioPlayer, error: NSError?) {
            Log.w(LogTag.Chat, "Attachment player: the file could not be decoded")
            finished()
        }
    }

    override fun play(id: Long, path: String) {
        stop()
        stopSpeech()
        val made = runCatching { AVAudioPlayer(NSURL.fileURLWithPath(path), null) }.getOrNull()
        if (made == null) {
            Log.w(LogTag.Chat, "Attachment player: couldn't open the file")
            return
        }
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, null)
        session.setActive(true, null)
        made.delegate = delegate
        if (!made.prepareToPlay() || !made.play()) {
            Log.w(LogTag.Chat, "Attachment player: couldn't start playing")
            session.setActive(false, null)
            return
        }
        player = made
        _playing.value = id
    }

    override fun stop() {
        player?.stop()
        finished()
    }

    private fun finished() {
        if (player != null) {
            player = null
            AVAudioSession.sharedInstance().setActive(false, null)
        }
        _playing.value = null
    }
}
