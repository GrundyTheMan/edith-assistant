package com.edith.app.chat.engine

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edith.app.chat.CurrentConversation
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.permissions.LocalNetworkAccess
import com.edith.app.core.time.Clock
import com.edith.app.data.chat.ChatRepository
import com.edith.app.data.db.EdithDatabase
import com.edith.app.data.db.MessageRole
import com.edith.app.data.db.MessageSource
import com.edith.app.data.db.MessageStatus
import com.edith.app.data.db.TitleSource
import com.edith.app.ollama.ModelCatalog
import com.edith.app.ollama.OllamaMessage
import com.edith.app.settings.ModelSelection
import com.edith.app.testing.MemorySettings
import com.edith.app.testing.ScriptedOllama
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.datetime.TimeZone
import java.util.Collections

/** C06 engine tests: real in-memory Room, a scripted Ollama, the real prompt/context builders. */
@RunWith(AndroidJUnit4::class)
class ChatEngineTest {

    private val clock = object : Clock {
        var t = Instant.parse("2026-10-31T12:00:00Z")
        override fun now(): Instant = t.also { t = t + 1.milliseconds }
        override fun zone(): TimeZone = TimeZone.of("America/Detroit")
    }
    private val ollama = ScriptedOllama()
    private var networkGranted = true
    private val events = Collections.synchronizedList(mutableListOf<String>())
    private lateinit var db: EdithDatabase
    private lateinit var repo: ChatRepository
    private lateinit var engineScope: CoroutineScope
    private lateinit var engine: ChatEngine
    private var cid = 0L
    private val settings = MemorySettings()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EdithDatabase::class.java)
            .build()
        repo = ChatRepository(db, clock, AppDispatchers(Dispatchers.Main, Dispatchers.IO, Dispatchers.Default))
        cid = runBlocking { repo.createConversation() }
        engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engine = ChatEngine(
            repo, settings, ModelSelection(settings, ModelCatalog(ollama)), PromptBuilder(clock) { listOf(DateTimeSection()) },
            ContextBuilder(), PlainTurnRunner(ollama),
            listOf(object : ReplyListener {
                override fun onDelta(conversationId: Long, text: String) { events += "delta:$text" }
                override fun onFinished(conversationId: Long, messageId: Long?, text: String) { events += "finished:$text" }
                override fun onCancelled(conversationId: Long) { events += "cancelled" }
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

    private suspend fun rows() = repo.observe(cid).first().map { Triple(it.message.role, it.message.status, it.message.content) }

    @Test fun sendStoresTheUserMessageStreamsAndStoresTheReply() = runBlocking {
        val partials = Collections.synchronizedList(mutableListOf<String>())
        val watcher = launch(Dispatchers.Default) {
            engine.state.collect { if (it is EngineState.Working) partials += it.partialText }
        }

        engine.send(cid, "Hi Edith", MessageSource.Typed)
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
        engine.send(cid, "Hello?", MessageSource.Typed)
        val failed = awaitDone() as EngineState.Failed
        assertTrue(failed.error is AppError.ServerUnreachable)
        val last = repo.observe(cid).first().last().message
        assertEquals(MessageStatus.Failed, last.status)
        assertEquals(failed.error.userMessage, last.errorMessage)

        ollama.failure = null
        engine.send(cid, "Try again", MessageSource.Typed)
        assertTrue("next send clears Failed", engine.state.value is EngineState.Working)
        assertEquals(EngineState.Idle, awaitDone())
        // The failed reply isn't sent back as context.
        assertEquals(listOf("system", "user", "user"), ollama.calls.last().messages.map { it.role })
    }

    @Test fun cancelKeepsThePartialTextAsACancelledMessageAndStopsTheStream() = runBlocking {
        ollama.words = (1..50).map { " word$it" }
        ollama.wordDelayMs = 100
        engine.send(cid, "Tell me a long story", MessageSource.Typed)
        withTimeout(5_000) { engine.state.first { it is EngineState.Working && it.partialText.length > 10 } }

        engine.cancel()
        assertEquals(EngineState.Idle, awaitDone())

        val last = repo.observe(cid).first().last().message
        assertEquals(MessageStatus.Cancelled, last.status)
        assertTrue("partial kept: ${last.content}", last.content.startsWith("word1 word2"))
        assertTrue(ollama.streamCancelled)
        assertTrue(events.last() == "cancelled")
    }

    @Test fun regenerateReplacesTheLastReply() = runBlocking {
        ollama.words = listOf("First")
        engine.send(cid, "Pick a number", MessageSource.Typed)
        awaitDone()
        ollama.words = listOf("Second")

        engine.regenerateLast(cid)
        assertEquals(EngineState.Idle, awaitDone())

        assertEquals(
            listOf(Triple(MessageRole.User, MessageStatus.Complete, "Pick a number"), Triple(MessageRole.Assistant, MessageStatus.Complete, "Second")),
            rows(),
        )
        assertEquals("the old reply isn't in the regenerated context", listOf("system", "user"), ollama.calls.last().messages.map { it.role })
    }

    @Test fun theTurnCarriesOnWhenTheScreenGoesAway() = runBlocking {
        val viewModelScope = CoroutineScope(Job() + Dispatchers.Default)   // stands in for a ViewModel's scope
        viewModelScope.launch { engine.send(cid, "Hi", MessageSource.Typed) }.join()
        viewModelScope.cancel()   // the ViewModel is cleared mid-turn (rotation, leaving the screen)

        assertEquals(EngineState.Idle, awaitDone())
        assertEquals(MessageStatus.Complete, repo.observe(cid).first().last().message.status)
    }

    @Test fun aBlankReplyIsStoredAsAFailureNotAnEmptyBubble() = runBlocking {
        ollama.words = listOf("  ", "\n")
        engine.send(cid, "Hello", MessageSource.Typed)
        assertEquals(EngineState.Failed(cid, AppError.EmptyReply), awaitDone())
        val last = repo.observe(cid).first().last().message
        assertEquals(MessageStatus.Failed to "Edith didn't reply. Try again.", last.status to last.errorMessage)
    }

    @Test fun withoutLocalNetworkAccessNothingIsSentAndTheReasonIsClear() = runBlocking {
        networkGranted = false
        engine.send(cid, "Hello", MessageSource.Typed)
        assertEquals(EngineState.Failed(cid, AppError.PermissionDenied("local network")), awaitDone())
        assertTrue(ollama.calls.isEmpty())
    }

    /** Regression: the engine used to show Idle before releasing the turn, so a send right then was ignored. */
    @Test fun aSendTheMomentAReplyFinishesIsAccepted() = runBlocking {
        repeat(5) { round ->
            engine.send(cid, "message $round", MessageSource.Typed)
            assertEquals(EngineState.Idle, awaitDone())
        }
        assertEquals((0..4).map { "message $it" }, rows().filter { it.first == MessageRole.User }.map { it.third })
        assertEquals(5, ollama.calls.size)
    }

    @Test fun onlyOneTurnAtATime() = runBlocking {
        engine.send(cid, "one", MessageSource.Typed)
        engine.send(cid, "two", MessageSource.Typed)   // ignored: a turn is running
        awaitDone()
        assertEquals(listOf("one"), rows().filter { it.first == MessageRole.User }.map { it.third })
    }

    // --- C06b: conversations ------------------------------------------------------------------------------------

    @Test fun aReplyStaysInTheConversationItStartedIn() = runBlocking {
        val other = repo.createConversation()
        val current = CurrentConversation(repo, settings, engine)
        current.select(cid)
        ollama.wordDelayMs = 80
        engine.send(cid, "Question in A", MessageSource.Typed)
        withTimeout(5_000) { engine.state.first { it is EngineState.Working && it.partialText.isNotEmpty() } }

        current.select(other)   // the owner switches chats mid-reply
        assertEquals(cid, (engine.state.value as EngineState.Working).conversationId)
        engine.send(other, "Question in B", MessageSource.Typed)   // ignored: one turn at a time, across chats
        assertEquals(EngineState.Idle, awaitDone())

        assertEquals(listOf("Question in A", "Hello there, friend!"), repo.observe(cid).first().map { it.message.content })
        assertTrue("nothing landed in B", repo.observe(other).first().isEmpty())
        assertEquals(other, settings.current().currentConversationId)
    }

    @Test fun failuresNameTheirConversation() = runBlocking {
        ollama.failure = AppError.Timeout
        engine.send(cid, "Hello?", MessageSource.Typed)
        assertEquals(EngineState.Failed(cid, AppError.Timeout), awaitDone())
    }

    @Test fun deletingAConversationMidReplyStopsTheTurnAndStoresNothingInIt() = runBlocking {
        val keep = repo.createConversation()
        val current = CurrentConversation(repo, settings, engine)
        current.select(cid)
        ollama.words = (1..50).map { " word$it" }
        ollama.wordDelayMs = 100
        engine.send(cid, "Long story please", MessageSource.Typed)
        withTimeout(5_000) { engine.state.first { it is EngineState.Working && it.partialText.length > 10 } }

        current.delete(cid)

        assertEquals(EngineState.Idle, engine.state.value)
        assertTrue(ollama.streamCancelled)
        assertEquals(null, repo.conversation(cid))
        assertEquals("the open chat moves to what's left", keep, current.id.value)
        assertEquals(listOf("cancelled"), events.filter { it == "cancelled" })
    }

    @Test fun currentConversationRestoresFallsBackAndAvoidsBlankChats() = runBlocking {
        val current = CurrentConversation(repo, settings, engine)
        val other = repo.createConversation()
        repo.addUserMessage(other, "has history", MessageSource.Typed)

        settings.update { it.copy(currentConversationId = other) }
        assertEquals("saved id restored", other, current.resolve())
        assertEquals("empty non-current chat pruned", null, repo.conversation(cid))

        val fresh = current.newChat()
        assertEquals("New chat on an empty chat stays put", fresh, current.newChat())

        settings.update { it.copy(currentConversationId = 9_999) }   // deleted meanwhile
        assertEquals("falls back to the most recent", fresh, current.resolve())
        repo.deleteConversation(fresh)
        repo.deleteConversation(other)
        val created = current.resolve()
        assertTrue("creates one when none is left", repo.conversation(created) != null)
    }

    @Test fun edithTitlesTheChatAfterItsFirstReply() = runBlocking {
        val titler = ConversationTitler(repo, ollama, settings, engineScope)
        ollama.words = listOf("Seven", ".")
        engine.send(cid, "Pick a number between one and ten for me", MessageSource.Typed)
        awaitDone()
        assertEquals("provisional", "Pick a number between one and ten for me", repo.conversation(cid)!!.title)

        assertEquals("Picking a Number", titler.titleIfNeeded(cid))
        assertEquals("Picking a Number" to TitleSource.Edith, repo.conversation(cid)!!.let { it.title to it.titleSource })
        val call = ollama.titleCalls.single()
        assertEquals("gemma4:12b-it-q8_0", call.model)
        assertTrue(call.messages.last().content.contains("Owner: Pick a number") && call.messages.last().content.contains("Edith: Seven."))

        repo.rename(cid, "Mine")
        assertEquals("never after the owner renames", null, titler.titleIfNeeded(cid))
        assertEquals(1, ollama.titleCalls.size)
    }

    @Test fun aFailedTitleCallKeepsTheProvisionalTitle() = runBlocking {
        val titler = ConversationTitler(repo, ollama, settings, engineScope)
        ollama.titleReply = Outcome.Failure(AppError.ServerUnreachable("http://10.0.2.2:11434"))
        engine.send(cid, "Hello", MessageSource.Typed)
        awaitDone()
        assertEquals(null, titler.titleIfNeeded(cid))
        assertEquals("Hello" to TitleSource.FirstMessage, repo.conversation(cid)!!.let { it.title to it.titleSource })
    }
}
