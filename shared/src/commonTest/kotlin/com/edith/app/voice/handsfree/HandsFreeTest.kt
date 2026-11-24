package com.edith.app.voice.handsfree

import com.edith.app.chat.engine.EngineState
import com.edith.app.settings.Settings
import com.edith.app.testing.FakeSettingsRepository
import com.edith.app.voice.capture.SpeechInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import com.edith.app.testing.assertEquals
import com.edith.app.testing.assertFalse
import com.edith.app.testing.assertNull
import com.edith.app.testing.assertTrue
import kotlin.test.Test

/** C14: the hands-free state machine with a fake wake word, recognizer, engine and voice. */
@OptIn(ExperimentalCoroutinesApi::class)
class HandsFreeTest {

    private class FakeWake : WakeListener {
        var listening = false
        var starts = 0
        var opens = true
        var onWake: (() -> Unit)? = null
        var onFailed: (() -> Unit)? = null
        var threshold = 0f
        override fun start(threshold: Float, onWake: () -> Unit, onFailed: () -> Unit): Boolean {
            if (!opens) return false
            listening = true; starts++; this.threshold = threshold; this.onWake = onWake; this.onFailed = onFailed
            return true
        }
        override fun stop() { listening = false }
        fun hear() = onWake!!.invoke()
    }

    private class FakeRecognizer : SpeechInput {
        var listener: SpeechInput.Listener? = null
        var cancels = 0
        override val isAvailable = true
        override fun start(listener: SpeechInput.Listener) { this.listener = listener }
        override fun stop() {}
        override fun cancel() { cancels++; listener = null }
        val active get() = listener != null
        fun say(text: String) = listener!!.let { listener = null; it.onFinal(text) }
        fun fail(message: String) = listener!!.let { listener = null; it.onError(message) }
    }

    private inner class Kit(scope: CoroutineScope, settings: Settings = Settings()) {
        val wake = FakeWake()
        val recognizer = FakeRecognizer()
        val engine = MutableStateFlow<EngineState>(EngineState.Idle)
        val speaking = MutableStateFlow<Long?>(null)
        val sent = mutableListOf<String>()
        var beeps = 0
        var speechStops = 0
        var replyCancels = 0
        val said = mutableListOf<String>()
        val handsFree = HandsFree(
            wake = wake,
            recognizer = recognizer,
            engineState = engine,
            speaking = speaking,
            stopSpeaking = { speechStops++; speaking.value = null },
            cancelReply = { replyCancels++; engine.value = EngineState.Idle },
            send = { sent += it; engine.value = EngineState.Working(1, "Thinking…", "") },
            earcon = { beeps++ },
            settings = FakeSettingsRepository(settings),
            scope = scope,
            phrase = "Hey Edith",
            say = { said += it },
        )
        val state get() = handsFree.state.value

        /** Edith writes her reply and speaks it, then finishes. */
        fun replyAndSpeak() {
            speaking.value = -1L
            engine.value = EngineState.Idle
            speaking.value = 42L
        }
        fun doneSpeaking() { speaking.value = null }
    }

    @Test fun `wake word question spoken answer then a follow-up without the wake word`() = runTest(UnconfinedTestDispatcher()) {
        val k = Kit(backgroundScope)
        k.handsFree.start()
        assertEquals(HandsFree.State.Listening, k.state)
        assertTrue(k.wake.listening)
        assertEquals(0.5f, k.wake.threshold, 0.001f)   // sensitivity 0.5

        k.wake.hear()
        assertEquals(1, k.beeps)
        assertFalse("the wake listener lets go of the mic first", k.wake.listening)
        assertTrue(k.recognizer.active)
        assertEquals(HandsFree.State.Capturing(), k.state)

        k.recognizer.say("what's the weather in Boston")
        assertEquals(listOf("what's the weather in Boston"), k.sent)
        assertEquals(HandsFree.State.Thinking, k.state)

        k.replyAndSpeak()
        assertEquals(HandsFree.State.Speaking, k.state)
        assertTrue("listening for barge-in while she speaks", k.wake.listening)

        k.doneSpeaking()
        assertEquals(HandsFree.State.Capturing(followUp = true), k.state)
        assertFalse(k.wake.listening)
        assertEquals("no beep for a follow-up", 1, k.beeps)

        k.recognizer.say("and tomorrow?")
        assertEquals(listOf("what's the weather in Boston", "and tomorrow?"), k.sent)
    }

    @Test fun `silence in the follow-up window goes back to the wake word`() = runTest(UnconfinedTestDispatcher()) {
        val k = Kit(backgroundScope, Settings(followUpSeconds = 5))
        k.handsFree.start()
        k.wake.hear(); k.recognizer.say("hello")
        k.replyAndSpeak(); k.doneSpeaking()
        assertEquals(HandsFree.State.Capturing(followUp = true), k.state)

        advanceTimeBy(4_900)
        assertTrue(k.state is HandsFree.State.Capturing)
        advanceTimeBy(200)
        assertEquals(HandsFree.State.Listening, k.state)
        assertTrue(k.wake.listening)
        assertFalse(k.recognizer.active)
    }

    @Test fun `a recognizer that gives up early is restarted until the follow-up window ends`() = runTest(UnconfinedTestDispatcher()) {
        val k = Kit(backgroundScope, Settings(followUpSeconds = 8))
        k.handsFree.start()
        k.wake.hear(); k.recognizer.say("hello")
        k.replyAndSpeak(); k.doneSpeaking()

        k.recognizer.say("")   // silence: Android's recognizer ends after a few seconds
        assertTrue(k.state is HandsFree.State.Capturing)
        advanceTimeBy(300)
        assertTrue("listening again", k.recognizer.active)
        k.recognizer.say("and tomorrow")
        assertEquals(listOf("hello", "and tomorrow"), k.sent)

        // And after the window, an empty result goes back to the wake word.
        k.replyAndSpeak(); k.doneSpeaking()
        advanceTimeBy(8_100)
        assertEquals(HandsFree.State.Listening, k.state)
    }

    @Test fun `talking in the follow-up window keeps it open past the timer`() = runTest(UnconfinedTestDispatcher()) {
        val k = Kit(backgroundScope, Settings(followUpSeconds = 3))
        k.handsFree.start()
        k.wake.hear(); k.recognizer.say("hello")
        k.replyAndSpeak(); k.doneSpeaking()
        k.recognizer.listener!!.onPartial("and")
        advanceTimeBy(5_000)
        assertEquals(HandsFree.State.Capturing(partial = "and", followUp = true), k.state)
        k.recognizer.say("and tomorrow")
        assertEquals(listOf("hello", "and tomorrow"), k.sent)
    }

    @Test fun `follow-ups off goes straight back to the wake word`() = runTest(UnconfinedTestDispatcher()) {
        val k = Kit(backgroundScope, Settings(followUpSeconds = 0))
        k.handsFree.start()
        k.wake.hear(); k.recognizer.say("hello")
        k.replyAndSpeak(); k.doneSpeaking()
        assertEquals(HandsFree.State.Listening, k.state)
    }

    @Test fun `nothing heard after the wake word quietly goes back to listening`() = runTest(UnconfinedTestDispatcher()) {
        val k = Kit(backgroundScope)
        k.handsFree.start()
        k.wake.hear()
        k.recognizer.say("")
        assertEquals(HandsFree.State.Listening, k.state)
        assertTrue(k.sent.isEmpty())
        assertNull(k.handsFree.notice.value)
    }

    @Test fun `three recognizer errors in a row turn hands-free off with a notice`() = runTest(UnconfinedTestDispatcher()) {
        val k = Kit(backgroundScope)
        k.handsFree.start()
        repeat(2) {
            k.wake.hear(); k.recognizer.fail("The microphone didn't work — try again.")
            assertEquals(HandsFree.State.Listening, k.state)
        }
        k.wake.hear(); k.recognizer.fail("The microphone didn't work — try again.")
        assertEquals(HandsFree.State.Off, k.state)
        assertEquals("Hands-free stopped: The microphone didn't work — try again.", k.handsFree.notice.value)
        assertFalse(k.wake.listening)
    }

    @Test fun `a success resets the error count`() = runTest(UnconfinedTestDispatcher()) {
        val k = Kit(backgroundScope, Settings(followUpSeconds = 0))
        k.handsFree.start()
        repeat(2) { k.wake.hear(); k.recognizer.fail("x") }
        k.wake.hear(); k.recognizer.say("hello"); k.replyAndSpeak(); k.doneSpeaking()
        repeat(2) { k.wake.hear(); k.recognizer.fail("x") }
        assertEquals(HandsFree.State.Listening, k.state)
    }

    @Test fun `barge-in - the wake word while Edith speaks stops her and listens`() = runTest(UnconfinedTestDispatcher()) {
        val k = Kit(backgroundScope)
        k.handsFree.start()
        k.wake.hear(); k.recognizer.say("tell me a long story")
        k.speaking.value = -1L   // still streaming and speaking
        assertEquals(HandsFree.State.Speaking, k.state)

        k.wake.hear()
        assertEquals(1, k.speechStops)
        assertEquals("the reply still being written is stopped too", 1, k.replyCancels)
        assertEquals(HandsFree.State.Capturing(), k.state)
        assertEquals(2, k.beeps)
        k.engine.value = EngineState.Idle   // the reply finishes in the background; nothing should change
        assertEquals(HandsFree.State.Capturing(), k.state)
    }

    @Test fun `stop and pause let go of the microphone and resume listens again`() = runTest(UnconfinedTestDispatcher()) {
        val k = Kit(backgroundScope)
        k.handsFree.start()
        k.wake.hear()
        k.handsFree.pause()
        assertEquals(HandsFree.State.Paused, k.state)
        assertFalse(k.wake.listening)
        assertFalse(k.recognizer.active)

        k.handsFree.resume()
        assertEquals(HandsFree.State.Listening, k.state)
        assertTrue(k.wake.listening)

        k.handsFree.stop(HandsFree.CALL)
        assertEquals(HandsFree.State.Off, k.state)
        assertFalse(k.wake.listening)
        assertEquals(HandsFree.CALL, k.handsFree.notice.value)
    }

    @Test fun `a busy engine isn't interrupted`() = runTest(UnconfinedTestDispatcher()) {
        val k = Kit(backgroundScope)
        k.handsFree.start()
        k.engine.value = EngineState.Working(1, "Thinking…", "")   // a typed message is being answered
        k.wake.hear(); k.recognizer.say("hello")
        assertTrue(k.sent.isEmpty())
        assertEquals(HandsFree.BUSY, k.handsFree.notice.value)
        assertEquals(HandsFree.State.Listening, k.state)
    }

    @Test fun `a microphone that won't open or fails turns hands-free off`() = runTest(UnconfinedTestDispatcher()) {
        val k = Kit(backgroundScope)
        k.wake.opens = false
        k.handsFree.start()
        assertEquals(HandsFree.State.Off, k.state)
        assertEquals(HandsFree.MIC_UNAVAILABLE, k.handsFree.notice.value)

        k.wake.opens = true
        k.handsFree.start()
        assertNull(k.handsFree.notice.value)
        k.wake.onFailed!!.invoke()
        assertEquals(HandsFree.State.Off, k.state)
        assertEquals(HandsFree.MIC_UNAVAILABLE, k.handsFree.notice.value)
    }

    @Test fun `the debug hook's sentence replaces what the recognizer heard`() = runTest(UnconfinedTestDispatcher()) {
        val k = Kit(backgroundScope)
        k.handsFree.start()
        k.handsFree.heard("ignored while not capturing")
        assertTrue(k.sent.isEmpty())
        k.wake.hear()
        k.handsFree.heard("what's the weather")
        assertEquals(listOf("what's the weather"), k.sent)
        assertEquals(1, k.recognizer.cancels.coerceAtMost(1))
    }

    @Test fun `sensitivity maps to openWakeWord's threshold`() {
        assertEquals(0.5f, WakeWordDetector.threshold(0.5f), 0.001f)
        assertEquals(0.9f, WakeWordDetector.threshold(0f), 0.001f)
        assertEquals(0.1f, WakeWordDetector.threshold(1f), 0.001f)
        assertTrue(WakeWordDetector.threshold(0.8f) < WakeWordDetector.threshold(0.2f))
    }

    /** C25, D10: a card can't be approved by voice; she says to tap it, once per card, and waits for the tap. */
    @Test fun `a waiting card is announced - tap it on the screen`() = runTest(UnconfinedTestDispatcher()) {
        val k = Kit(backgroundScope)
        k.handsFree.start()
        k.wake.hear()
        k.recognizer.say("unlock the back door")
        val card = com.edith.app.chat.engine.ConfirmationRequest("Unlock: Back door?", confirmLabel = "Unlock", id = "c1", screenOnly = true)
        k.engine.value = EngineState.AwaitingConfirmation(1, card, "")
        k.engine.value = EngineState.AwaitingConfirmation(1, card, "")   // the same card again: said once
        assertEquals(listOf("For safety, this needs a tap on the screen: tap Unlock to go ahead."), k.said)
        assertTrue("it waits; nothing is approved by voice", k.state == HandsFree.State.Thinking)

        k.engine.value = EngineState.AwaitingConfirmation(1, card.copy(id = "c2", screenOnly = false, confirmLabel = "Close"), "")
        assertEquals("Tap Close on the screen to go ahead, or Cancel.", k.said.last())
    }
}
