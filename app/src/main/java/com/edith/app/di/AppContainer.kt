package com.edith.app.di

import android.app.Application
import com.edith.app.chat.CurrentConversation
import com.edith.app.chat.service.ReplyKeepAlive
import com.edith.app.chat.service.ReplyService
import com.edith.app.chat.engine.ChatEngine
import com.edith.app.chat.engine.ContextBuilder
import com.edith.app.chat.engine.ConversationTitler
import com.edith.app.chat.engine.DateTimeSection
import com.edith.app.chat.engine.PlainTurnRunner
import com.edith.app.chat.engine.PromptBuilder
import com.edith.app.chat.engine.PromptSection
import com.edith.app.chat.engine.ReplyListener
import com.edith.app.chat.engine.TurnRunner
import com.edith.app.chat.ui.ChatFeatures
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.net.TaggedSocketFactory
import com.edith.app.core.secrets.KeystoreSecretStore
import com.edith.app.core.secrets.SecretStore
import com.edith.app.core.time.Clock
import com.edith.app.core.time.SystemClock
import com.edith.app.data.chat.ChatRepository
import com.edith.app.data.db.EdithDatabase
import com.edith.app.ollama.ModelCatalog
import com.edith.app.ollama.OkHttpOllamaClient
import com.edith.app.ollama.OllamaClient
import com.edith.app.ollama.OllamaDtos
import com.edith.app.core.permissions.AndroidLocalNetworkAccess
import com.edith.app.core.permissions.LocalNetworkAccess
import com.edith.app.settings.DataStoreSettingsRepository
import com.edith.app.settings.ModelSelection
import com.edith.app.settings.SettingsRepository
import com.edith.app.voice.speech.SilentSpeechController
import com.edith.app.voice.speech.SpeechController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Builds and holds the app's long-lived objects. Later chunks add fields here. Created once, by [com.edith.app.EdithApp]. */
class AppContainer(val app: Application) {
    init {
        Log.d(LogTag.App, "AppContainer created")
    }

    val dispatchers: AppDispatchers = AppDispatchers.Default
    val clock: Clock = SystemClock

    /** For app-wide background work that outlives any screen. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    /** The database (C05). Opened once at startup by [openDatabaseInBackground]; Room does its I/O off the main thread. */
    val database: EdithDatabase by lazy { EdithDatabase.create(app) }
    val chatRepository: ChatRepository by lazy { ChatRepository(database, clock, dispatchers) }

    /** Chat turns run here, not in a ViewModel, so a reply carries on through rotation (C06, lessons L10). */
    val engineScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default)

    /** System-prompt sections; later chunks add theirs here (tools C08, attachments C15/C16, Google C17). */
    val promptSections: List<PromptSection> = listOf(DateTimeSection())

    /** Replaced by the tool-calling runner in C08. */
    val turnRunner: TurnRunner by lazy { PlainTurnRunner(ollama) }

    /** Edith names new chats (D12). */
    val conversationTitler: ConversationTitler by lazy { ConversationTitler(chatRepository, ollama, settingsRepository, appScope) }

    /** Speech (C13) adds itself here. */
    val replyListeners: MutableList<ReplyListener> by lazy { mutableListOf<ReplyListener>(conversationTitler) }

    val chatEngine: ChatEngine by lazy {
        ChatEngine(
            repo = chatRepository, settings = settingsRepository, modelSelection = modelSelection,
            promptBuilder = PromptBuilder(clock) { promptSections }, contextBuilder = ContextBuilder(),
            turnRunner = turnRunner, listeners = replyListeners, scope = engineScope, localNetwork = localNetworkAccess,
        )
    }

    /** The open conversation (C06b). Resolved at startup by [openDatabaseInBackground]. */
    val currentConversation: CurrentConversation by lazy {
        CurrentConversation(chatRepository, settingsRepository, chatEngine)
    }

    /** The chat's Play/Stop buttons (C07 §5). C13 replaces this with the phone's voice. */
    val speechController: SpeechController = SilentSpeechController

    /** Which input-row buttons exist (C07 §6). C15 turns on attach; C12 record and dictation. */
    val chatFeatures: ChatFeatures = ChatFeatures()

    /**
     * Creates/opens edith.db on a background thread at launch (running any migration), so the first chat doesn't
     * wait for it, then picks the conversation to open.
     */
    fun openDatabaseInBackground() {
        appScope.launch {
            try {
                database.openHelper.writableDatabase
                Log.d(LogTag.App, "Database ready")
                currentConversation.resolve()
            } catch (e: Exception) {
                Log.e(LogTag.App, "Couldn't open the database", e)
            }
        }
        replyKeepAlive.watch(appScope)
    }

    /** Keeps a reply going when the owner leaves the app (D13): starts [ReplyService] as each turn begins. */
    val replyKeepAlive: ReplyKeepAlive by lazy { ReplyKeepAlive(chatEngine.state) { ReplyService.start(app) } }

    val secretStore: SecretStore by lazy { KeystoreSecretStore(app) }

    /**
     * One shared HTTP client (C03 §5). Long read timeout: the first token after a model load can take a while.
     * Building it sets up TLS (~190 ms on the emulator), so it's lazy and first used from the IO dispatcher by
     * [OkHttpOllamaClient] — don't read it on the main thread.
     */
    private val okHttpLazy: Lazy<OkHttpClient> = lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .socketFactory(TaggedSocketFactory())
            .build()
    }
    val okHttp: OkHttpClient by okHttpLazy

    val ollama: OllamaClient by lazy { OkHttpOllamaClient(okHttpLazy, OllamaDtos.json, dispatchers) }
    val modelCatalog: ModelCatalog by lazy { ModelCatalog(ollama) }

    /** One DataStore per file per process, so the repository is created exactly once here (C04). */
    val settingsRepository: SettingsRepository by lazy { DataStoreSettingsRepository.create(app) }
    val modelSelection: ModelSelection by lazy { ModelSelection(settingsRepository, modelCatalog) }
    val localNetworkAccess: LocalNetworkAccess by lazy { AndroidLocalNetworkAccess(app) }
}
