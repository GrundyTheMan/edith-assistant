package com.edith.app.di

import com.edith.app.attachments.AttachmentFiles
import com.edith.app.attachments.AttachmentsSection
import com.edith.app.attachments.EdithFilesSection
import com.edith.app.attachments.MediaAttachmentContext
import com.edith.app.attachments.Retention
import com.edith.app.chat.CurrentConversation
import com.edith.app.chat.engine.ChatEngine
import com.edith.app.chat.engine.ContextBuilder
import com.edith.app.chat.engine.ConversationTitler
import com.edith.app.chat.engine.DateTimeSection
import com.edith.app.chat.engine.PromptBuilder
import com.edith.app.chat.engine.PromptSection
import com.edith.app.chat.engine.ReplyListener
import com.edith.app.tools.framework.ToolGuidanceSection
import com.edith.app.chat.engine.TurnRunner
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.net.KtorClients
import com.edith.app.core.permissions.LocalNetworkAccess
import com.edith.app.core.secrets.SecretStore
import com.edith.app.core.time.Clock
import com.edith.app.data.chat.ChatRepository
import com.edith.app.data.db.EdithDatabase
import com.edith.app.ollama.KtorOllamaClient
import com.edith.app.ollama.ModelCatalog
import com.edith.app.ollama.OllamaClient
import com.edith.app.ollama.OllamaDtos
import com.edith.app.settings.ModelSelection
import com.edith.app.settings.SettingsRepository
import com.edith.app.tools.framework.Confirmations
import com.edith.app.core.permissions.ToolPermissions
import com.edith.app.tools.framework.ToolRegistry
import com.edith.app.tools.framework.ToolTurnRunner
import com.edith.app.tools.location.LocationProvider
import com.edith.app.tools.weather.OpenMeteoClient
import com.edith.app.tools.web.DuckDuckGoSearch
import com.edith.app.tools.web.OllamaWebSearch
import com.edith.app.tools.web.WebSearch
import com.edith.app.tools.web.WebSearchSetup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * What the platform gives the shared engine (K2.12, plan §4.2). The Android app builds one from its own objects (Keystore store, OkHttp
 * clients, the Room database it opens through a Context); the iPhone app builds one in K4.2 from Keychain, Darwin and a database file.
 * Everything here is an interface or a plain value, so nothing in [SharedContainer] knows which platform it runs on.
 */
class PlatformServices(
    val dispatchers: AppDispatchers,
    val clock: Clock,
    /** The database, opened on first use (Android: through a Context; the iPhone: a file path). */
    val database: Lazy<EdithDatabase>,
    val settings: SettingsRepository,
    val secrets: SecretStore,
    val http: KtorClients,
    val attachments: AttachmentFiles,
    val location: LocationProvider,
    val toolPermissions: ToolPermissions,
    val localNetwork: LocalNetworkAccess,
)

/**
 * The chat engine and everything it needs, built once from [PlatformServices] (K2.12). The services that are not shared yet (accounts, the home,
 * the printer) make their tools in the app, so the app hands this container what it must know about them: [toolRegistry] (called when the first
 * turn needs it, which breaks the loop "tools use the web search that lives here"), [replyListeners] (the titler, then the app's voice) and
 * [afterAttachments]. As the services move, these hooks go away.
 */
class SharedContainer(
    private val platform: PlatformServices,
    private val toolRegistry: () -> ToolRegistry,
    private val replyListeners: () -> MutableList<ReplyListener>,
    private val afterAttachments: suspend () -> Unit,
) {
    val dispatchers: AppDispatchers get() = platform.dispatchers
    val clock: Clock get() = platform.clock
    private val httpClients: KtorClients get() = platform.http
    private val settingsRepository: SettingsRepository get() = platform.settings

    /** For app-wide background work that outlives any screen. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    /** Chat turns run here, not in a ViewModel, so a reply carries on through rotation (C06, lessons L10). */
    val engineScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default)

    val ollama: OllamaClient by lazy { KtorOllamaClient(httpClients.ktorPlain, OllamaDtos.json, dispatchers) }
    val modelCatalog: ModelCatalog by lazy { ModelCatalog(ollama) }
    val modelSelection: ModelSelection by lazy { ModelSelection(settingsRepository, modelCatalog) }

    val chatRepository: ChatRepository by lazy { ChatRepository(platform.database.value, clock, dispatchers, platform.attachments) }

    /** Tool confirmations waiting for the owner's tap (C08 §6, D11). */
    val confirmations = Confirmations()

    /**
     * Web search (C10, D3): DuckDuckGo, keyless; with an Ollama API key saved, Ollama's web search first. The key stays in the secret store.
     */
    val webSearchSetup: WebSearchSetup by lazy { WebSearchSetup(platform.secrets) }
    val ollamaWeb: OllamaWebSearch by lazy { OllamaWebSearch(httpClients.ktorSecure, dispatchers, apiKey = { webSearchSetup.key() }) }
    val webSearch: WebSearch by lazy { WebSearch(DuckDuckGoSearch(httpClients.ktorSecure, dispatchers), ollamaWeb, webSearchSetup) }

    /** Weather data (C09): Open-Meteo, keyless. */
    val openMeteo: OpenMeteoClient by lazy { OpenMeteoClient(httpClients.ktorSecure, dispatchers) }

    /** System-prompt sections; later chunks add theirs here (attachments C15/C16, Google C17). */
    val promptSections: List<PromptSection> by lazy { listOf(DateTimeSection(), ToolGuidanceSection { toolRegistry() }, AttachmentsSection(), EdithFilesSection()) }

    /** The tool loop (C08). */
    val turnRunner: TurnRunner by lazy {
        ToolTurnRunner(ollama, toolRegistry(), confirmations, settingsRepository, clock, dispatchers, permissions = platform.toolPermissions)
    }

    /** Edith names new chats (D12). */
    val conversationTitler: ConversationTitler by lazy { ConversationTitler(chatRepository, ollama, settingsRepository, appScope) }

    /** Keeps the newest N attachments each way (C16, O1). */
    val retention: Retention by lazy { Retention(platform.database.value.attachmentDao(), platform.attachments, settingsRepository, clock, dispatchers) }

    val chatEngine: ChatEngine by lazy {
        ChatEngine(
            repo = chatRepository, settings = settingsRepository, modelSelection = modelSelection,
            promptBuilder = PromptBuilder(clock) { promptSections }, contextBuilder = ContextBuilder(MediaAttachmentContext(platform.attachments)),
            turnRunner = turnRunner, listeners = replyListeners(), scope = engineScope, localNetwork = platform.localNetwork,
            confirmations = confirmations, attachmentStore = platform.attachments, afterAttachments = afterAttachments,
        )
    }

    /** The open conversation (C06b). Resolved at startup. */
    val currentConversation: CurrentConversation by lazy { CurrentConversation(chatRepository, settingsRepository, chatEngine) }
}
