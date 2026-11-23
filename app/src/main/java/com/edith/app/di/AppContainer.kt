package com.edith.app.di

import android.app.Application
import com.edith.app.BuildConfig
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
import com.edith.app.core.lifecycle.AppVisibility
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.net.HttpClients
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
import com.edith.app.ollama.KtorOllamaClient
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
import com.edith.app.voice.own.AndroidSpeechFocus
import com.edith.app.voice.own.AudioTrackSink
import com.edith.app.voice.own.OwnVoiceState
import com.edith.app.voice.own.OwnVoices
import com.edith.app.voice.own.SherpaSynth
import com.edith.app.voice.own.SherpaTts
import com.edith.app.voice.own.VoiceDownloader
import com.edith.app.voice.speech.AndroidTts
import com.edith.app.voice.speech.ChoosingTts
import com.edith.app.voice.speech.SpeechController
import com.edith.app.voice.speech.SpeechOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import com.edith.app.attachments.AttachmentIntake
import com.edith.app.attachments.AttachmentPlayer
import com.edith.app.attachments.AttachmentsSection
import com.edith.app.attachments.MediaAttachmentContext
import com.edith.app.attachments.EdithFilesSection
import com.edith.app.attachments.FileAttachmentStore
import com.edith.app.attachments.Retention
import com.edith.app.google.CredentialManagerSignIn
import com.edith.app.google.browser.BrowserGoogle
import com.edith.app.google.browser.BrowserOAuth
import com.edith.app.google.browser.BrowserSignIn
import com.edith.app.icloud.ICloudAccounts
import com.edith.app.home.DataStoreHomePrefs
import com.edith.app.home.DemoPicture
import com.edith.app.home.FakeHomeBackend
import com.edith.app.home.Home
import com.edith.app.home.RoomCommandLog
import com.edith.app.home.homeTools
import com.edith.app.alexa.AlexaApi
import com.edith.app.alexa.AlexaService
import com.edith.app.meross.MerossApi
import com.edith.app.meross.MerossService
import com.edith.app.meross.PahoMerossBus
import com.edith.app.printer.MoonrakerApi
import com.edith.app.printer.PrinterService
import com.edith.app.blink.BlinkApi
import com.edith.app.blink.BlinkEndpoints
import com.edith.app.blink.BlinkService
import com.edith.app.ring.RingApi
import com.edith.app.ring.RingService
import com.edith.app.microsoft.MicrosoftAccounts
import com.edith.app.microsoft.MicrosoftOAuth
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.edith.app.google.PlayServicesAuthorizer
import com.edith.app.google.GoogleAccounts
import com.edith.app.accounts.AccountMigration
import com.edith.app.accounts.Accounts
import com.edith.app.accounts.DataStoreAccountStore
import com.edith.app.accounts.tools.CalendarHandles
import com.edith.app.accounts.tools.MailHandles
import com.edith.app.accounts.tools.calendarTools
import com.edith.app.accounts.tools.mailTools
import com.edith.app.accounts.tools.TaskHandles
import com.edith.app.accounts.tools.taskTools
import com.edith.app.accounts.tasks.RankStore
import com.edith.app.accounts.tasks.TaskRanker
import com.edith.app.voice.capture.AudioRecorder
import com.edith.app.voice.capture.LiveSpeech
import com.edith.app.voice.capture.OllamaTranscriber
import com.edith.app.voice.capture.SpeechInput
import com.edith.app.voice.capture.VoiceMemos
import com.edith.app.alerts.ActivitySource
import com.edith.app.alerts.AlertKeepAlive
import com.edith.app.alerts.AlertNotifier
import com.edith.app.alerts.AlertPrefs
import com.edith.app.alerts.AlertService
import com.edith.app.alerts.DataStoreAlertPrefs
import com.edith.app.voice.handsfree.HandsFree
import com.edith.app.voice.handsfree.MicWakeListener
import com.edith.app.voice.handsfree.OpenWakeWord
import com.edith.app.voice.handsfree.WakeWordModel
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    val chatRepository: ChatRepository by lazy { ChatRepository(database, clock, dispatchers, attachmentStore) }

    /** Chat turns run here, not in a ViewModel, so a reply carries on through rotation (C06, lessons L10). */
    val engineScope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatchers.default)

    /** Every tool Edith has (C08). Later chunks add theirs here: weather C09, web C10, location C11, Google C18/C19. */
    val toolRegistry: ToolRegistry by lazy {
        ToolRegistry(
            tools = listOf(
                CurrentTimeTool(), WeatherTool(openMeteo, deviceLocation),
                SearchWebTool(webSearch), ReadWebPageTool(webSearch.pages(PageReader(httpClients.plain, dispatchers))),
                // C18/C22: calendars across every account; C19/C22: email (send only after the owner confirms: D5)
                *calendarTools(accounts, calendarHandles, clock::zone).toTypedArray(),
                *mailTools(accounts, mailHandles).toTypedArray(),
                // C35: to-dos (Google Tasks), every change on a card (D11, D20)
                *taskTools(accounts, taskHandles, taskRanker, clock) { settingsRepository.current() }.toTypedArray(),
                // C25: the smart home (D10, D19)
                *homeTools(home).toTypedArray(),
                // C39: the printer through Jarvis, from anywhere: offered only while Jarvis lets this phone control it (D26)
                com.edith.app.home.PrinterControlTool(printerFeed) { accounts.jarvisConnection()?.printerInfo?.control == true },
            ),
            integrations = listOf(accounts, home),   // C22: offered while at least one account is connected; C25: one home service
        )
    }

    /**
     * Web search (C10, D3): DuckDuckGo, keyless; with an Ollama API key saved, Ollama's web search first. The key
     * stays in [secretStore].
     */
    val webSearchSetup: WebSearchSetup by lazy { WebSearchSetup(secretStore) }
    val ollamaWeb: OllamaWebSearch by lazy { OllamaWebSearch(httpClients.ktorSecure, dispatchers, apiKey = { webSearchSetup.key() }) }
    private val webSearch: WebSearch by lazy { WebSearch(DuckDuckGoSearch(httpClients.ktorSecure, dispatchers), ollamaWeb, webSearchSetup) }

    /** Weather data (C09): Open-Meteo, keyless. Shares the app's HTTP client, with its own 10 s timeouts. */
    val openMeteo: OpenMeteoClient by lazy { OpenMeteoClient(httpClients.ktorSecure, dispatchers) }

    /** Tool confirmations waiting for the owner's tap (C08 §6, D11). */
    val confirmations = Confirmations()

    /** Permissions tools ask for with Android's dialog, shown by the chat screen (C11). */
    val toolPermissions: ToolPermissions by lazy { ToolPermissions.android(app) }

    /** The phone's approximate location for "here" (C11): never stored, never sent to Ollama. */
    val deviceLocation: LocationProvider by lazy { AndroidLocationProvider(app, dispatchers) }

    /** System-prompt sections; later chunks add theirs here (attachments C15/C16, Google C17). */
    val promptSections: List<PromptSection> by lazy { listOf(DateTimeSection(), ToolGuidanceSection { toolRegistry }, AttachmentsSection(), EdithFilesSection()) }

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
            promptBuilder = PromptBuilder(clock) { promptSections }, contextBuilder = ContextBuilder(MediaAttachmentContext(attachmentStore)),
            turnRunner = turnRunner, listeners = replyListeners, scope = engineScope, localNetwork = localNetworkAccess,
            confirmations = confirmations, attachmentStore = attachmentStore, afterAttachments = { retention.apply() },
        )
    }

    /** The open conversation (C06b). Resolved at startup by [openDatabaseInBackground]. */
    val currentConversation: CurrentConversation by lazy {
        CurrentConversation(chatRepository, settingsRepository, chatEngine)
    }

    /** Where Edith's own voice is downloaded to (C36): app-private, not backed up. */
    private val voicesDir: File get() = File(app.filesDir, "voices")

    /** Downloads, checks and unpacks Edith's own voice, only while she is on screen (C36 §3). */
    val voiceDownloader: VoiceDownloader by lazy {
        VoiceDownloader(OwnVoices.default, voicesDir, http = { httpClients.ktorSecure.value }, visible = AppVisibility.visible, scope = appScope, io = dispatchers.io, freeBytes = { VoiceDownloader.allocatableBytes(app, voicesDir) })
    }

    /** Edith's own voice (C36): sherpa-onnx running a Piper voice, loaded when she first speaks with it. */
    val ownTts: SherpaTts by lazy {
        val handsFreeOn = AtomicBoolean(false)
        val tts = SherpaTts(
            loadSynth = { SherpaSynth.load(voiceDownloader.dir, OwnVoices.default.speakerId) },
            sink = AudioTrackSink(),
            focus = AndroidSpeechFocus(app.getSystemService(AudioManager::class.java)),
            keepLoaded = { handsFreeOn.get() },   // hands-free keeps listening, so keep the voice ready to answer
        )
        appScope.launch { settingsRepository.settings.collect { handsFreeOn.set(it.handsFreeEnabled) } }
        // A new download, or Delete: drop the old model and any "wouldn't start" problem.
        appScope.launch { voiceDownloader.state.map { it is OwnVoiceState.Ready }.distinctUntilChanged().drop(1).collect { tts.reset() } }
        tts
    }

    /** The voice Edith speaks with: the phone's or her own, by the `speech_voice` setting (C36 §4). */
    val choosingTts: ChoosingTts by lazy {
        ChoosingTts(
            phone = AndroidTts(app),
            own = ownTts,
            setting = settingsRepository.settings.map { it.speechVoice }.distinctUntilChanged(),
            ownReady = voiceDownloader.state.map { it is OwnVoiceState.Ready },
            scope = appScope,
        )
    }

    /** Edith's voice (C13): reads replies as they stream in, with the voice [choosingTts] picks. */
    val speechOutput: SpeechOutput by lazy { SpeechOutput(choosingTts, settingsRepository, appScope) }

    /** The chat's Play/Stop buttons (C07 §5). */
    val speechController: SpeechController get() = speechOutput

    /** Which input-row buttons exist (C07 §6). C15 turns on attach; C12 record, and dictation when the phone can. */
    val chatFeatures: ChatFeatures by lazy { ChatFeatures(attach = true, record = true, dictation = dictation.isAvailable) }

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
            say = { speechOutput.announce(it) },
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

    /** Prepares picked photos and audio files (C15). */
    val attachmentIntake: AttachmentIntake by lazy { AttachmentIntake(app.contentResolver, File(app.cacheDir, "pending"), dispatchers) }

    /** Attachments' files: `filesDir/attachments` (or `cacheDir/session` when keeping 0), C16. */
    val attachmentStore: FileAttachmentStore by lazy { FileAttachmentStore(app.filesDir, app.cacheDir) }

    /**
     * Google as an account provider (C17, D9; any number of accounts since C22). With Play services no tokens are
     * stored by the app; without it (C17b, D16) each account's browser sign-in keeps a refresh token, encrypted in
     * [secretStore].
     */
    /**
     * The browser sign-in, one at a time for the whole app (C17b; Google and Microsoft since C23): the pending sign-in
     * survives Edith being killed, and a code that arrives off screen waits for her (L36).
     */
    val browserSignIn: BrowserSignIn by lazy {
        BrowserSignIn(secretStore, clock, dispatchers, backLink = "${BuildConfig.APPLICATION_ID}://signed-in", appVisible = AppVisibility.visible)
    }

    /** Microsoft as an account provider (C23, D18): Outlook mail and calendars through Graph. */
    val microsoftAccounts: MicrosoftAccounts by lazy {
        MicrosoftAccounts(
            oauth = BrowserOAuth(httpClients.ktorSecure, dispatchers, MicrosoftOAuth.provider(BuildConfig.MICROSOFT_CLIENT_ID)),
            signInFlow = browserSignIn,
            secrets = secretStore,
            http = httpClients.secure,
            dispatchers = dispatchers,
            clock = clock,
            windowsZone = { zone -> android.icu.util.TimeZone.getWindowsID(zone.id) },
        )
    }

    val googleAccounts: GoogleAccounts by lazy {
        val browser = BrowserGoogle(
            oauth = BrowserOAuth(httpClients.ktorSecure, dispatchers, BuildConfig.GOOGLE_DESKTOP_CLIENT_ID, BuildConfig.GOOGLE_DESKTOP_CLIENT_SECRET),
            secrets = secretStore,
            clock = clock,
            dispatchers = dispatchers,
            backLink = "${BuildConfig.APPLICATION_ID}://signed-in",
            appVisible = AppVisibility.visible,
            signInFlow = browserSignIn,
        )
        GoogleAccounts(
            play = CredentialManagerSignIn(app),
            playAuthorizer = { email -> PlayServicesAuthorizer(app, email) },
            browser = browser,
            webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID,
            playServicesAvailable = {
                GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) == ConnectionResult.SUCCESS
            },
            http = httpClients.ktorSecure,
            clock = clock,
            dispatchers = dispatchers,
        )
    }

    /** iCloud as an account provider (C24): mail over IMAP/SMTP, calendars over CalDAV, an app-specific password. */
    val icloudAccounts: ICloudAccounts by lazy { ICloudAccounts(secretStore, httpClients.secure, dispatchers) }
    val jarvisAccounts: com.edith.app.jarvis.JarvisAccounts by lazy { com.edith.app.jarvis.JarvisAccounts(secretStore, httpClients.secure, dispatchers) }

    /** Every email-and-calendar account (C22, D17), saved in the `settings` file under `accounts`. */
    val accounts: Accounts by lazy { Accounts(DataStoreAccountStore(settingsStore.store), googleAccounts, appScope, microsoftAccounts, icloudAccounts, jarvisAccounts) }

    /** The 3D printer through the paired Jarvis (C39, D26): finds the printer Jarvis shares, and the one session the Printer page uses. */
    internal val printerFeed: com.edith.app.printer.feed.PrinterFeed by lazy { com.edith.app.printer.feed.PrinterFeed { accounts.jarvisConnection() } }
    internal val printerPrefs: com.edith.app.printer.PrinterPrefs by lazy { com.edith.app.printer.DataStorePrinterPrefs(settingsStore.store) }
    internal val printerSession: com.edith.app.printer.feed.PrinterSession by lazy { com.edith.app.printer.feed.PrinterSession(printerFeed, clock, appScope) }

    /**
     * The smart home (C25, D10): every connected service's devices, the home tools and the Home tab. Debug builds add
     * the Demo home's fake backend behind a switch in Settings → Home; release builds have none.
     */
    /** Every command sent to a device (C25 §8), kept in Room. */
    val homeCommandLog: RoomCommandLog by lazy { RoomCommandLog(database.homeCommandDao()) }

    /** Meross garage door openers (C26): the account's sign-in is kept encrypted in [secretStore]. */
    val meross: MerossService by lazy { MerossService(secretStore, MerossApi(httpClients.secure, clock), PahoMerossBus(dispatchers, clock), clock) }

    /** Ring doorbells and cameras (C27): the sign-in is kept encrypted in [secretStore]. */
    val ring: RingService by lazy { RingService(secretStore, RingApi(httpClients.secure), clock, rtc = com.edith.app.ring.WebRtcClient(app)) }   // C32: rtc is Ring live view

    /** Blink cameras (C29): the sign-in is kept encrypted in [secretStore]. */
    val blink: BlinkService by lazy { BlinkService(secretStore, BlinkApi(httpClients.secure, blinkEndpoints()), clock) }

    /**
     * Debug builds only: a pretend Blink on the PC for trying the sign-in, snapshots and clips on the emulator without an
     * account. It is used only when the debug app's private `files/blink-test-server.txt` holds its address
     * (`adb shell run-as com.edith.app.debug`); release builds never look.
     */
    private fun blinkEndpoints(): BlinkEndpoints {
        if (!BuildConfig.DEBUG) return BlinkEndpoints()
        val base = runCatching { File(app.filesDir, "blink-test-server.txt").readText().trim().trimEnd('/') }.getOrNull()?.takeIf { it.startsWith("http") }
            ?: return BlinkEndpoints()
        Log.w(LogTag.Home, "Blink is using a test server instead of Blink's own (debug build)")
        return BlinkEndpoints(oauth = base, tierInfo = "$base/rest/api/v1/users/tier_info", rest = { "$base/rest" })
    }

    /** Alexa devices (C30): the sign-in is kept encrypted in [secretStore]. */
    val alexa: AlexaService by lazy { AlexaService(secretStore, AlexaApi(httpClients.secure), clock) }

    /** A 3D printer's Moonraker on the home Wi-Fi (C34): its address and API key are kept encrypted in [secretStore]. */
    val printer: PrinterService by lazy { PrinterService(secretStore, MoonrakerApi(httpClients.ktorPlain), clock, com.edith.app.printer.feed.FeedPrinterRead(printerFeed)) }

    val home: Home by lazy {
        Home(
            DataStoreHomePrefs(settingsStore.store), homeCommandLog, appScope, clock,
            demo = if (BuildConfig.DEBUG) FakeHomeBackend(clock, DemoPicture::jpeg, live = runCatching { app.assets.open("demo-live.ts").use { it.readBytes() } to 8 }.getOrNull()) else null,
        ).also { h ->
            h.register(meross)   // C26
            h.register(ring)     // C27
            h.register(alexa)    // C30
            h.register(blink)    // C29
            h.register(printer)  // C34; C28, C31 register theirs here
        }
    }

    /** Short handles for listed events (`e1`) and found emails (`m1`): one sequence each for the app run (C22 trap 4). */
    val calendarHandles: CalendarHandles by lazy { CalendarHandles() }
    val mailHandles: MailHandles by lazy { MailHandles() }
    /** C35: to-do handles (`t1`…) and the suggested order, saved in `filesDir/task-ranks.json`. */
    val taskHandles: TaskHandles by lazy { TaskHandles() }
    val taskRanker: TaskRanker by lazy { TaskRanker(ollama, RankStore(java.io.File(app.filesDir, RankStore.FILE_NAME)), clock) }

    /** Keeps the newest N attachments each way (C16, O1). */
    val retention: Retention by lazy { Retention(database.attachmentDao(), attachmentStore, settingsRepository, clock, dispatchers) }

    /** Plays audio attachments in the chat; starting one stops Edith's voice. */
    val attachmentPlayer: AttachmentPlayer by lazy { AttachmentPlayer { speechOutput.stop() } }

    /**
     * Creates/opens edith.db on a background thread at launch (running any migration), so the first chat doesn't
     * wait for it, then picks the conversation to open.
     */
    fun openDatabaseInBackground() {
        appScope.launch {
            try {
                AudioRecorder.deleteLeftovers(recordingsDir)   // from a crash or a killed process mid-recording
                attachmentIntake.clearLeftovers()   // C15: unsent attachments from the last run
                File(app.cacheDir, "attachments").deleteRecursively()   // C15's one-run store, before C16
                retention.onStart()   // C16: session files, orphans, then the setting
                startAccounts()
                database.openHelper.writableDatabase
                Log.d(LogTag.App, "Database ready")
                currentConversation.resolve()
            } catch (e: Exception) {
                Log.e(LogTag.App, "Couldn't open the database", e)
            }
        }
        replyKeepAlive.watch(appScope)
        alertKeepAlive.watch(appScope)   // C33: starts watching the cameras when alerts are on
    }

    /**
     * C22: carries C17's one Google account over (once), then connects every account and checks each quietly (C17),
     * and picks up a browser sign-in an earlier run left waiting (C17b). A failure here never stops the database.
     */
    private suspend fun startAccounts() {
        try {
            AccountMigration(settingsStore.store, secretStore).run()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(LogTag.Accounts, "Couldn't carry the Google account over", e)
        }
        try {
            accounts.load()
            accounts.resumePending()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(LogTag.Accounts, "Couldn't load the accounts", e)
        }
        try {
            meross.load()   // C26: picks up the saved Meross sign-in
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(LogTag.Home, "Couldn't load the Meross sign-in", e)
        }
        try {
            ring.load()   // C27: picks up the saved Ring sign-in
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(LogTag.Home, "Couldn't load the Ring sign-in", e)
        }
        try {
            blink.load()   // C29: picks up the saved Blink sign-in
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(LogTag.Home, "Couldn't load the Blink sign-in", e)
        }
        try {
            alexa.load()   // C30: picks up the saved Alexa sign-in
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(LogTag.Home, "Couldn't load the Alexa sign-in", e)
        }
        try {
            printer.load()   // C34: picks up the saved printer
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(LogTag.Home, "Couldn't load the printer setup", e)
        }
    }

    /** Keeps a reply going when the owner leaves the app (D13): starts [ReplyService] as each turn begins. */
    val replyKeepAlive: ReplyKeepAlive by lazy { ReplyKeepAlive(chatEngine.state) { ReplyService.start(app) } }

    /** Camera alerts (C33): the owner's switches, the notifications, and the rule that starts watching. */
    val alertPrefs: AlertPrefs by lazy { DataStoreAlertPrefs(settingsStore.store) }
    val alertNotifier: AlertNotifier by lazy { AlertNotifier(app) }
    val activitySource: ActivitySource by lazy { ActivitySource { id, since -> home.activity(id, since) } }
    val alertKeepAlive: AlertKeepAlive by lazy { AlertKeepAlive(alertPrefs.choices) { AlertService.start(app) } }

    val secretStore: SecretStore by lazy { KeystoreSecretStore(app) }

    /**
     * The HTTP clients (C03 §5, C37), sharing one pool. Long read timeout: the first token after a model load can take
     * a while. Building TLS takes ~190 ms on the emulator, so they're lazy and first used from the IO dispatcher by
     * [KtorOllamaClient] — don't read them on the main thread.
     *
     * **Every service takes [HttpClients.secure] (HTTPS only)**, except the three that need plain HTTP: Ollama and the
     * 3D printer (addresses the owner types on their own network) and the web-page reader (public pages, no cloud
     * account). `HttpClientsWiringTest` keeps it that way.
     */
    val httpClients = HttpClients {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .socketFactory(TaggedSocketFactory())
            .build()
    }

    val ollama: OllamaClient by lazy { KtorOllamaClient(httpClients.ktorPlain, OllamaDtos.json, dispatchers) }
    val modelCatalog: ModelCatalog by lazy { ModelCatalog(ollama) }

    /** One DataStore per file per process, so the repository is created exactly once here (C04). */
    private val settingsStore: DataStoreSettingsRepository by lazy { DataStoreSettingsRepository.create(app) }
    val settingsRepository: SettingsRepository get() = settingsStore
    val modelSelection: ModelSelection by lazy { ModelSelection(settingsRepository, modelCatalog) }
    val localNetworkAccess: LocalNetworkAccess by lazy { AndroidLocalNetworkAccess(app) }

    private companion object {
        const val EARCON_VOLUME = 60
        const val EARCON_MS = 120
    }
}
