package com.edith.app.voice.own

import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.voice.speech.TtsEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Edith's own voice (C36 §1, decision D21): a [TtsEngine] that speaks with a neural voice run by sherpa-onnx, for
 * phones that have no text-to-speech engine. [SpeechOutput][com.edith.app.voice.speech.SpeechOutput] and hands-free
 * don't know the difference.
 *
 * Two threads. The *generator* turns each sentence into audio (never on the main thread), at most [maxAhead] sentences
 * ahead of what has been heard. The *player* feeds the audio to an [AudioSink] and tells the listener:
 *  - `onStart` when the first samples of a sentence have reached the speaker,
 *  - `onDone` when its **last sample has been played**, not when it was generated: hands-free starts listening on
 *    `onDone`, so anything earlier makes her hear herself (C36 trap 3),
 *  - `onError` when generating it failed.
 *
 * The model is loaded on the first sentence, on the generator thread (a sentence waits for it), and unloaded after
 * [idleUnloadMs] without speech unless [keepLoaded] says hands-free is on: Andarium may kill Edith when memory is
 * short (C17b). Nothing she says is ever logged, only lengths and timings (C36 trap 4).
 */
class SherpaTts(
    private val loadSynth: () -> SpeechSynth,
    private val sink: AudioSink,
    private val focus: SpeechFocus,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val keepLoaded: () -> Boolean = { false },
    private val idleUnloadMs: Long = IDLE_UNLOAD_MS,
    private val maxAhead: Int = MAX_AHEAD,
) : TtsEngine {
    private val _available = MutableStateFlow(true)
    override val available: StateFlow<Boolean> = _available.asStateFlow()
    private val _problem = MutableStateFlow<String?>(null)
    override val problem: StateFlow<String?> = _problem.asStateFlow()

    @Volatile private var listener: TtsEngine.Listener? = null

    private class Utterance(val text: String, val id: String, val speed: Float, val epoch: Int, val queuedAtMs: Long)
    private object End
    private class Failure(val error: Throwable)
    private class Segment(val utterance: Utterance) {
        @Volatile var sampleRate = 0
        val chunks = LinkedBlockingQueue<Any>()
    }
    private class Awaiting(val id: String, val epoch: Int, val endFrame: Long)

    /** Bumped by [stop]: work of an older epoch is dropped wherever it is. */
    @Volatile private var epoch = 0
    private val textQueue = LinkedBlockingQueue<Utterance>()
    private val playQueue = LinkedBlockingQueue<Segment>()
    private val awaiting = ConcurrentLinkedQueue<Awaiting>()

    /** Sentences generated (or being) and not yet heard, of the current epoch. Guarded by [flowLock]. */
    private var inFlight = 0
    private val flowLock = ReentrantLock()
    private val flowChanged = flowLock.newCondition()

    private val synthLock = ReentrantLock()
    private var synth: SpeechSynth? = null
    @Volatile private var lastActivityMs = nowMs()

    private var generator: Thread? = null
    private var player: Thread? = null
    @Volatile private var closed = false

    init {
        focus.onLost = {
            Log.d(LogTag.Voice, "Audio focus lost; stopping speech")
            listener?.onInterrupted()
        }
    }

    override fun setListener(listener: TtsEngine.Listener) {
        this.listener = listener
    }

    override fun speak(text: String, utteranceId: String, rate: Float): Boolean {
        if (!_available.value || closed) return false
        focus.request()
        startThreads()
        lastActivityMs = nowMs()
        textQueue.put(Utterance(text, utteranceId, rate.coerceIn(0.5f, 2f), epoch, nowMs()))
        return true
    }

    override fun stop() {
        flowLock.withLock {
            epoch++
            inFlight = 0
            flowChanged.signalAll()
        }
        textQueue.clear()
        playQueue.clear()
        awaiting.clear()
        sink.stop()   // after the epoch changed: audio of the old epoch can't start again (see [play])
        focus.abandon()
    }

    override fun idle() {
        focus.abandon()
        if (flowLock.withLock { inFlight == 0 } && textQueue.isEmpty()) sink.release()
    }

    /** Unloads the voice if nothing was said for [idleUnloadMs] and hands-free isn't waiting for a wake word. */
    fun unloadIfIdle() {
        if (flowLock.withLock { inFlight != 0 } || textQueue.isNotEmpty()) return
        if (nowMs() - lastActivityMs < idleUnloadMs || keepLoaded()) return
        if (!synthLock.tryLock()) return
        try {
            synth?.let {
                it.close()
                synth = null
                Log.d(LogTag.Voice, "Own voice unloaded after ${(nowMs() - lastActivityMs) / 1000} s without speech")
            }
        } finally {
            synthLock.unlock()
        }
    }

    /** True while the model is in memory. */
    val isLoaded: Boolean get() = synthLock.withLock { synth != null }

    /** The voice files changed (downloaded again, or deleted): forget the model and any "couldn't start" problem. */
    fun reset() {
        stop()
        synthLock.withLock {
            synth?.close()
            synth = null
        }
        _problem.value = null
        _available.value = true
    }

    /** Stops the threads (tests; the app keeps one for its whole life). */
    fun close() {
        closed = true
        stop()
        generator?.interrupt()
        player?.interrupt()
        synthLock.withLock {
            synth?.close()
            synth = null
        }
    }

    // --- The generator thread ---------------------------------------------------------------------------------------

    @Synchronized private fun startThreads() {
        if (generator != null) return
        generator = thread(name = "edith-voice-generate", isDaemon = true) { runCatching { generatorLoop() } }
        player = thread(name = "edith-voice-play", isDaemon = true) { runCatching { playerLoop() } }
    }

    private fun generatorLoop() {
        while (!closed) {
            val utterance = textQueue.take()
            if (utterance.epoch != epoch || !reserveSlot(utterance.epoch)) continue
            val segment = Segment(utterance)
            playQueue.put(segment)   // in order: the player starts on it as soon as its first samples arrive
            try {
                synthLock.withLock {
                    if (utterance.epoch != epoch) return@withLock
                    check(_available.value) { "The voice isn't available" }
                    val synth = synth ?: loadSynth().also { synth = it }
                    segment.sampleRate = synth.sampleRate
                    val startedAt = nowMs()
                    var samples = 0L
                    synth.generate(utterance.text, utterance.speed) { chunk ->
                        if (utterance.epoch != epoch) {
                            false
                        } else {
                            samples += chunk.size
                            segment.chunks.put(chunk)
                            true
                        }
                    }
                    Log.d(
                        LogTag.Voice,
                        "Own voice: ${utterance.text.length} chars generated in ${nowMs() - startedAt} ms, " +
                            "${samples * 1000 / synth.sampleRate} ms of audio",
                    )
                }
                segment.chunks.put(End)
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Throwable) {
                Log.w(LogTag.Voice, "Own voice couldn't speak a sentence of ${utterance.text.length} chars", e)
                if (synth == null) {   // the model itself wouldn't load: say so in Settings
                    _problem.value = LOAD_PROBLEM
                    _available.value = false
                }
                segment.chunks.put(Failure(e))
            }
        }
    }

    /** Waits until fewer than [maxAhead] sentences are waiting to be heard. False if [stop] came meanwhile. */
    private fun reserveSlot(forEpoch: Int): Boolean = flowLock.withLock {
        while (inFlight >= maxAhead && forEpoch == epoch) flowChanged.await(50, TimeUnit.MILLISECONDS)
        if (forEpoch != epoch) return false
        inFlight++
        true
    }

    private fun releaseSlot(forEpoch: Int) = flowLock.withLock {
        if (forEpoch == epoch && inFlight > 0) inFlight--
        flowChanged.signalAll()
    }

    // --- The player thread ------------------------------------------------------------------------------------------

    private fun playerLoop() {
        var lastUnloadCheckMs = nowMs()
        while (!closed) {
            fireDone()
            if (nowMs() - lastUnloadCheckMs >= UNLOAD_CHECK_MS) {
                lastUnloadCheckMs = nowMs()
                unloadIfIdle()
            }
            val segment = playQueue.poll(POLL_MS, TimeUnit.MILLISECONDS) ?: continue
            play(segment)
        }
    }

    private fun play(segment: Segment) {
        val utterance = segment.utterance
        val sinkGeneration = sink.generation   // read before the epoch check: a [stop] in between makes the writes fail
        var wrote = false
        while (!closed) {
            if (utterance.epoch != epoch) return
            val item = segment.chunks.poll(POLL_MS, TimeUnit.MILLISECONDS)
            if (item == null) {
                fireDone()   // the last sentence may be finishing while this one is still being generated
                continue
            }
            when (item) {
                is FloatArray -> {
                    // In slices: the first one reaching the speaker is "started" (the whole chunk may be seconds of audio
                    // that the speaker only accepts as it plays), and a [stop] is noticed between slices.
                    var from = 0
                    while (from < item.size) {
                        val to = minOf(from + SLICE_SAMPLES, item.size)
                        val slice = if (from == 0 && to == item.size) item else item.copyOfRange(from, to)
                        if (!sink.write(slice, segment.sampleRate, sinkGeneration) || utterance.epoch != epoch) return
                        if (!wrote) {
                            wrote = true
                            Log.d(LogTag.Voice, "Own voice: first audio ${nowMs() - utterance.queuedAtMs} ms after the sentence was queued")
                            listener?.onStart(utterance.id)
                        }
                        from = to
                    }
                }
                End -> {
                    if (wrote) {
                        awaiting.add(Awaiting(utterance.id, utterance.epoch, sink.framesWritten))
                    } else {   // nothing to hear (a sentence of only symbols)
                        releaseSlot(utterance.epoch)
                        listener?.onDone(utterance.id)
                    }
                    return
                }
                is Failure -> {
                    releaseSlot(utterance.epoch)
                    listener?.onError(utterance.id)
                    return
                }
            }
        }
    }

    /** Tells the listener about every sentence whose last sample has been played. */
    private fun fireDone() {
        while (true) {
            val next = awaiting.peek() ?: return
            if (next.epoch != epoch) {
                awaiting.poll()
                continue
            }
            if (sink.framesPlayed() < next.endFrame) return
            awaiting.poll()
            releaseSlot(next.epoch)
            lastActivityMs = nowMs()
            listener?.onDone(next.id)
        }
    }

    companion object {
        /** C36 §1: generation runs at most this many sentences ahead of what has been heard. */
        const val MAX_AHEAD = 3
        /** C36 §1: the model is unloaded after this long without speech (not while hands-free is on). */
        const val IDLE_UNLOAD_MS = 2 * 60 * 1000L
        private const val POLL_MS = 20L
        /** Samples handed to the speaker at a time (a fifth of a second at 22,050 Hz). */
        private const val SLICE_SAMPLES = 4_096
        private const val UNLOAD_CHECK_MS = 5_000L
        const val LOAD_PROBLEM = "Edith's own voice wouldn't start. Delete it and download it again in Settings → Voice."
    }
}
