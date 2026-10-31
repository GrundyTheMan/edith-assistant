package com.edith.app.chat.engine

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.AppErrorException
import com.edith.app.core.error.Outcome
import com.edith.app.core.permissions.LocalNetworkAccess
import com.edith.app.core.time.Clock
import com.edith.app.data.chat.ChatRepository
import com.edith.app.data.db.EdithDatabase
import com.edith.app.data.db.EdithDatabaseCallback
import com.edith.app.data.db.MessageRole
import com.edith.app.data.db.MessageSource
import com.edith.app.data.db.MessageStatus
import com.edith.app.ollama.ChatCall
import com.edith.app.ollama.ChatEvent
import com.edith.app.ollama.ModelCatalog
import com.edith.app.ollama.OllamaClient
import com.edith.app.ollama.OllamaMessage
import com.edith.app.ollama.OllamaModelTag
import com.edith.app.ollama.OllamaShow
import com.edith.app.ollama.ReplyStats
import com.edith.app.settings.ModelSelection
import com.edith.app.settings.Settings
import com.edith.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.Collections

/** C06 engine tests: real in-memory Room, a scripted Ollama, the real prompt/context builders. */
@RunWith(AndroidJUnit4::class)
class ChatEngineTest {

    /** Each reply is a script: words streamed with a delay, or an error, or nothing. */
    private class ScriptedOllama : OllamaClient {
        var words = listOf("Hello", " there", ",", " friend", "!")
        var wordDelayMs = 40L
        var failure: AppError? = null
        val calls: MutableList<ChatCall> = Collections.synchronizedList(mutableListOf())
        @Volatile var streamCancelled = false

        override fun chatStream(baseUrl: String, request: ChatCall): Flow<ChatEvent> = flow {
            calls += request
            failure?.let { throw AppErrorException(it) }
            try {
                for (w in words) { delay(wordDelayMs); emit(ChatEvent.Delta(w)) }
                emit(ChatEvent.Done(ReplyStats(totalMs = 10, evalCount = words.size, promptEvalCount = 1)))
            } catch (e: kotlinx.coroutines.CancellationException) {
                streamCancelled = true   // the real client cancels the HTTP call here (C03 test 9)
                throw e
            }
        }
        override suspend fun chat(baseUrl: String, request: ChatCall): Outcome<OllamaMessage> = error("unused")
        override suspend fun listModels(baseUrl: String) = Outcome.Success(listOf(OllamaModelTag("gemma4:12b-it-q8_0")))
        override suspend fun show(baseUrl: String, model: String) = Outcome.Success(OllamaShow(listOf("completion", "tools")))
        override suspend fun version(baseUrl: String) = Outcome.Success("0.40.13")
    }

    private class MemorySettings : SettingsRepository {
        val state = MutableStateFlow(Settings())
        override val settings: Flow<Settings> = state
        override suspend fun current() = state.value
        override suspend fun update(transform: (Settings) -> Settings) { state.value = transform(state.value) }
    }

    private val clock = object : Clock {
        var t = Instant.parse("2026-10-31T12:00:00Z")
        override fun now(): Instant = t.also { t = t.plusMillis(1) }
        override fun zone(): ZoneId = ZoneId.of("America/Detroit")
    }
    private val ollama = ScriptedOllama()
    private var networkGranted = true
    private val events = Collections.synchronizedList(mutableListOf<String>())
    private lateinit var db: EdithDatabase
    private lateinit var repo: ChatRepository
    private lateinit var engineScope: CoroutineScope
    private lateinit var engine: ChatEngine

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EdithDatabase::class.java)
            .addCallback(EdithDatabaseCallback).build()
        repo = ChatRepository(db, clock, AppDispatchers(Dispatchers.Main, Dispatchers.IO, Dispatchers.Default))
        val settings = MemorySettings()
        engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engine = ChatEngine(
            repo, settings, ModelSelection(settings, ModelCatalog(ollama)), PromptBuilder(clock) { listOf(DateTimeSection()) },
            ContextBuilder(), PlainTurnRunner(ollama),
            listOf(object : ReplyListener {
                override fun onDelta(text: String) { events += "delta:$text" }
                override fun onFinished(text: String) { events += "finished:$text" }
                override fun onCancelled() { events += "cancelled" }
            }),
            engineScope, object : LocalNetworkAccess { override fun isGranted() = networkGranted },
        )
    }

    @After fun tearDown() {
        engineScope.cancel()
        db.close()
    }

    private suspend fun awaitDone(): EngineState =
        withTimeout(10_000) { engine.state.first { it is EngineState.Idle || it is EngineState.Failed } }

    private suspend fun rows() = repo.observe().first().map { Triple(it.message.role, it.message.status, it.message.content) }

    @Test fun sendStoresTheUserMessageStreamsAndStoresTheReply() = runBlocking {
        val partials = Collections.synchronizedList(mutableListOf<String>())
        val watcher = launch(Dispatchers.Default) {
            engine.state.collect { if (it is EngineState.Working) partials += it.partialText }
        }

        engine.send("Hi Edith", MessageSource.Typed)
        assertTrue("working right away", engine.state.value is EngineState.Working)
        assertEquals(EngineState.Idle, awaitDone())
        watcher.cancel()

        assertEquals(
            listOf(
                Triple(MessageRole.User, MessageStatus.Complete, "Hi Edith"),
                Triple(MessageRole.Assistant, MessageStatus.Complete, "Hello there, friend!"),
            ),
            rows(),
        )
        val growing = partials.filter { it.isNotEmpty() }.distinct()
        assertTrue("partial text should grow while streaming: $growing", growing.size >= 2 && growing.zipWithNext().all { (a, b) -> b.startsWith(a) })
        assertEquals(listOf("delta:Hello", "delta: there", "delta:,", "delta: friend", "delta:!", "finished:Hello there, friend!"), events)

        val sent = ollama.calls.single()
        assertEquals("gemma4:12b-it-q8_0", sent.model)
        assertEquals("system", sent.messages.first().role)
        assertTrue(sent.messages.first().content.startsWith(PERSONA) && "Current date and time:" in sent.messages.first().content)
        assertEquals(OllamaMessage("user", "Hi Edith"), sent.messages.last())
    }

    @Test fun anUnreachableServerFailsWithAStoredErrorAndTheNextSendClearsIt() = runBlocking {
        ollama.failure = AppError.ServerUnreachable("http://10.0.2.2:11434")
        engine.send("Hello?", MessageSource.Typed)
        val failed = awaitDone() as EngineState.Failed
        assertTrue(failed.error is AppError.ServerUnreachable)
        val last = repo.observe().first().last().message
        assertEquals(MessageStatus.Failed, last.status)
        assertEquals(failed.error.userMessage, last.errorMessage)

        ollama.failure = null
        engine.send("Try again", MessageSource.Typed)
        assertTrue("next send clears Failed", engine.state.value is EngineState.Working)
        assertEquals(EngineState.Idle, awaitDone())
        // The failed reply isn't sent back as context.
        assertEquals(listOf("system", "user", "user"), ollama.calls.last().messages.map { it.role })
    }

    @Test fun cancelKeepsThePartialTextAsACancelledMessageAndStopsTheStream() = runBlocking {
        ollama.words = (1..50).map { " word$it" }
        ollama.wordDelayMs = 100
        engine.send("Tell me a long story", MessageSource.Typed)
        withTimeout(5_000) { engine.state.first { it is EngineState.Working && it.partialText.length > 10 } }

        engine.cancel()
        assertEquals(EngineState.Idle, awaitDone())

        val last = repo.observe().first().last().message
        assertEquals(MessageStatus.Cancelled, last.status)
        assertTrue("partial kept: ${last.content}", last.content.startsWith("word1 word2"))
        assertTrue(ollama.streamCancelled)
        assertTrue(events.last() == "cancelled")
    }

    @Test fun regenerateReplacesTheLastReply() = runBlocking {
        ollama.words = listOf("First")
        engine.send("Pick a number", MessageSource.Typed)
        awaitDone()
        ollama.words = listOf("Second")

        engine.regenerateLast()
        assertEquals(EngineState.Idle, awaitDone())

        assertEquals(
            listOf(Triple(MessageRole.User, MessageStatus.Complete, "Pick a number"), Triple(MessageRole.Assistant, MessageStatus.Complete, "Second")),
            rows(),
        )
        assertEquals("the old reply isn't in the regenerated context", listOf("system", "user"), ollama.calls.last().messages.map { it.role })
    }

    @Test fun theTurnCarriesOnWhenTheScreenGoesAway() = runBlocking {
        val viewModelScope = CoroutineScope(Job() + Dispatchers.Default)   // stands in for a ViewModel's scope
        viewModelScope.launch { engine.send("Hi", MessageSource.Typed) }.join()
        viewModelScope.cancel()   // the ViewModel is cleared mid-turn (rotation, leaving the screen)

        assertEquals(EngineState.Idle, awaitDone())
        assertEquals(MessageStatus.Complete, repo.observe().first().last().message.status)
    }

    @Test fun aBlankReplyIsStoredAsAFailureNotAnEmptyBubble() = runBlocking {
        ollama.words = listOf("  ", "\n")
        engine.send("Hello", MessageSource.Typed)
        assertEquals(EngineState.Failed(AppError.EmptyReply), awaitDone())
        val last = repo.observe().first().last().message
        assertEquals(MessageStatus.Failed to "Edith didn't reply. Try again.", last.status to last.errorMessage)
    }

    @Test fun withoutLocalNetworkAccessNothingIsSentAndTheReasonIsClear() = runBlocking {
        networkGranted = false
        engine.send("Hello", MessageSource.Typed)
        assertEquals(EngineState.Failed(AppError.PermissionDenied("local network")), awaitDone())
        assertTrue(ollama.calls.isEmpty())
    }

    /** Regression: the engine used to show Idle before releasing the turn, so a send right then was ignored. */
    @Test fun aSendTheMomentAReplyFinishesIsAccepted() = runBlocking {
        repeat(5) { round ->
            engine.send("message $round", MessageSource.Typed)
            assertEquals(EngineState.Idle, awaitDone())
        }
        assertEquals((0..4).map { "message $it" }, rows().filter { it.first == MessageRole.User }.map { it.third })
        assertEquals(5, ollama.calls.size)
    }

    @Test fun onlyOneTurnAtATime() = runBlocking {
        engine.send("one", MessageSource.Typed)
        engine.send("two", MessageSource.Typed)   // ignored: a turn is running
        awaitDone()
        assertEquals(listOf("one"), rows().filter { it.first == MessageRole.User }.map { it.third })
    }
}
