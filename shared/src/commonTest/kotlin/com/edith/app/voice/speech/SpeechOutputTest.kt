package com.edith.app.voice.speech

import com.edith.app.settings.Settings
import com.edith.app.testing.FakeSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.edith.app.testing.assertEquals
import com.edith.app.testing.assertNull
import com.edith.app.testing.assertTrue
import kotlin.test.Test

/** C13: sentences from a stream, text fit for speech, and the voice's state (with a fake text-to-speech engine). */
class SpeechOutputTest {

    // --- SentenceChunker ------------------------------------------------------------------------------------

    private fun chunk(vararg deltas: String): List<String> {
        val c = SentenceChunker()
        return deltas.flatMap { c.add(it) } + c.flush()
    }

    @Test fun `sentences come out as soon as they end even when split across deltas`() {
        val c = SentenceChunker()
        assertEquals(emptyList<String>(), c.add("It's sunny to"))
        assertEquals(emptyList<String>(), c.add("day.")) // the next character decides
        assertEquals(listOf("It's sunny today."), c.add(" Bring"))
        assertEquals(listOf("Bring sunglasses!"), c.add(" sunglasses! Is that all?"))
        assertEquals(listOf("Is that all?"), c.flush())
    }

    @Test fun `abbreviations decimals and times don't end a sentence`() {
        assertEquals(
            listOf("Dr. Smith said it's 3.5 degrees at 5 p.m. on Main St. today.", "Then rain, e.g. showers."),
            chunk("Dr. Smith said it's 3.5 degrees at 5 p.m. on Main St. today. Then rain, e.g. showers."),
        )
    }

    @Test fun `ellipses question and exclamation marks end sentences`() {
        assertEquals(
            listOf("Well... I think so!", "Are you sure?", "Yes, quite sure…", "Okay."),
            chunk("Well... I think so! Are you sure? Yes, quite sure… Okay."),
        )
    }

    @Test fun `a long first sentence is cut at a pause after 25 characters`() {
        val long = "The forecast for Fowlerville tomorrow afternoon looks mostly cloudy, with a chance of light rain " +
            "around three o'clock and winds from the west"
        val c = SentenceChunker()
        val first = c.add(long)
        assertEquals(listOf("The forecast for Fowlerville tomorrow afternoon looks mostly cloudy,"), first)
        assertEquals(listOf("with a chance of light rain around three o'clock and winds from the west"), c.flush())
    }

    @Test fun `a short sentence isn't cut and commas in numbers aren't pauses`() {
        assertEquals(listOf("It costs 1,000 dollars, give or take."), chunk("It costs 1,000 dollars, give or take."))
        val long = "The new umbrella from the shop downtown costs 1,000 dollars or so in total for everyone involved " +
            "here, give or take."
        assertEquals(
            listOf("The new umbrella from the shop downtown costs 1,000 dollars or so in total for everyone involved here,", "give or take."),
            chunk(long),
        )
    }

    @Test fun `one-word pieces join the next sentence and lines end sentences`() {
        assertEquals(listOf("Sure. Here's the plan:", "1. Pack an umbrella", "2. Leave early"), chunk("Sure. Here's the plan:\n1. Pack an umbrella\n2. Leave early"))
    }

    @Test fun `the trailing remainder comes out at the end`() {
        val c = SentenceChunker()
        assertEquals(listOf("First one."), c.add("First one. And the rest"))
        assertEquals(listOf("And the rest"), c.flush())
        assertEquals(emptyList<String>(), c.flush())
    }

    @Test fun `Edith's files are skipped even with tags split across deltas`() {
        assertEquals(
            listOf("Here's your list.", "Anything else?"),
            chunk("Here's your list. <fi", "le name=\"list.txt\">milk. eggs. bread.</fi", "le> Anything else?"),
        )
        assertEquals(listOf("Here it is."), chunk("Here it is. <file name=\"a.md\">never closed. still file."))
    }

    // --- SpeechText.clean -----------------------------------------------------------------------------------

    @Test fun `markdown links addresses and emoji are removed`() {
        assertEquals("Weather: sunny and warm.", SpeechText.clean("## **Weather**: _sunny_ and `warm`. ☀️"))
        assertEquals("See the forecast for details.", SpeechText.clean("See [the forecast](https://open-meteo.com/en) for details."))
        assertEquals("More at a link today.", SpeechText.clean("More at https://example.com/a.b?c=1 today."))
        assertEquals("Pack an umbrella", SpeechText.clean("- Pack an umbrella"))
        assertEquals("Quoted text", SpeechText.clean("> Quoted text"))
        assertEquals("Thumbs up", SpeechText.clean("Thumbs 👍🏽 up"))
        assertEquals("Done.", SpeechText.clean("Done. <file name=\"x.txt\">secret</file>"))
    }

    // --- SpeechOutput ---------------------------------------------------------------------------------------

    private class FakeTts : TtsEngine {
        val spoken = mutableListOf<Pair<String, String>>()   // id to text
        var stops = 0
        var idles = 0
        var rate = 0f
        lateinit var callbacks: TtsEngine.Listener
        override val available: StateFlow<Boolean> = MutableStateFlow(true)
        override val problem: StateFlow<String?> = MutableStateFlow(null)
        override fun setListener(listener: TtsEngine.Listener) { callbacks = listener }
        override fun speak(text: String, utteranceId: String, rate: Float): Boolean { spoken += utteranceId to text; this.rate = rate; return true }
        override fun stop() { stops++ }
        override fun idle() { idles++ }
        fun texts() = spoken.map { it.second }
        fun done(index: Int) = callbacks.onDone(spoken[index].first)
    }

    private fun output(settings: Settings = Settings()): Pair<SpeechOutput, FakeTts> {
        val tts = FakeTts()
        return SpeechOutput(tts, FakeSettingsRepository(settings), CoroutineScope(Dispatchers.Unconfined)) to tts
    }

    @Test fun `a streaming reply is spoken sentence by sentence in order then tagged with its message`() {
        val (speech, tts) = output(Settings(speechRate = 1.3f))
        speech.onDelta(1, "Hello there. How")
        assertEquals(listOf("Hello there."), tts.texts())
        assertEquals(SpeechOutput.LIVE_REPLY, speech.speakingMessageId.value)
        speech.onDelta(1, " are you? I'm")
        speech.onDelta(1, " **fine**")
        speech.onFinished(1, 42, "Hello there. How are you? I'm fine")
        assertEquals(listOf("Hello there.", "How are you?", "I'm fine"), tts.texts())
        assertEquals(1.3f, tts.rate)
        assertEquals(42L, speech.speakingMessageId.value)

        tts.done(0); tts.done(1)
        assertEquals(42L, speech.speakingMessageId.value)
        tts.done(2)
        assertNull(speech.speakingMessageId.value)
        assertTrue(tts.idles > 0)
    }

    @Test fun `stop clears the state and a late callback from stopped speech doesn't clear newer speech`() {
        val (speech, tts) = output()
        speech.play(7, "First message. It has two sentences.")
        assertEquals(7L, speech.speakingMessageId.value)
        speech.stop()
        assertNull(speech.speakingMessageId.value)
        assertEquals(1, tts.stops)

        speech.play(8, "Second message.")
        tts.done(0); tts.done(1)   // late callbacks from message 7
        assertEquals(8L, speech.speakingMessageId.value)
        tts.done(2)
        assertNull(speech.speakingMessageId.value)
    }

    @Test fun `with speak replies off nothing is spoken automatically but Play works`() {
        val (speech, tts) = output(Settings(speakReplies = false))
        speech.onDelta(1, "Hello there. More.")
        speech.onFinished(1, 5, "Hello there. More.")
        assertEquals(emptyList<String>(), tts.texts())
        assertNull(speech.speakingMessageId.value)

        speech.play(5, "Hello there. More.")
        assertEquals(listOf("Hello there.", "More."), tts.texts())
        assertEquals(5L, speech.speakingMessageId.value)
    }

    @Test fun `stopping a streaming reply keeps the rest of it quiet and the next reply speaks again`() {
        val (speech, tts) = output()
        speech.onDelta(1, "One sentence here. ")
        speech.stop()
        speech.onDelta(1, "Another sentence here. ")
        speech.onFinished(1, 3, "…")
        assertEquals(listOf("One sentence here."), tts.texts())
        assertNull(speech.speakingMessageId.value)

        speech.onDelta(1, "A new reply. ")
        assertEquals(listOf("One sentence here.", "A new reply."), tts.texts())
    }

    @Test fun `a cancelled or failed reply stops and text dropped before a tool call isn't spoken`() {
        val (speech, tts) = output()
        speech.onDelta(1, "Let me check the weather. One mom")
        speech.onReset(1)
        speech.onDelta(1, "It's sunny. ")
        speech.onCancelled(1)
        assertEquals(listOf("Let me check the weather.", "It's sunny."), tts.texts())
        assertNull(speech.speakingMessageId.value)
        assertEquals(1, tts.stops)

        speech.onDelta(2, "Partial answer. ")
        speech.onFailed(2)
        assertNull(speech.speakingMessageId.value)
        assertEquals(2, tts.stops)
    }

    @Test fun `losing audio focus stops speech`() {
        val (speech, tts) = output()
        speech.play(9, "A long reply to read.")
        tts.callbacks.onInterrupted()
        assertNull(speech.speakingMessageId.value)
        assertEquals(1, tts.stops)
    }

    @Test fun `the speed setting is kept within range`() {
        val (speech, tts) = output(Settings(speechRate = 3f))
        speech.play(1, "Too fast for anyone.")
        assertEquals(SpeechOutput.MAX_RATE, tts.rate)
    }
}
