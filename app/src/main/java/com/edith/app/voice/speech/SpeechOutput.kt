package com.edith.app.voice.speech

import com.edith.app.chat.engine.ReplyListener
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Edith's voice (C13): reads replies aloud with the phone's text-to-speech, sentence by sentence as they stream in
 * (L14), and plays a stored message when the owner taps Play.
 *
 * Each thing spoken (a streaming reply, or one Play) is a new generation; its utterances are `"<generation>-<n>"`.
 * [stop] moves to a new generation, so a late callback from a stopped utterance can't clear newer speech.
 *
 * A streaming reply has no message id until it's stored, so [speakingMessageId] is [LIVE_REPLY] while it streams and
 * becomes the stored message's id when it finishes (if it's still being spoken).
 *
 * Text written before a tool call ("Let me check…") is spoken if a sentence of it completed; an unfinished sentence
 * is dropped with it ([onReset]).
 */
class SpeechOutput(
    private val engine: TtsEngine,
    settings: SettingsRepository,
    scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : SpeechController, ReplyListener, TtsEngine.Listener {
    private val _speaking = MutableStateFlow<Long?>(null)
    override val speakingMessageId: StateFlow<Long?> = _speaking.asStateFlow()

    /** Why the phone can't speak, or null (shown in Settings). */
    val problem: StateFlow<String?> = engine.problem

    @Volatile private var speakReplies = true
    @Volatile private var rate = 1.0f

    private val lock = Any()
    private var generation = 0
    private var sequence = 0
    /** Utterances of the current generation not yet finished. */
    private var pending = 0
    /** A reply is streaming into the current generation. */
    private var live = false
    /** The owner stopped the streaming reply's speech; the rest of that reply stays quiet. */
    private var liveMuted = false
    private val chunker = SentenceChunker()
    /** For the latency log: when the reply's first text arrived and when its first sentence was queued. */
    private var firstTextAtMs = 0L
    private var firstQueuedAtMs = 0L

    init {
        engine.setListener(this)
        scope.launch {
            settings.settings.collect {
                speakReplies = it.speakReplies
                rate = it.speechRate.coerceIn(MIN_RATE, MAX_RATE)
            }
        }
    }

    // --- Play / Stop (C07's action row) -----------------------------------------------------------------------

    override fun play(messageId: Long, text: String) {
        synchronized(lock) {
            newGeneration()
            if (live) liveMuted = true   // playing an older message silences the reply being written
            val pieces = SentenceChunker().run { add(text) + flush() }
            pieces.forEach { enqueue(it) }
            _speaking.value = if (pending > 0) messageId else null
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (live) liveMuted = true
            newGeneration()
            _speaking.value = null
        }
    }

    /**
     * Says [text] after whatever is being said, without stopping or muting the reply being written (C25: hands-free
     * says to tap a card on the screen; the reply carries on, and is spoken, once the owner answers).
     */
    fun announce(text: String) {
        synchronized(lock) {
            if (live && !liveMuted) chunker.flush().forEach { enqueueLive(it) }
            if (enqueue(text) && _speaking.value == null) _speaking.value = LIVE_REPLY
        }
    }

    /** Says a short line so the owner can hear the voice and speed (Settings → Test voice). */
    fun test() = play(TEST_VOICE, TEST_LINE)

    // --- Streaming replies (ReplyListener) ------------------------------------------------------------------

    override fun onDelta(conversationId: Long, text: String) {
        synchronized(lock) {
            if (!live) {
                if (!speakReplies) return
                newGeneration()
                live = true
                liveMuted = false
                chunker.reset()
                firstTextAtMs = nowMs()
                firstQueuedAtMs = 0L
            }
            if (liveMuted) return
            chunker.add(text).forEach { enqueueLive(it) }
        }
    }

    override fun onReset(conversationId: Long) {
        synchronized(lock) { if (live) chunker.reset() }
    }

    override fun onFinished(conversationId: Long, messageId: Long?, text: String) {
        synchronized(lock) {
            if (!live) return
            if (!liveMuted) chunker.flush().forEach { enqueueLive(it) }
            endLive()
            _speaking.value = if (pending > 0) messageId ?: LIVE_REPLY else null
            if (pending == 0) engine.idle()
        }
    }

    override fun onCancelled(conversationId: Long) = endLiveAndStop()

    override fun onFailed(conversationId: Long) = endLiveAndStop()

    private fun endLiveAndStop() {
        synchronized(lock) {
            if (!live) return
            endLive()
            newGeneration()
            _speaking.value = null
        }
    }

    private fun endLive() {
        live = false
        liveMuted = false
        chunker.reset()
    }

    // --- The engine's callbacks -----------------------------------------------------------------------------

    override fun onStart(utteranceId: String) {
        val log = synchronized(lock) {
            if (!isCurrent(utteranceId) || firstQueuedAtMs == 0L) return
            val now = nowMs()
            "Speech started ${now - firstTextAtMs} ms after the reply's first text (${now - firstQueuedAtMs} ms after its first sentence was ready)"
                .also { firstQueuedAtMs = 0L }
        }
        Log.d(LogTag.Voice, log)
    }

    override fun onDone(utteranceId: String) = finished(utteranceId)

    override fun onError(utteranceId: String) = finished(utteranceId)

    override fun onInterrupted() = stop()

    private fun finished(utteranceId: String) {
        synchronized(lock) {
            if (!isCurrent(utteranceId)) return   // from speech that was stopped
            pending = (pending - 1).coerceAtLeast(0)
            if (pending == 0 && !live) {
                _speaking.value = null
                engine.idle()
            }
        }
    }

    // --- Helpers (called with the lock held) ------------------------------------------------------------------

    private fun newGeneration() {
        val wasSpeaking = pending > 0
        generation++
        sequence = 0
        pending = 0
        if (wasSpeaking) engine.stop()
    }

    private fun enqueueLive(sentence: String) {
        if (enqueue(sentence)) {
            if (_speaking.value == null) _speaking.value = LIVE_REPLY
            if (firstQueuedAtMs == 0L && sequence == 1) firstQueuedAtMs = nowMs()
        }
    }

    /** Queues one sentence of the current generation. False if there was nothing to say or it couldn't be queued. */
    private fun enqueue(sentence: String): Boolean {
        val text = SpeechText.clean(sentence)
        if (text.isEmpty()) return false
        val id = "$generation-${sequence++}"
        pending++
        if (!engine.speak(text, id, rate)) {
            pending--
            return false
        }
        return true
    }

    private fun isCurrent(utteranceId: String): Boolean = utteranceId.substringBefore('-') == generation.toString()

    companion object {
        /** [speakingMessageId] while a reply that isn't stored yet is being spoken. */
        const val LIVE_REPLY = -1L
        /** [speakingMessageId] during Settings → Test voice. */
        const val TEST_VOICE = -2L
        const val TEST_LINE = "Hi, I'm Edith."
        const val MIN_RATE = 0.6f
        const val MAX_RATE = 1.6f
    }
}
