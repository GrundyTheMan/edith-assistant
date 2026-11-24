package com.edith.app.voice.capture

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.net.HttpClients
import com.edith.app.ollama.KtorOllamaClient
import com.edith.app.ollama.OllamaDtos
import com.edith.app.settings.Settings
import com.edith.app.testing.FakeSettingsRepository
import com.edith.app.testing.testDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** C12: the WAV format, the transcription request, and the voice memo paths (sent, too short, cancelled, silence, fallback). */
class VoiceInputTest {
    @get:Rule val tmp = TemporaryFolder()

    // --- WAV --------------------------------------------------------------------------------------------------

    @Test fun `the WAV header describes 16 kHz mono 16-bit PCM and the real data size`() {
        val file = tmp.newFile("a.wav")
        val writer = WavWriter(file)
        val samples = ShortArray(16_000) { (it % 100 - 50).toShort() }   // one second
        writer.write(samples, samples.size)
        writer.write(samples, 8_000)                                     // half a second more
        writer.close()

        val bytes = file.readBytes()
        val h = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        assertEquals("RIFF", tag(0)); assertEquals("WAVE", tag(8)); assertEquals("fmt ", tag(12)); assertEquals("data", tag(36))
        val data = 24_000 * 2
        assertEquals(36 + data, h.getInt(4))
        assertEquals(16, h.getInt(16))           // fmt chunk size
        assertEquals(1, h.getShort(20).toInt())  // PCM
        assertEquals(1, h.getShort(22).toInt())  // mono
        assertEquals(16_000, h.getInt(24))
        assertEquals(32_000, h.getInt(28))       // byte rate
        assertEquals(2, h.getShort(32).toInt())  // block align
        assertEquals(16, h.getShort(34).toInt()) // bits
        assertEquals(data, h.getInt(40))
        assertEquals(44 + data, bytes.size)
        assertEquals(1_500, writer.durationMs)
        assertEquals("samples are little-endian", samples[1], h.getShort(44 + 2))
    }

    @Test fun `the level meter follows loudness`() {
        assertEquals(0f, AudioRecorder.levelOf(ShortArray(160), 160))
        val quiet = AudioRecorder.levelOf(ShortArray(160) { if (it % 2 == 0) 30 else -30 }, 160)
        val loud = AudioRecorder.levelOf(ShortArray(160) { if (it % 2 == 0) 20_000 else -20_000 }, 160)
        assertTrue("quiet $quiet < loud $loud", quiet < loud)
        assertTrue(loud in 0.9f..1f)
    }

    @Test fun `leftover recordings are deleted`() {
        val dir = tmp.newFolder("recordings")
        File(dir, "x.wav").writeText("x"); File(dir, "y.wav").writeText("y")
        AudioRecorder.deleteLeftovers(dir)
        assertEquals(0, dir.listFiles()!!.size)
    }

    // --- Ollama transcription ---------------------------------------------------------------------------------

    @Test fun `transcription sends the WAV in images with the exact prompt, and NO_SPEECH means empty`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val client = KtorOllamaClient(HttpClients.over(OkHttpClient()), OllamaDtos.json, AppDispatchers(Dispatchers.Unconfined, Dispatchers.IO, Dispatchers.Default))
            val settings = FakeSettingsRepository(Settings(serverUrl = server.url("/").toString(), model = "gemma4:12b-it-q8_0"))
            val transcriber = OllamaTranscriber(client, settings, testDispatchers())
            val audio = wav("speech.wav", 1_000)
            server.enqueue(chatReply("  Is there a Pistons game tonight?\n"))
            assertEquals(Outcome.Success("Is there a Pistons game tonight?"), transcriber.transcribe(audio))

            val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            assertEquals("gemma4:12b-it-q8_0", body["model"]!!.jsonPrimitive.content)
            assertEquals("false", body["stream"]!!.jsonPrimitive.content)
            val message = body["messages"]!!.jsonArray.single().jsonObject
            assertEquals("user", message["role"]!!.jsonPrimitive.content)
            assertEquals(OllamaTranscriber.PROMPT, message["content"]!!.jsonPrimitive.content)
            assertArrayEquals(java.io.File(audio.path).readBytes(), Base64.getDecoder().decode(message["images"]!!.jsonArray.single().jsonPrimitive.content))

            server.enqueue(chatReply("[NO_SPEECH]"))
            assertEquals(Outcome.Success(""), transcriber.transcribe(audio))
            assertEquals("", OllamaTranscriber.clean("Sorry. [NO_SPEECH]"))
        } finally {
            server.close()
        }
    }

    // --- Voice memos ------------------------------------------------------------------------------------------

    private fun wav(name: String, durationMs: Long): RecordedAudio {
        val file = tmp.newFile(name)
        WavWriter(file).apply { val s = ShortArray((durationMs * 16).toInt()); write(s, s.size); close() }
        return RecordedAudio(file.path, durationMs)
    }

    private fun chatReply(text: String) = MockResponse.Builder().code(200).body(
        """{"model":"gemma4","message":{"role":"assistant","content":${Json.encodeToString(String.serializer(), text)}},"done":true}""",
    ).build()

    private inner class FakeRecorder(var durationMs: Long = 2_000, var opens: Boolean = true) : Recorder {
        override val level: StateFlow<Float> = MutableStateFlow(0f)
        var recording = false
        var last: RecordedAudio? = null
        override fun start() = opens.also { recording = it }
        override fun stop(): RecordedAudio? {
            if (!recording) return null
            recording = false
            return wav("memo${System.nanoTime()}.wav", durationMs).also { last = it }
        }
        override fun cancel() {
            stop()?.path?.let { java.io.File(it).delete() }
        }
    }

    private class FakeSpeech(override val isAvailable: Boolean = true) : SpeechInput {
        var listener: SpeechInput.Listener? = null
        var stopped = false
        var cancelled = false
        override fun start(listener: SpeechInput.Listener) { this.listener = listener }
        override fun stop() { stopped = true }
        override fun cancel() { cancelled = true }
    }

    private class Harness(scope: TestScope, val recorder: Recorder, val speech: FakeSpeech, hears: Boolean, transcript: Outcome<String>) {
        val sent = mutableListOf<Pair<Long, String>>()
        var spokeStopped = 0
        var transcribed = 0
        val memos = VoiceMemos(
            recorder = recorder,
            transcriber = { transcribed++; transcript },
            liveSpeech = speech,
            modelHears = { hears },
            send = { id, text -> sent += id to text },
            stopSpeaking = { spokeStopped++ },
            scope = scope.backgroundScope,
            dispatchers = testDispatchers(kotlinx.coroutines.test.UnconfinedTestDispatcher(scope.testScheduler)),
            nowMs = { scope.testScheduler.currentTime },
        )
    }

    @Test fun `a memo is transcribed, sent to the chat it started in, and its file deleted`() = runTest {
        val recorder = FakeRecorder()
        val h = Harness(this, recorder, FakeSpeech(), hears = true, transcript = Outcome.Success("Remind me to call Mom"))
        h.memos.start(7)
        assertEquals(1, h.spokeStopped)
        assertTrue(h.memos.state.value is VoiceMemos.State.Recording)
        h.memos.stopAndSend()
        assertEquals(listOf(7L to "Remind me to call Mom"), h.sent)
        assertFalse("deleted after transcription", java.io.File(recorder.last!!.path).exists())
        assertEquals(VoiceMemos.State.Idle, h.memos.state.value)
        assertNull(h.memos.notice.value)
    }

    @Test fun `too short, cancelled, silent and failed memos send nothing and leave no file`() = runTest {
        val recorder = FakeRecorder(durationMs = 500)
        var h = Harness(this, recorder, FakeSpeech(), hears = true, transcript = Outcome.Success("x"))
        h.memos.start(1); h.memos.stopAndSend()
        assertEquals(VoiceMemos.TOO_SHORT, h.memos.notice.value)
        assertEquals("not even transcribed", 0, h.transcribed)
        assertFalse(java.io.File(recorder.last!!.path).exists())

        recorder.durationMs = 3_000
        h.memos.start(1); h.memos.cancel()
        assertFalse(recorder.recording)
        assertEquals(VoiceMemos.State.Idle, h.memos.state.value)

        h = Harness(this, recorder, FakeSpeech(), hears = true, transcript = Outcome.Success(""))
        h.memos.start(1); h.memos.stopAndSend()
        assertEquals(VoiceMemos.NO_SPEECH, h.memos.notice.value)
        assertFalse(java.io.File(recorder.last!!.path).exists())

        h = Harness(this, recorder, FakeSpeech(), hears = true, transcript = Outcome.Failure(AppError.ServerUnreachable("http://pc:11434")))
        h.memos.start(1); h.memos.stopAndSend()
        assertTrue(h.memos.notice.value!!, h.memos.notice.value!!.startsWith("Couldn't transcribe the recording: Can't reach your Ollama server"))
        assertFalse(java.io.File(recorder.last!!.path).exists())

        h.memos.start(1); h.memos.cancel(VoiceMemos.LEFT_APP)
        assertEquals(VoiceMemos.LEFT_APP, h.memos.notice.value)
        assertTrue(h.sent.isEmpty())
    }

    @Test fun `a microphone that won't open says so`() = runTest {
        val h = Harness(this, FakeRecorder(opens = false), FakeSpeech(), hears = true, transcript = Outcome.Success("x"))
        h.memos.start(1)
        assertEquals(VoiceMemos.MIC_UNAVAILABLE, h.memos.notice.value)
        assertEquals(VoiceMemos.State.Idle, h.memos.state.value)
    }

    @Test fun `a model that can't hear uses live speech recognition instead of a WAV`() = runTest {
        val recorder = FakeRecorder()
        val speech = FakeSpeech()
        val h = Harness(this, recorder, speech, hears = false, transcript = Outcome.Success("unused"))
        h.memos.start(3)
        assertFalse("no WAV", recorder.recording)
        assertTrue((h.memos.state.value as VoiceMemos.State.Recording).live)
        h.memos.stopAndSend()
        assertTrue(speech.stopped)
        speech.listener!!.onFinal("What's on my calendar")
        assertEquals(listOf(3L to "What's on my calendar"), h.sent)
        assertEquals(0, h.transcribed)

        h.memos.start(3)
        speech.listener!!.onFinal("")
        assertEquals(VoiceMemos.NO_SPEECH, h.memos.notice.value)

        val none = Harness(this, recorder, FakeSpeech(isAvailable = false), hears = false, transcript = Outcome.Success("x"))
        none.memos.start(3)
        assertEquals(VoiceMemos.CANT_HEAR, none.memos.notice.value)
    }

    @Test fun `recording stops and sends at five minutes`() = runTest {
        val recorder = FakeRecorder(durationMs = VoiceMemos.MAX_MS)
        val h = Harness(this, recorder, FakeSpeech(), hears = true, transcript = Outcome.Success("A long memo"))
        h.memos.start(1)
        advanceTimeBy(VoiceMemos.MAX_MS + 1)
        assertEquals(listOf(1L to "A long memo"), h.sent)
        assertEquals(VoiceMemos.AT_LIMIT, h.memos.notice.value)
    }
}
