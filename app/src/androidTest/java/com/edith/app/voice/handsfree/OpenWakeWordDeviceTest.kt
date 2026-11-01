package com.edith.app.voice.handsfree

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * C14: the real openWakeWord models through ONNX Runtime on the device (D4), with the stand-in "Hey Jarvis" model.
 * The WAVs are Windows text-to-speech at 16 kHz mono (`androidTest/assets/handsfree`). This checks the pipeline
 * (mel → embedding → wake word, openWakeWord's numbers); real-room accuracy can only be judged on a phone.
 */
@RunWith(AndroidJUnit4::class)
class OpenWakeWordDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val detector = OpenWakeWord(app, WakeWordModel("${WakeWordModel.DIR}/hey_jarvis_v0.1.onnx", "Hey Jarvis", isStandIn = true))

    @After fun tearDown() = detector.close()

    @Test fun theWakeWordScoresHighAndOtherSpeechDoesNot() {
        val threshold = OpenWakeWord.threshold(0.5f)
        for (wav in listOf("hey_jarvis.wav", "hey_jarvis_f.wav")) {
            val best = bestScore(wav)
            assertTrue("$wav scored $best", best >= threshold)
        }
        val other = bestScore("other.wav")
        assertTrue("other speech scored $other", other < 0.1f)
    }

    @Test fun silenceScoresZeroAndScoringIsFastEnoughForRealTime() {
        detector.reset()
        val silence = ShortArray(WakeWordDetector.CHUNK_SAMPLES)
        val started = System.nanoTime()
        var best = 0f
        repeat(50) { best = maxOf(best, detector.score(silence)) }   // 4 s of audio
        val msPerChunk = (System.nanoTime() - started) / 1_000_000.0 / 50
        assertTrue("silence scored $best", best < 0.1f)
        assertTrue("$msPerChunk ms per 80 ms chunk", msPerChunk < 40.0)   // half of real time, on the emulator
    }

    private fun bestScore(asset: String): Float {
        detector.reset()
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("handsfree/$asset").use { it.readBytes() }
        val data = (12 until bytes.size - 8).first { String(bytes, it, 4, Charsets.US_ASCII) == "data" } + 8
        val pcm = ByteBuffer.wrap(bytes, data, bytes.size - data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(pcm.remaining() + WakeWordDetector.SAMPLE_RATE).also { pcm.get(it, 0, pcm.remaining()) }
        var best = 0f
        val chunk = ShortArray(WakeWordDetector.CHUNK_SAMPLES)
        var at = 0
        while (at + chunk.size <= samples.size) {
            samples.copyInto(chunk, 0, at, at + chunk.size)
            best = maxOf(best, detector.score(chunk))
            at += chunk.size
        }
        return best
    }
}
