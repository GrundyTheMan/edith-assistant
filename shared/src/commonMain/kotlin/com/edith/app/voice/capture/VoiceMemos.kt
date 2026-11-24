package com.edith.app.voice.capture

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.time.SystemClock
import com.edith.app.core.util.deleteFileQuietly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Voice memos (C12): record → transcribe → send as the owner's message (source `voice_memo`) to the chat it was
 * started in. App-scoped like the chat engine, so rotating the phone doesn't lose a recording or a transcription.
 *
 * Two ways to capture, chosen when recording starts:
 * - the model hears audio → a WAV ([Recorder]) transcribed by the model ([Transcriber]); the file is deleted after
 *   transcription, whatever happens;
 * - it doesn't → Android's live speech recognition ([SpeechInput]), because `SpeechRecognizer` can't transcribe a file.
 *
 * Every call comes from the main thread; state changes happen there too.
 */
class VoiceMemos(
    private val recorder: Recorder,
    private val transcriber: Transcriber,
    private val liveSpeech: SpeechInput,
    private val modelHears: suspend () -> Boolean,
    private val send: (conversationId: Long, text: String) -> Unit,
    private val stopSpeaking: () -> Unit,
    private val scope: CoroutineScope,
    private val dispatchers: AppDispatchers,
    private val nowMs: () -> Long = { SystemClock.now().toEpochMilliseconds() },
    private val maxMs: Long = MAX_MS,
) {
    sealed interface State {
        data object Idle : State
        /** Checking whether the model hears audio, then opening the microphone. */
        data class Starting(val conversationId: Long) : State
        data class Recording(val conversationId: Long, val startedAtMs: Long, val live: Boolean) : State
        data class Transcribing(val conversationId: Long) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val liveLevel = MutableStateFlow(0f)
    /** 0..1 for the level meter, from whichever capture is running. */
    val level: StateFlow<Float> = combine(recorder.level, liveLevel) { a, b -> maxOf(a, b) }
        .stateIn(scope, SharingStarted.WhileSubscribed(), 0f)

    private val _notice = MutableStateFlow<String?>(null)
    /** A short message about the last memo ("No speech was heard…"); dismissed by the owner or the next memo. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun dismissNotice() {
        _notice.value = null
    }

    private var starting: Job? = null
    private var autoStop: Job? = null

    fun start(conversationId: Long) {
        if (_state.value != State.Idle) return
        stopSpeaking()   // so Edith's voice isn't recorded (C13)
        _notice.value = null
        _state.value = State.Starting(conversationId)
        starting = scope.launch(dispatchers.main) {
            val live = !modelHears()
            val opened = if (live) {
                if (liveSpeech.isAvailable) liveSpeech.start(liveListener(conversationId)).let { true }
                else false.also { _notice.value = CANT_HEAR }
            } else {
                withContext(dispatchers.io) { recorder.start() }.also { if (!it) _notice.value = MIC_UNAVAILABLE }
            }
            if (!opened) {
                _state.value = State.Idle
                return@launch
            }
            Log.i(LogTag.Voice, "Recording a voice memo (${if (live) "live recognition" else "WAV for the model"})")
            _state.value = State.Recording(conversationId, nowMs(), live)
            autoStop = scope.launch(dispatchers.main) {
                delay(maxMs)
                stopAndSend(atLimit = true)
            }
        }
    }

    fun stopAndSend() = stopAndSend(atLimit = false)

    private fun stopAndSend(atLimit: Boolean) {
        val recording = _state.value as? State.Recording ?: return
        autoStop?.cancel()
        if (atLimit) _notice.value = AT_LIMIT
        _state.value = State.Transcribing(recording.conversationId)
        if (recording.live) {
            liveSpeech.stop()   // the listener sends the final text
            return
        }
        scope.launch(dispatchers.main) {
            val audio = withContext(dispatchers.io) { recorder.stop() }
            when {
                audio == null -> _state.value = State.Idle
                audio.durationMs < MIN_MS -> {
                    withContext(dispatchers.io) { deleteFileQuietly(audio.path) }
                    _notice.value = TOO_SHORT
                    _state.value = State.Idle
                }
                else -> sendRecording(recording.conversationId, audio)
            }
        }
    }

    /** Transcribes [audio] and sends it to [conversationId]. Also the debug hook's way in (a pushed WAV). */
    suspend fun sendRecording(conversationId: Long, audio: RecordedAudio) {
        _state.value = State.Transcribing(conversationId)
        try {
            when (val r = transcriber.transcribe(audio)) {
                is Outcome.Success -> deliver(conversationId, r.value)
                is Outcome.Failure -> _notice.value = "Couldn't transcribe the recording: ${r.error.userMessage}"
            }
        } finally {
            withContext(dispatchers.io) { deleteFileQuietly(audio.path) }
            _state.value = State.Idle
        }
    }

    /** ✕ while recording: nothing is kept or sent. [reason] is shown when it wasn't the owner's choice. */
    fun cancel(reason: String? = null) {
        when (val s = _state.value) {
            is State.Starting -> {
                starting?.cancel()
                _state.value = State.Idle
            }
            is State.Recording -> {
                autoStop?.cancel()
                if (s.live) liveSpeech.cancel() else scope.launch(dispatchers.io) { recorder.cancel() }
                liveLevel.value = 0f
                _state.value = State.Idle
                reason?.let { _notice.value = it }
            }
            else -> Unit   // already recorded: a transcription finishes on its own
        }
    }

    private fun deliver(conversationId: Long, text: String) {
        if (text.isBlank()) {
            _notice.value = NO_SPEECH   // L11: never send an empty or apologising "transcript"
            return
        }
        send(conversationId, text.trim())
    }

    private fun liveListener(conversationId: Long) = object : SpeechInput.Listener {
        override fun onLevel(level: Float) {
            liveLevel.value = level
        }

        override fun onFinal(text: String) {
            autoStop?.cancel()
            liveLevel.value = 0f
            if (_state.value == State.Idle) return   // cancelled
            deliver(conversationId, text)
            _state.value = State.Idle
        }

        override fun onError(message: String) {
            autoStop?.cancel()
            liveLevel.value = 0f
            if (_state.value == State.Idle) return
            _notice.value = message
            _state.value = State.Idle
        }
    }

    companion object {
        const val MAX_MS = 5 * 60_000L
        const val MIN_MS = 700L
        const val MIC_DENIED = "Edith needs microphone access to hear you. You can allow it in Settings → Apps → Edith."
        const val NO_SPEECH = "No speech was heard in that recording."
        const val TOO_SHORT = "That was too short to send."
        const val AT_LIMIT = "Recordings stop at 5 minutes — that one was sent."
        const val LEFT_APP = "The recording stopped when you left the app, so it wasn't sent."
        const val MIC_UNAVAILABLE = "The microphone couldn't be opened — another app may be using it."
        const val CANT_HEAR = "This model can't hear audio, and speech recognition isn't available on this device."
    }
}
