package com.edith.app.voice.speech

import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.settings.SpeechVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.updateAndFetch

/** The voice that speaks right now (C36 §4). */
enum class ActiveVoice { Phone, Own, None }

/**
 * Which voice speaks (C36 §4), from the setting `speech_voice` and what is usable. A pure function, so one test covers
 * every combination.
 *  - `auto`: the phone's voice when it has one, else Edith's own when it is downloaded, else none.
 *  - `phone`: the phone's voice or none. `own`: Edith's own or none.
 * [phoneAvailable] is false for a phone with no voice, and for one whose only voice needs the network (A4).
 */
fun chooseVoice(setting: SpeechVoice, phoneAvailable: Boolean, ownReady: Boolean): ActiveVoice = when (setting) {
    SpeechVoice.Auto -> when {
        phoneAvailable -> ActiveVoice.Phone
        ownReady -> ActiveVoice.Own
        else -> ActiveVoice.None
    }
    SpeechVoice.Phone -> if (phoneAvailable) ActiveVoice.Phone else ActiveVoice.None
    SpeechVoice.Own -> if (ownReady) ActiveVoice.Own else ActiveVoice.None
}

/** The words for [ActiveVoice.None], by what the owner chose. */
fun noVoiceProblem(setting: SpeechVoice, phoneProblem: String?): String = when (setting) {
    SpeechVoice.Auto -> NO_VOICE_AUTO
    SpeechVoice.Phone -> phoneProblem ?: NO_VOICE_AUTO
    SpeechVoice.Own -> OWN_NOT_READY
}

const val NO_VOICE_AUTO = "Your phone has no voice. Download Edith's own voice in Settings → Voice."
const val OWN_NOT_READY = "Edith's own voice isn't downloaded yet. Get it in Settings → Voice."

/**
 * Forwards to the phone's voice or to Edith's own, whichever [chooseVoice] says (C36 §4). When the choice changes (the
 * setting, or a download finishing) it stops the old voice first. [available] and [problem] describe the choice.
 */
@OptIn(ExperimentalAtomicApi::class)
class ChoosingTts(
    private val phone: TtsEngine,
    private val own: TtsEngine,
    setting: Flow<SpeechVoice>,
    ownReady: Flow<Boolean>,
    scope: CoroutineScope,
) : TtsEngine {

    /** What is being said now, per engine, so a switch can tell [TtsEngine.Listener] that the speech ended. */
    private val inFlight = AtomicInt(0)
    @Volatile private var listener: TtsEngine.Listener? = null

    /** The voice in use; the engines' own availability counts. */
    val active: StateFlow<ActiveVoice> = combine(setting, phone.available, ownReady, own.available) { s, p, o, ownOk ->
        chooseVoice(s, phoneAvailable = p, ownReady = o && ownOk)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, chooseVoice(SpeechVoice.Auto, phone.available.value, false))

    /** The phone has a usable voice (Settings offers Edith's own when it doesn't). */
    val phoneAvailable: StateFlow<Boolean> = phone.available

    override val available: StateFlow<Boolean> = combine(active, own.available) { a, ownOk -> a != ActiveVoice.None && (a != ActiveVoice.Own || ownOk) }
        .stateIn(scope, SharingStarted.Eagerly, active.value != ActiveVoice.None)

    override val problem: StateFlow<String?> = combine(active, setting, phone.problem, own.problem) { a, s, phoneProblem, ownProblem ->
        when (a) {
            ActiveVoice.Phone -> phoneProblem
            ActiveVoice.Own -> ownProblem
            ActiveVoice.None -> noVoiceProblem(s, phoneProblem)
        }
    }.stateIn(scope, SharingStarted.Eagerly, if (active.value == ActiveVoice.None) NO_VOICE_AUTO else null)

    init {
        scope.launch {
            var before = active.value
            active.collect { now ->
                if (now == before) return@collect
                Log.d(LogTag.Voice, "Voice changed: $before → $now")
                switchedAway(before)
                before = now
            }
        }
        phone.setListener(Forward())
        own.setListener(Forward())
    }

    /** Stops [old] and, if it was in the middle of speech, tells the listener so the speech state is cleared. */
    private fun switchedAway(old: ActiveVoice) {
        val speaking = inFlight.exchange(0) > 0
        when (old) {
            ActiveVoice.Phone -> phone.stop()
            ActiveVoice.Own -> own.stop()
            ActiveVoice.None -> {}
        }
        if (speaking) listener?.onInterrupted()
    }

    override fun setListener(listener: TtsEngine.Listener) {
        this.listener = listener
    }

    override fun speak(text: String, utteranceId: String, rate: Float): Boolean {
        val engine = when (active.value) {
            ActiveVoice.Phone -> phone
            ActiveVoice.Own -> own
            ActiveVoice.None -> return false
        }
        inFlight.incrementAndFetch()
        if (engine.speak(text, utteranceId, rate)) return true
        inFlight.decrementAndFetch()
        return false
    }

    override fun stop() {
        inFlight.store(0)
        phone.stop()
        own.stop()
    }

    override fun idle() {
        phone.idle()
        own.idle()
    }

    private inner class Forward : TtsEngine.Listener {
        override fun onStart(utteranceId: String) { listener?.onStart(utteranceId) }
        override fun onDone(utteranceId: String) { inFlight.updateAndFetch { (it - 1).coerceAtLeast(0) }; listener?.onDone(utteranceId) }
        override fun onError(utteranceId: String) { inFlight.updateAndFetch { (it - 1).coerceAtLeast(0) }; listener?.onError(utteranceId) }
        override fun onInterrupted() { listener?.onInterrupted() }
    }
}
