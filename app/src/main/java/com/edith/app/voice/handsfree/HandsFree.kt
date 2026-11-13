package com.edith.app.voice.handsfree

import com.edith.app.chat.engine.EngineState
import com.edith.app.chat.engine.isRunning
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.settings.SettingsRepository
import com.edith.app.voice.capture.SpeechInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Hands-free mode (C14): "Hey Edith", a question, the spoken answer, and follow-ups without the wake word.
 *
 * ```
 * Off ─start─► Listening ─wake word─► Capturing ─text─► Thinking ─► Speaking ─done─► Capturing (follow-up)
 *                 ▲    ▲                  │ nothing heard                │ wake word (barge-in)   │ silence
 *                 │    └──────────────────┘                              └─► Capturing            │
 *                 └───────────────────────────────────────────────────────────────────────────────┘
 * ```
 * - The wake-word listener and the speech recognizer never hold the microphone at the same time: the listener is
 *   stopped before the recognizer starts.
 * - While Edith speaks, the wake word is listened for again, so "Hey Edith" interrupts her (barge-in).
 * - Three recognizer errors in a row turn hands-free off with a notice.
 *
 * App-scoped, like the chat engine; [HandsFreeService] keeps it alive with the notification. Runs on the main
 * thread ([scope]), which Android's speech recognizer requires.
 */
class HandsFree(
    private val wake: WakeListener,
    private val recognizer: SpeechInput,
    private val engineState: StateFlow<EngineState>,
    private val speaking: StateFlow<Long?>,
    private val stopSpeaking: () -> Unit,
    /** Stops the reply being written (a barge-in while it still streams); what she wrote is kept, marked stopped. */
    private val cancelReply: () -> Unit,
    /** Sends the text to the open chat, as a hands-free message. */
    private val send: suspend (String) -> Unit,
    private val earcon: suspend () -> Unit,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    /** What to say in notices, e.g. "Hey Edith". */
    val phrase: String,
    /** Says a line without stopping the reply (C25: "tap the card on the screen"). */
    private val say: (String) -> Unit = {},
) {
    sealed interface State {
        data object Off : State
        data object Paused : State
        data object Listening : State
        data class Capturing(val partial: String = "", val followUp: Boolean = false) : State
        data object Thinking : State
        data object Speaking : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** Why hands-free stopped or what went wrong, for the chat screen. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var errors = 0
    private var job: Job? = null
    private var followUpTimer: Job? = null
    /** The follow-up window is still open: a recognizer that heard nothing is started again. */
    private var followUpOpen = false

    val isOn: Boolean get() = _state.value != State.Off

    fun start() {
        if (_state.value != State.Off && _state.value != State.Paused) return
        _notice.value = null
        errors = 0
        Log.i(LogTag.Voice, "Hands-free on")
        listen()
    }

    /** Turns hands-free off. [reason] is shown to the owner when it wasn't their choice. */
    fun stop(reason: String? = null) {
        if (_state.value == State.Off) return
        release()
        _state.value = State.Off
        if (reason != null) _notice.value = reason
        Log.i(LogTag.Voice, "Hands-free off${reason?.let { ": $it" }.orEmpty()}")
    }

    /** Mic off, notification kept; [resume] listens again. */
    fun pause() {
        if (_state.value == State.Off || _state.value == State.Paused) return
        release()
        _state.value = State.Paused
    }

    fun resume() {
        if (_state.value == State.Paused) listen()
    }

    fun dismissNotice() {
        _notice.value = null
    }

    /** The wake word was heard (from the listener's thread, or the debug hook). */
    fun wakeWordHeard() {
        scope.launch {
            val s = _state.value
            if (s != State.Listening && s != State.Speaking) return@launch
            wake.stop()
            if (s == State.Speaking) {   // barge-in
                Log.d(LogTag.Voice, "Barge-in: stopping Edith's voice")
                job?.cancel()
                stopSpeaking()
                if (engineState.value.isRunning()) cancelReply()
            }
            capture(followUp = false)
        }
    }

    /**
     * The recognizer's final text. Public for the debug hook, which says a sentence for the emulator's silent
     * microphone; it replaces whatever the recognizer was hearing.
     */
    fun heard(text: String) {
        if (_state.value !is State.Capturing) return
        followUpTimer?.cancel()
        recognizer.cancel()
        onFinal(text)
    }

    private fun listen() {
        release()
        _state.value = State.Listening
        scope.launch {
            val threshold = OpenWakeWord.threshold(settings.current().wakeWordSensitivity)
            if (_state.value != State.Listening) return@launch
            if (!wake.start(threshold, ::wakeWordHeard, ::wakeListenerFailed)) stop(MIC_UNAVAILABLE)
        }
    }

    /** The wake-word listener stopped on its own (from its thread). */
    private fun wakeListenerFailed() {
        scope.launch {
            if (_state.value == State.Listening) stop(MIC_UNAVAILABLE)
            // While Edith speaks it was only for barge-in: she finishes, and the follow-up carries on.
        }
    }

    private suspend fun capture(followUp: Boolean) {
        _state.value = State.Capturing(followUp = followUp)
        if (!followUp) earcon()
        if (_state.value !is State.Capturing) return
        startRecognizer()
        if (followUp) {
            followUpOpen = true
            followUpTimer = scope.launch {
                delay(settings.current().followUpSeconds * 1000L)
                followUpOpen = false
                if (_state.value is State.Capturing) {
                    Log.d(LogTag.Voice, "Follow-up window ended")
                    recognizer.cancel()
                    listen()
                }
            }
        }
    }

    private fun startRecognizer() {
        recognizer.start(object : SpeechInput.Listener {
            override fun onPartial(text: String) {
                followUpTimer?.cancel()   // the owner is talking
                followUpOpen = false
                val s = _state.value
                if (s is State.Capturing) _state.value = s.copy(partial = text)
            }
            override fun onFinal(text: String) = this@HandsFree.onFinal(text)
            override fun onError(message: String) = this@HandsFree.onError(message)
        })
    }

    private fun onFinal(text: String) {
        if (_state.value !is State.Capturing) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            if (followUpOpen) {
                // The recognizer gives up on silence after a few seconds; the follow-up window is longer.
                // A short pause first, so a recognizer that ends at once can't spin.
                scope.launch {
                    delay(RESTART_PAUSE_MS)
                    if (followUpOpen && _state.value is State.Capturing) startRecognizer()
                }
                return
            }
            followUpTimer?.cancel()
            listen()   // nothing heard: quietly back to the wake word
            return
        }
        followUpTimer?.cancel()
        followUpOpen = false
        errors = 0
        reply(trimmed)
    }

    private fun onError(message: String) {
        if (_state.value !is State.Capturing) return
        followUpTimer?.cancel()
        followUpOpen = false
        errors++
        Log.w(LogTag.Voice, "Hands-free recognizer error $errors/$MAX_ERRORS: $message")
        if (errors >= MAX_ERRORS) stop("Hands-free stopped: $message") else listen()
    }

    /** Sends [text], then follows the reply: Thinking → Speaking (barge-in possible) → a follow-up. */
    private fun reply(text: String) {
        if (engineState.value.isRunning()) {
            _notice.value = BUSY
            listen()
            return
        }
        _state.value = State.Thinking
        job = scope.launch {
            // Hands-free can't approve a card by voice (C25, D10): she says so, and the reply waits for the tap.
            var prompted: String? = null
            val prompts = launch {
                engineState.collect { s ->
                    if (s is EngineState.AwaitingConfirmation && s.request.id != prompted) {
                        prompted = s.request.id
                        say(confirmPrompt(s.request))
                    }
                }
            }
            send(text)
            // Wait for the reply to be written and spoken. Speaking starts while it's still streaming (C13).
            combine(engineState, speaking) { engine, voice -> engine.isRunning() to (voice != null) }
                .first { (running, talking) ->
                    if (talking && _state.value == State.Thinking) {
                        _state.value = State.Speaking
                        val threshold = OpenWakeWord.threshold(settings.current().wakeWordSensitivity)
                        wake.start(threshold, ::wakeWordHeard, ::wakeListenerFailed)   // barge-in; if it can't, she just finishes
                    }
                    !running && !talking
                }
            prompts.cancel()
            wake.stop()
            if (settings.current().followUpSeconds > 0) capture(followUp = true) else listen()
        }
    }

    private fun release() {
        job?.cancel(); job = null
        followUpTimer?.cancel(); followUpTimer = null
        followUpOpen = false
        wake.stop()
        recognizer.cancel()
    }

    companion object {
        const val MAX_ERRORS = 3
        const val RESTART_PAUSE_MS = 250L
        const val MIC_UNAVAILABLE = "Hands-free stopped: the microphone isn't available."
        const val BUSY = "Edith is still answering — ask again when she's done."
        const val CALL = "Hands-free stopped for a call."
        const val MIC_TAKEN = "Hands-free stopped: another app is using the microphone."

        /** What she says while a card waits: hands-free has no voice approval, and some cards never allow one (D10). */
        fun confirmPrompt(request: com.edith.app.chat.engine.ConfirmationRequest): String =
            if (request.screenOnly) "For safety, this needs a tap on the screen: tap ${request.confirmLabel} to go ahead."
            else "Tap ${request.confirmLabel} on the screen to go ahead, or ${request.denyLabel}."
    }
}
