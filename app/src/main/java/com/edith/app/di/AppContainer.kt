package com.edith.app.di

import android.app.Application
import android.media.AudioManager
import android.media.ToneGenerator
import com.edith.app.chat.CurrentConversation
import com.edith.app.chat.service.ReplyKeepAlive
import com.edith.app.chat.service.ReplyService
import com.edith.app.chat.engine.ChatEngine
import com.edith.app.chat.engine.ContextBuilder
import com.edith.app.chat.engine.ConversationTitler
import com.edith.app.chat.engine.DateTimeSection
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
import com.edith.app.core.error.Outcome
import com.edith.app.data.db.EdithDatabase
import com.edith.app.data.db.MessageSource
import com.edith.app.ollama.ModelCatalog
import com.edith.app.ollama.OkHttpOllamaClient
import com.edith.app.ollama.OllamaClient
import com.edith.app.ollama.OllamaDtos
import com.edith.app.core.permissions.AndroidLocalNetworkAccess
import com.edith.app.core.permissions.LocalNetworkAccess
import com.edith.app.settings.DataStoreSettingsRepository
import com.edith.app.settings.ModelSelection
import com.edith.app.settings.SettingsRepository
import com.edith.app.tools.context.CurrentTimeTool
import com.edith.app.core.permissions.ToolPermissions
import com.edith.app.tools.framework.Confirmations
import com.edith.app.tools.location.AndroidLocationProvider
import com.edith.app.tools.location.LocationProvider
import com.edith.app.tools.framework.ToolGuidanceSection
import com.edith.app.tools.framework.ToolRegistry
import com.edith.app.tools.framework.ToolTurnRunner
import com.edith.app.tools.web.DuckDuckGoSearch
import com.edith.app.tools.web.OllamaWebSearch
import com.edith.app.tools.web.PageReader
import com.edith.app.tools.web.ReadWebPageTool
import com.edith.app.tools.web.SearchWebTool
import com.edith.app.tools.web.WebSearch
import com.edith.app.tools.web.WebSearchSetup
import com.edith.app.tools.weather.OpenMeteoClient
import com.edith.app.tools.weather.WeatherTool
import com.edith.app.voice.speech.AndroidTts
import com.edith.app.voice.speech.SpeechController
import com.edith.app.voice.speech.SpeechOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import com.edith.app.voice.capture.AudioRecorder
import com.edith.app.voice.capture.LiveSpeech
import com.edith.app.voice.capture.OllamaTranscriber
import com.edith.app.voice.capture.SpeechInput
import com.edith.app.voice.capture.VoiceMemos
import com.edith.app.voice.handsfree.HandsFree
import com.edith.app.voice.handsfree.MicWakeListener
import com.edith.app.voice.handsfree.OpenWakeWord
import com.edith.app.voice.handsfree.WakeWordModel
import java.io.File
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

    /** Every tool Edith has (C08). Later chunks add theirs here: weather C09, web C10, location C11, Google C18/C19. */
    val toolRegistry: ToolRegistry by lazy {
        ToolRegistry(
            tools = listOf(
                CurrentTimeTool(), WeatherTool(openMeteo, deviceLocation),
                SearchWebTool(webSearch), ReadWebPageTool(webSearch.pages(PageReader(okHttpLazy, dispatchers))),
            ),
        )
    }

    /**
     * Web search (C10, D3): DuckDuckGo, keyless; with an Ollama API key saved, Ollama's web search first. The key
     * stays in [secretStore].
     */
    val webSearchSetup: WebSearchSetup by lazy { WebSearchSetup(secretStore) }
    val ollamaWeb: OllamaWebSearch by lazy { OllamaWebSearch(okHttpLazy, dispatchers, apiKey = { webSearchSetup.key() }) }
    private val webSearch: WebSearch by lazy { WebSearch(DuckDuckGoSearch(okHttpLazy, dispatchers), ollamaWeb, webSearchSetup) }

    /** Weather data (C09): Open-Meteo, keyless. Shares the app's HTTP client, with its own 10 s timeouts. */
    val openMeteo: OpenMeteoClient by lazy { OpenMeteoClient(okHttpLazy, dispatchers) }

    /** Tool confirmations waiting for the owner's tap (C08 §6, D11). */
    val confirmations = Confirmations()

    /** Permissions tools ask for with Android's dialog, shown by the chat screen (C11). */
    val toolPermissions: ToolPermissions by lazy { ToolPermissions.android(app) }

    /** The phone's approximate location for "here" (C11): never stored, never sent to Ollama. */
    val deviceLocation: LocationProvider by lazy { AndroidLocationProvider(app, dispatchers) }

    /** System-prompt sections; later chunks add theirs here (attachments C15/C16, Google C17). */
    val promptSections: List<PromptSection> by lazy { listOf(DateTimeSection(), ToolGuidanceSection { toolRegistry }) }

    /** The tool loop (C08). */
    val turnRunner: TurnRunner by lazy {
        ToolTurnRunner(ollama, toolRegistry, confirmations, settingsRepository, clock, dispatchers, permissions = toolPermissions)
    }

    /** Edith names new chats (D12). */
    val conversationTitler: ConversationTitler by lazy { ConversationTitler(chatRepository, ollama, settingsRepository, appScope) }

    /** Edith's voice speaks replies as they stream (C13). */
    val replyListeners: MutableList<ReplyListener> by lazy { mutableListOf(conversationTitler, speechOutput) }

    val chatEngine: ChatEngine by lazy {
        ChatEngine(
            repo = chatRepository, settings = settingsRepository, modelSelection = modelSelection,
            promptBuilder = PromptBuilder(clock) { promptSections }, contextBuilder = ContextBuilder(),
            turnRunner = turnRunner, listeners = replyListeners, scope = engineScope, localNetwork = localNetworkAccess,
            confirmations = confirmations,
        )
    }

    /** The open conversation (C06b). Resolved at startup by [openDatabaseInBackground]. */
    val currentConversation: CurrentConversation by lazy {
        CurrentConversation(chatRepository, settingsRepository, chatEngine)
    }

    /** Edith's voice (C13): the phone's text-to-speech, started once for the app. */
    val speechOutput: SpeechOutput by lazy { SpeechOutput(AndroidTts(app), settingsRepository, appScope) }

    /** The chat's Play/Stop buttons (C07 §5). */
    val speechController: SpeechController get() = speechOutput

    /** Which input-row buttons exist (C07 §6). C15 turns on attach; C12 record, and dictation when the phone can. */
    val chatFeatures: ChatFeatures by lazy { ChatFeatures(record = true, dictation = dictation.isAvailable) }

    /** Voice memos (C12): app-scoped, so a recording or transcription survives rotation. */
    val voiceMemos: VoiceMemos by lazy {
        VoiceMemos(
            recorder = AudioRecorder(recordingsDir),
            transcriber = OllamaTranscriber(ollama, settingsRepository, dispatchers),
            liveSpeech = LiveSpeech(app),
            modelHears = {
                // Unknown (server unreachable, no model): try the model; its error is clearer than a fallback.
                val s = settingsRepository.current()
                val caps = s.model?.let { modelCatalog.capabilities(s.serverUrl, it) }
                caps !is Outcome.Success || "audio" in caps.value
            },
            send = { id, text -> chatEngine.send(id, text, MessageSource.VoiceMemo) },
            stopSpeaking = { speechController.stop() },
            scope = appScope,
            dispatchers = dispatchers,
        )
    }

    /** Dictation into the text field (C12): Android's speech recognizer. */
    val dictation: SpeechInput by lazy { LiveSpeech(app) }

    /** The wake word in this build: "Hey Edith" once the owner's model is in assets, else the stand-in (C14, D4). */
    val wakeWordModel: WakeWordModel by lazy { WakeWordModel.find(app) }

    /** Listens for the wake word with openWakeWord (D4). */
    val wakeListener: MicWakeListener by lazy { MicWakeListener { OpenWakeWord(app, wakeWordModel) } }

    /** Hands-free mode (C14): app-scoped; [HandsFreeService] keeps it running with its notification. */
    val handsFree: HandsFree by lazy {
        HandsFree(
            wake = wakeListener,
            recognizer = LiveSpeech(app),
            engineState = chatEngine.state,
            speaking = speechOutput.speakingMessageId,
            stopSpeaking = { speechOutput.stop() },
            cancelReply = { chatEngine.cancel() },
            send = { text -> chatEngine.send(currentConversation.await(), text, MessageSource.HandsFree) },
            earcon = ::earcon,
            settings = settingsRepository,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            phrase = wakeWordModel.phrase,
        )
    }

    /** A short beep after the wake word, so the owner knows Edith is listening (C14 §1). */
    private suspend fun earcon() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, EARCON_VOLUME)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, EARCON_MS)
            delay(EARCON_MS + 60L)   // let it finish before the recognizer opens the mic
            tone.release()
        } catch (e: RuntimeException) {
            Log.w(LogTag.Voice, "Couldn't play the hands-free beep", e)
        }
    }

    /** Where memos are recorded; emptied at startup and after every transcription. */
    private val recordingsDir: File get() = File(app.cacheDir, "recordings")

    /**
     * Creates/opens edith.db on a background thread at launch (running any migration), so the first chat doesn't
     * wait for it, then picks the conversation to open.
     */
    fun openDatabaseInBackground() {
        appScope.launch {
            try {
                AudioRecorder.deleteLeftovers(recordingsDir)   // from a crash or a killed process mid-recording
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

    private companion object {
        const val EARCON_VOLUME = 60
        const val EARCON_MS = 120
    }
}
