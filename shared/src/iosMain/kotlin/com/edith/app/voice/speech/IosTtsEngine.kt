@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.edith.app.voice.speech

import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.util.PlatformLock
import com.edith.app.core.util.synchronized
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionModeSpokenAudio
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechUtteranceDefaultSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMaximumSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMinimumSpeechRate
import platform.AVFAudio.setActive
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.darwin.NSObject

/**
 * Edith's voice on the iPhone (K4.3): Apple's `AVSpeechSynthesizer`, which has the voices the owner chose in Settings → Accessibility and needs no
 * permission. The audio session is `playback` with `spokenAudio` and ducking, so a podcast or a navigation voice lowers instead of stopping, and
 * it is let go in [idle] so the other app gets its volume back. A call, Siri or an alarm that takes the audio ends the speech ([TtsEngine.Listener.onInterrupted]).
 * Written without a Mac to try it on: the macOS jobs compile it, and the owner's phone is the first real run.
 */
class IosTtsEngine : TtsEngine {
    private val _available = MutableStateFlow(true)
    override val available: StateFlow<Boolean> = _available.asStateFlow()
    private val _problem = MutableStateFlow<String?>(null)
    override val problem: StateFlow<String?> = _problem.asStateFlow()

    private val synthesizer = AVSpeechSynthesizer()
    private val lock = PlatformLock()
    private var listener: TtsEngine.Listener? = null
    /** The utterance ids by utterance, for the delegate's callbacks. */
    private val ids = HashMap<AVSpeechUtterance, String>()

    private val delegate = object : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didStartSpeechUtterance: AVSpeechUtterance) {
            idOf(didStartSpeechUtterance, remove = false)?.let { listener?.onStart(it) }
        }

        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) {
            idOf(didFinishSpeechUtterance, remove = true)?.let { listener?.onDone(it) }
        }

        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didCancelSpeechUtterance: AVSpeechUtterance) {
            // stop() cancels what was queued; SpeechOutput has already moved on to a new generation, so nothing is reported.
            idOf(didCancelSpeechUtterance, remove = true)
        }
    }

    init {
        synthesizer.delegate = delegate
        NSNotificationCenter.defaultCenter.addObserverForName(AVAudioSessionInterruptionNotification, null, NSOperationQueue.mainQueue) { note ->
            val type = (note?.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.unsignedLongValue
            if (type == AVAudioSessionInterruptionTypeBegan) {
                Log.i(LogTag.Voice, "Speech interrupted by the system")
                listener?.onInterrupted()
            }
        }
    }

    private fun idOf(utterance: AVSpeechUtterance, remove: Boolean): String? = lock.synchronized {
        if (remove) ids.remove(utterance) else ids[utterance]
    }

    override fun setListener(listener: TtsEngine.Listener) {
        this.listener = listener
    }

    override fun speak(text: String, utteranceId: String, rate: Float): Boolean {
        if (text.isBlank()) return false
        if (!activate()) return false
        val utterance = AVSpeechUtterance.speechUtteranceWithString(text)
        // 1.0 is the owner's normal speed; Apple's scale runs from a minimum to a maximum with its own default in between.
        utterance.rate = (AVSpeechUtteranceDefaultSpeechRate * rate).coerceIn(AVSpeechUtteranceMinimumSpeechRate, AVSpeechUtteranceMaximumSpeechRate)
        utterance.voice = AVSpeechSynthesisVoice.voiceWithLanguage(AVSpeechSynthesisVoice.currentLanguageCode())
        lock.synchronized { ids[utterance] = utteranceId }
        synthesizer.speakUtterance(utterance)
        return true
    }

    override fun stop() {
        lock.synchronized { ids.clear() }
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
    }

    override fun idle() {
        AVAudioSession.sharedInstance().setActive(false, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
    }

    /** Takes the audio (ducking others); false, with [problem] set, when the system refuses. */
    private fun activate(): Boolean {
        val session = AVAudioSession.sharedInstance()
        val ok = session.setCategory(AVAudioSessionCategoryPlayback, AVAudioSessionModeSpokenAudio, AVAudioSessionCategoryOptionDuckOthers, null) &&
            session.setActive(true, null)
        if (!ok) {
            Log.w(LogTag.Voice, "The audio session could not be started for speech")
            _problem.value = "The phone would not give Edith the speaker."
        } else if (_problem.value != null) {
            _problem.value = null
        }
        return ok
    }
}
