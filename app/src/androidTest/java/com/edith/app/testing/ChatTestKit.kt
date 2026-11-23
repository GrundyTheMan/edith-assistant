package com.edith.app.testing

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.edith.app.chat.CurrentConversation
import com.edith.app.chat.engine.ChatEngine
import com.edith.app.chat.engine.ContextBuilder
import com.edith.app.chat.engine.DateTimeSection
import com.edith.app.chat.engine.EngineState
import com.edith.app.chat.engine.PlainTurnRunner
import com.edith.app.chat.engine.PromptBuilder
import com.edith.app.chat.engine.ReplyListener
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.AppErrorException
import com.edith.app.core.error.Outcome
import com.edith.app.core.permissions.LocalNetworkAccess
import com.edith.app.core.time.Clock
import com.edith.app.data.chat.ChatRepository
import com.edith.app.data.db.EdithDatabase
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlin.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.datetime.TimeZone
import java.util.Collections

/** An Ollama whose replies are scripts: words streamed with a delay, or an error. */
class ScriptedOllama : OllamaClient {
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
        } catch (e: CancellationException) {
            streamCancelled = true   // the real client cancels the HTTP call here (C03 test 9)
            throw e
        }
    }

    /** The titler's non-streaming call (D12). */
    var titleReply: Outcome<OllamaMessage> = Outcome.Success(OllamaMessage("assistant", "\"Picking a Number.\""))
    val titleCalls: MutableList<ChatCall> = Collections.synchronizedList(mutableListOf())
    override suspend fun chat(baseUrl: String, request: ChatCall): Outcome<OllamaMessage> {
        titleCalls += request
        return titleReply
    }
    override suspend fun listModels(baseUrl: String) = Outcome.Success(listOf(OllamaModelTag("gemma4:12b-it-q8_0")))
    /** What the model can do (C15 needs "vision" and "audio" for attachments). */
    var capabilities = listOf("completion", "tools")
    override suspend fun show(baseUrl: String, model: String) = Outcome.Success(OllamaShow(capabilities))
    override suspend fun version(baseUrl: String) = Outcome.Success("0.40.13")
}

class MemorySettings : SettingsRepository {
    val state = MutableStateFlow(Settings())
    override val settings: Flow<Settings> = state
    override suspend fun current() = state.value
    override suspend fun update(transform: (Settings) -> Settings) { state.value = transform(state.value) }
}

/** Starts at [start] and moves 1 ms per reading, so stored messages have distinct, ordered times. */
class TickingClock(start: Instant = Instant.parse("2026-10-31T16:00:00Z"), private val zone: TimeZone = TimeZone.of("America/Detroit")) : Clock {
    @Volatile var t: Instant = start
    override fun now(): Instant = t.also { t = t + 1.milliseconds }
    override fun zone(): TimeZone = zone
}

/**
 * The real chat stack (in-memory Room, repository, engine, current conversation) over a [ScriptedOllama]: what the
 * engine and chat-screen tests run against. Call [close] in @After.
 */
class ChatTestKit(listeners: List<ReplyListener> = emptyList()) {
    val clock = TickingClock()
    val ollama = ScriptedOllama()
    val settings = MemorySettings()
    @Volatile var networkGranted = true
    val db: EdithDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EdithDatabase::class.java).build()
    val repo = ChatRepository(db, clock, AppDispatchers(Dispatchers.Main, Dispatchers.IO, Dispatchers.Default))
    val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val engine = ChatEngine(
        repo, settings, ModelSelection(settings, ModelCatalog(ollama)), PromptBuilder(clock) { listOf(DateTimeSection()) },
        ContextBuilder(), PlainTurnRunner(ollama), listeners, engineScope,
        object : LocalNetworkAccess { override fun isGranted() = networkGranted },
    )
    val current = CurrentConversation(repo, settings, engine)

    suspend fun awaitIdleOrFailed(): EngineState =
        withTimeout(10_000) { engine.state.first { it is EngineState.Idle || it is EngineState.Failed } }

    /** Stops the engine and waits for it (a stopped reply still writes its partial text), then closes the database. */
    fun close() {
        runBlocking { engineScope.coroutineContext.job.cancelAndJoin() }
        db.close()
    }
}
