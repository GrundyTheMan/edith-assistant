package com.edith.app.voice.own

import com.edith.app.voice.speech.TtsEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * C36 §1: [SherpaTts] with a fake voice, a fake speaker and a fake clock (no native code). The fake voice makes 1000
 * samples per second of audio, so numbers are easy to follow: each sentence is two chunks of 500 samples.
 */
class SherpaTtsTest {

    private class FakeSynth : SpeechSynth {
        override val sampleRate = 1000
        val texts = CopyOnWriteArrayList<String>()
        val speeds = CopyOnWriteArrayList<Float>()
        @Volatile var closed = false
        /** Holds generation after the first chunk of a sentence until released. */
        @Volatile var gate: CountDownLatch? = null
        @Volatile var failOn: String? = null
        val stoppedEarly = AtomicInteger()

        override fun generate(text: String, speed: Float, onSamples: (FloatArray) -> Boolean) {
            texts += text
            speeds += speed
            if (text == failOn) error("boom")
            if (!onSamples(FloatArray(500))) { stoppedEarly.incrementAndGet(); return }
            gate?.await(5, TimeUnit.SECONDS)
            if (!onSamples(FloatArray(500))) { stoppedEarly.incrementAndGet(); return }
        }

        override fun close() { closed = true }
    }

    /** The speaker: audio is "heard" only when the test calls [hear]. */
    private class FakeSink : AudioSink {
        private val lock = Any()
        private var written = 0L
        private var played = 0L
        @Volatile override var generation = 0
        val stops = AtomicInteger()
        val writes = CopyOnWriteArrayList<Int>()
        val releases = AtomicInteger()

        override val framesWritten: Long get() = synchronized(lock) { written }
        override fun framesPlayed(): Long = synchronized(lock) { minOf(played, written) }

        override fun write(samples: FloatArray, sampleRate: Int, generation: Int): Boolean = synchronized(lock) {
            if (generation != this.generation) return false
            written += samples.size
            writes += samples.size
            true
        }

        override fun stop() = synchronized(lock) { generation++; written = 0; played = 0; stops.incrementAndGet(); Unit }
        override fun release() { releases.incrementAndGet(); stop() }

        fun hear(frames: Long) = synchronized(lock) { played += frames }
        fun hearEverything() = synchronized(lock) { played = written }
    }

    private class FakeFocus : SpeechFocus {
        override var onLost: () -> Unit = {}
        val requests = AtomicInteger()
        val abandons = AtomicInteger()
        override fun request() { requests.incrementAndGet() }
        override fun abandon() { abandons.incrementAndGet() }
    }

    private class Heard : TtsEngine.Listener {
        val events = CopyOnWriteArrayList<String>()
        override fun onStart(utteranceId: String) { events += "start $utteranceId" }
        override fun onDone(utteranceId: String) { events += "done $utteranceId" }
        override fun onError(utteranceId: String) { events += "error $utteranceId" }
        override fun onInterrupted() { events += "interrupted" }
    }

    private val synth = FakeSynth()
    private val sink = FakeSink()
    private val focus = FakeFocus()
    private val heard = Heard()
    private val clock = AtomicLong(1_000_000)
    private val loads = AtomicInteger()
    private var handsFreeOn = false
    private var loadFails = false

    private fun tts(maxAhead: Int = 3) = SherpaTts(
        loadSynth = {
            loads.incrementAndGet()
            if (loadFails) error("no such file")
            synth
        },
        sink = sink,
        focus = focus,
        nowMs = { clock.get() },
        keepLoaded = { handsFreeOn },
        maxAhead = maxAhead,
    ).also { it.setListener(heard) }

    private val engines = mutableListOf<SherpaTts>()
    private fun newTts(maxAhead: Int = 3) = tts(maxAhead).also { engines += it }

    @After fun stopThreads() = engines.forEach { it.close() }

    private fun await(what: String, timeoutMs: Long = 4_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > until) throw AssertionError("Timed out waiting for $what. Heard: ${heard.events}, sink writes ${sink.writes}")
            Thread.sleep(5)
        }
    }

    /** Lets other threads act, then checks nothing more happened. */
    private fun settle() = Thread.sleep(250)

    // --- Order, and when things are announced ----------------------------------------------------------------

    @Test fun `sentences are spoken in order, and done means played, not generated`() {
        val tts = newTts()
        assertTrue(tts.speak("One.", "1-0", 1f))
        assertTrue(tts.speak("Two.", "1-1", 1f))

        await("both sentences generated") { synth.texts.size == 2 && sink.writes.size == 4 }
        assertEquals(listOf("One.", "Two."), synth.texts.toList())
        assertEquals("start 1-0 came when its samples reached the speaker", "start 1-0", heard.events.first())
        assertTrue("start of the second only once it reaches the speaker", "start 1-1" in heard.events)
        settle()
        assertFalse("generated and handed over, but not heard yet: not done", heard.events.any { it.startsWith("done") })

        sink.hear(500)   // half of the first sentence
        settle()
        assertFalse(heard.events.any { it.startsWith("done") })
        sink.hear(500)   // all of the first
        await("first sentence done") { "done 1-0" in heard.events }
        assertFalse("done 1-1" in heard.events)
        sink.hear(1000)
        await("second sentence done") { "done 1-1" in heard.events }
        assertEquals(listOf("start 1-0", "start 1-1", "done 1-0", "done 1-1").sorted(), heard.events.sorted())
        assertTrue("done 1-0 comes before done 1-1", heard.events.indexOf("done 1-0") < heard.events.indexOf("done 1-1"))
    }

    @Test fun `the speed is passed to the voice, larger meaning faster`() {
        val tts = newTts()
        tts.speak("Hi.", "1-0", 1.5f)
        await("generated") { synth.speeds.isNotEmpty() }
        assertEquals(1.5f, synth.speeds.first(), 0f)
    }

    // --- The queue limit -------------------------------------------------------------------------------------

    @Test fun `generation stays at most three sentences ahead of what was heard`() {
        val tts = newTts()
        listOf("A.", "B.", "C.", "D.", "E.").forEachIndexed { i, s -> tts.speak(s, "1-$i", 1f) }
        await("three generated") { synth.texts.size == 3 }
        settle()
        assertEquals("a fourth waits for one to be heard", listOf("A.", "B.", "C."), synth.texts.toList())

        sink.hear(1000)
        await("first heard") { "done 1-0" in heard.events }
        await("fourth generated") { synth.texts.size == 4 }
        settle()
        assertEquals(listOf("A.", "B.", "C.", "D."), synth.texts.toList())
    }

    // --- Stop ------------------------------------------------------------------------------------------------

    @Test fun `stop in the middle of a sentence drops everything, flushes the speaker and reports nothing`() {
        val tts = newTts()
        synth.gate = CountDownLatch(1)
        tts.speak("Long one.", "1-0", 1f)
        tts.speak("Never said.", "1-1", 1f)
        await("first chunk written and announced") { sink.writes.size == 1 && "start 1-0" in heard.events }

        tts.stop()
        assertTrue("the speaker was flushed", sink.stops.get() >= 1)
        assertTrue("the focus was given up", focus.abandons.get() >= 1)
        synth.gate!!.countDown()   // the voice goes on to its second chunk and finds it should stop
        await("generation told to stop") { synth.stoppedEarly.get() == 1 }
        settle()

        assertEquals("nothing but the start of the sentence that was cut", listOf("start 1-0"), heard.events.toList())
        assertEquals("the second sentence was never generated", listOf("Long one."), synth.texts.toList())
        assertEquals("only the first chunk reached the speaker", listOf(500), sink.writes.toList())
    }

    @Test fun `speech after a stop works, and nothing stale is played`() {
        val tts = newTts()
        tts.speak("Old.", "1-0", 1f)
        await("old written") { sink.writes.size == 2 }
        tts.stop()
        sink.writes.clear()

        tts.speak("New.", "2-0", 1f)
        await("new written") { sink.writes.size == 2 }
        sink.hearEverything()
        await("new done") { "done 2-0" in heard.events }
        assertFalse("the stopped sentence is never reported done", "done 1-0" in heard.events)
    }

    // --- Errors ----------------------------------------------------------------------------------------------

    @Test fun `a generation error is reported for that sentence only`() {
        val tts = newTts()
        synth.failOn = "Bad."
        tts.speak("Bad.", "1-0", 1f)
        tts.speak("Fine.", "1-1", 1f)
        await("error and start") { "error 1-0" in heard.events && "start 1-1" in heard.events }
        assertTrue("one bad sentence doesn't make the voice unavailable", tts.available.value)
        sink.hearEverything()
        await("fine one done") { "done 1-1" in heard.events }
    }

    @Test fun `a voice that won't load reports the sentence, says why in Settings and refuses more speech`() {
        loadFails = true
        val tts = newTts()
        assertTrue(tts.speak("Hello.", "1-0", 1f))
        await("error") { "error 1-0" in heard.events }
        await("unavailable") { !tts.available.value }
        assertEquals(SherpaTts.LOAD_PROBLEM, tts.problem.value)
        assertFalse(tts.speak("Again.", "1-1", 1f))

        loadFails = false
        tts.reset()   // a fresh download
        assertTrue(tts.available.value)
        assertNull(tts.problem.value)
        assertTrue(tts.speak("Again.", "2-0", 1f))
        await("start") { "start 2-0" in heard.events }
    }

    // --- Loading and unloading ---------------------------------------------------------------------------------

    @Test fun `the voice is loaded on the first sentence, not before, and once`() {
        val tts = newTts()
        assertEquals(0, loads.get())
        assertFalse(tts.isLoaded)
        tts.speak("One.", "1-0", 1f)
        tts.speak("Two.", "1-1", 1f)
        await("both generated") { synth.texts.size == 2 }
        assertEquals(1, loads.get())
        assertTrue(tts.isLoaded)
    }

    @Test fun `the voice is unloaded after two minutes without speech, and loaded again when needed`() {
        val tts = newTts()
        tts.speak("One.", "1-0", 1f)
        await("written") { sink.writes.size == 2 }
        sink.hearEverything()
        await("done") { "done 1-0" in heard.events }

        clock.addAndGet(60_000)
        tts.unloadIfIdle()
        assertTrue("one minute isn't enough", tts.isLoaded)
        clock.addAndGet(60_001)
        tts.unloadIfIdle()
        assertFalse(tts.isLoaded)
        assertTrue(synth.closed)

        synth.closed = false
        tts.speak("Two.", "2-0", 1f)
        await("loaded again") { loads.get() == 2 && sink.writes.size >= 3 }
    }

    @Test fun `the voice is not unloaded while hands-free is on, or while something is being said`() {
        val tts = newTts()
        tts.speak("One.", "1-0", 1f)
        await("written") { sink.writes.size == 2 }

        clock.addAndGet(10 * 60_000)
        tts.unloadIfIdle()
        assertTrue("a sentence that hasn't been heard keeps it", tts.isLoaded)

        sink.hearEverything()
        await("done") { "done 1-0" in heard.events }
        clock.addAndGet(10 * 60_000)
        handsFreeOn = true
        tts.unloadIfIdle()
        assertTrue("hands-free keeps it", tts.isLoaded)

        handsFreeOn = false
        tts.unloadIfIdle()
        assertFalse(tts.isLoaded)
    }

    // --- Audio focus -----------------------------------------------------------------------------------------

    @Test fun `focus is asked for when speaking, given up when idle, and losing it interrupts`() {
        val tts = newTts()
        tts.speak("One.", "1-0", 1f)
        assertTrue(focus.requests.get() >= 1)
        await("written") { sink.writes.size == 2 }
        sink.hearEverything()
        await("done") { "done 1-0" in heard.events }

        tts.idle()
        assertEquals(1, focus.abandons.get())
        assertTrue("the speaker is freed when nothing is left to say", sink.releases.get() >= 1)

        focus.onLost()
        assertEquals("interrupted", heard.events.last())
    }

    @Test fun `idle keeps the speaker while a sentence is still waiting`() {
        val tts = newTts()
        tts.speak("One.", "1-0", 1f)
        await("written") { sink.writes.size == 2 }
        tts.idle()
        assertEquals("not released while a sentence hasn't been heard", 0, sink.releases.get())
    }
}
