package com.edith.app.voice.speech

import com.edith.app.settings.SpeechVoice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C36 §4: which voice speaks, for every combination, and how [ChoosingTts] switches between two engines. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChoosingTtsTest {

    // --- The rule ------------------------------------------------------------------------------------------

    @Test fun `every combination of the setting, the phone's voice and Edith's own voice`() {
        val expected = mapOf(
            // (setting, phone, own) -> voice
            Triple(SpeechVoice.Auto, true, true) to ActiveVoice.Phone,
            Triple(SpeechVoice.Auto, true, false) to ActiveVoice.Phone,
            Triple(SpeechVoice.Auto, false, true) to ActiveVoice.Own,
            Triple(SpeechVoice.Auto, false, false) to ActiveVoice.None,
            Triple(SpeechVoice.Phone, true, true) to ActiveVoice.Phone,
            Triple(SpeechVoice.Phone, true, false) to ActiveVoice.Phone,
            Triple(SpeechVoice.Phone, false, true) to ActiveVoice.None,
            Triple(SpeechVoice.Phone, false, false) to ActiveVoice.None,
            Triple(SpeechVoice.Own, true, true) to ActiveVoice.Own,
            Triple(SpeechVoice.Own, true, false) to ActiveVoice.None,
            Triple(SpeechVoice.Own, false, true) to ActiveVoice.Own,
            Triple(SpeechVoice.Own, false, false) to ActiveVoice.None,
        )
        assertEquals("every combination is listed", SpeechVoice.entries.size * 4, expected.size)
        expected.forEach { (input, voice) ->
            assertEquals("$input", voice, chooseVoice(input.first, phoneAvailable = input.second, ownReady = input.third))
        }
    }

    @Test fun `the problem text says what to do`() {
        assertEquals(NO_VOICE_AUTO, noVoiceProblem(SpeechVoice.Auto, "Speech isn't available"))
        assertEquals("Speech isn't available", noVoiceProblem(SpeechVoice.Phone, "Speech isn't available"))
        assertEquals(OWN_NOT_READY, noVoiceProblem(SpeechVoice.Own, null))
        assertEquals("Your phone has no voice. Download Edith's own voice in Settings → Voice.", NO_VOICE_AUTO)
    }

    // --- The engine ----------------------------------------------------------------------------------------

    private class FakeTts(available: Boolean = true, problem: String? = null) : TtsEngine {
        override val available = MutableStateFlow(available)
        override val problem = MutableStateFlow(problem)
        var heardBy: TtsEngine.Listener? = null
        val spoken = mutableListOf<String>()
        val events = mutableListOf<String>()
        var accept = true
        override fun setListener(listener: TtsEngine.Listener) { heardBy = listener }
        override fun speak(text: String, utteranceId: String, rate: Float): Boolean {
            if (!accept) return false
            spoken += "$utteranceId:$text@$rate"
            events += "speak"
            return true
        }
        override fun stop() { events += "stop" }
        override fun idle() { events += "idle" }
    }

    private class Heard : TtsEngine.Listener {
        val events = mutableListOf<String>()
        override fun onStart(utteranceId: String) { events += "start $utteranceId" }
        override fun onDone(utteranceId: String) { events += "done $utteranceId" }
        override fun onError(utteranceId: String) { events += "error $utteranceId" }
        override fun onInterrupted() { events += "interrupted" }
    }

    private class Rig(
        scope: TestScope,
        val phone: FakeTts = FakeTts(),
        val own: FakeTts = FakeTts(),
        initial: SpeechVoice = SpeechVoice.Auto,
        ownReady: Boolean = false,
    ) {
        val setting = MutableStateFlow(initial)
        val ownReady = MutableStateFlow(ownReady)
        val heard = Heard()
        val tts = ChoosingTts(phone, own, setting, this.ownReady, scope.backgroundScope).also { it.setListener(heard) }
    }

    @Test fun `auto uses the phone's voice and forwards speech and idle`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this, ownReady = true)
        advanceUntilIdle()
        assertTrue(rig.tts.available.value)
        assertNull(rig.tts.problem.value)
        assertTrue(rig.tts.speak("Hello.", "1-0", 1.2f))
        assertEquals(listOf("1-0:Hello.@1.2"), rig.phone.spoken)
        assertTrue(rig.own.spoken.isEmpty())
        rig.phone.heardBy!!.onStart("1-0")
        rig.phone.heardBy!!.onDone("1-0")
        assertEquals(listOf("start 1-0", "done 1-0"), rig.heard.events)
        rig.tts.idle()
        assertTrue("idle reaches the engine that holds the audio focus", "idle" in rig.phone.events)
    }

    @Test fun `with no phone voice and none downloaded nothing speaks, and the problem says to download`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this, phone = FakeTts(available = false, problem = "Speech isn't available"))
        advanceUntilIdle()
        assertFalse(rig.tts.available.value)
        assertEquals(NO_VOICE_AUTO, rig.tts.problem.value)
        assertFalse(rig.tts.speak("Hello.", "1-0", 1f))
        assertTrue(rig.phone.spoken.isEmpty() && rig.own.spoken.isEmpty())
    }

    @Test fun `a finished download switches in auto, and Edith's own voice speaks`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this, phone = FakeTts(available = false))
        advanceUntilIdle()
        assertFalse(rig.tts.available.value)

        rig.ownReady.value = true
        advanceUntilIdle()
        assertTrue(rig.tts.available.value)
        assertNull(rig.tts.problem.value)
        assertTrue(rig.tts.speak("Hello.", "2-0", 1f))
        assertEquals(listOf("2-0:Hello.@1.0"), rig.own.spoken)
    }

    @Test fun `switching stops the old voice first and ends speech that was in progress`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this, ownReady = true)
        advanceUntilIdle()
        rig.tts.speak("One.", "1-0", 1f)
        rig.tts.speak("Two.", "1-1", 1f)
        rig.phone.heardBy!!.onDone("1-0")

        rig.setting.value = SpeechVoice.Own
        advanceUntilIdle()

        assertEquals("the phone's voice was stopped", "stop", rig.phone.events.last())
        assertEquals(listOf("interrupted"), rig.heard.events.filter { it == "interrupted" })
        assertTrue(rig.tts.speak("Three.", "2-0", 1f))
        assertEquals(listOf("2-0:Three.@1.0"), rig.own.spoken)
    }

    @Test fun `switching while nothing is being said doesn't interrupt anything`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this, ownReady = true)
        advanceUntilIdle()
        rig.setting.value = SpeechVoice.Own
        advanceUntilIdle()
        assertEquals(emptyList<String>(), rig.heard.events)
    }

    @Test fun `the own voice setting without a download is unavailable with its own problem`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this, initial = SpeechVoice.Own)
        advanceUntilIdle()
        assertFalse(rig.tts.available.value)
        assertEquals(OWN_NOT_READY, rig.tts.problem.value)
    }

    @Test fun `Edith's own voice failing to start makes it unavailable`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this, phone = FakeTts(available = false), ownReady = true)
        advanceUntilIdle()
        assertTrue(rig.tts.available.value)
        rig.own.available.value = false
        advanceUntilIdle()
        assertFalse(rig.tts.available.value)
    }

    @Test fun `a refused sentence isn't counted as speech`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this, ownReady = true)
        advanceUntilIdle()
        rig.phone.accept = false
        assertFalse(rig.tts.speak("Hello.", "1-0", 1f))
        rig.phone.accept = true
        rig.setting.value = SpeechVoice.Own
        advanceUntilIdle()
        assertEquals("nothing was in flight, so nothing is interrupted", emptyList<String>(), rig.heard.events)
    }

    @Test fun `the phone setting never uses Edith's own voice`() = runTest(UnconfinedTestDispatcher()) {
        val rig = Rig(this, phone = FakeTts(available = false, problem = "Speech isn't available"), initial = SpeechVoice.Phone, ownReady = true)
        advanceUntilIdle()
        assertFalse(rig.tts.available.value)
        assertEquals("Speech isn't available", rig.tts.problem.value)
    }
}
