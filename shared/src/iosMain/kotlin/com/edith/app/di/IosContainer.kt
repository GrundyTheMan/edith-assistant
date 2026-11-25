@file:OptIn(ExperimentalForeignApi::class)

package com.edith.app.di

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.edith.app.attachments.AttachmentFiles
import com.edith.app.attachments.AttachmentIntake
import com.edith.app.attachments.IosAttachmentIntake
import com.edith.app.attachments.PathAttachmentStore
import com.edith.app.accounts.DataStoreAccountStore
import com.edith.app.accounts.IosAccounts
import com.edith.app.accounts.IosGoogleAccounts
import com.edith.app.accounts.tools.CalendarHandles
import com.edith.app.accounts.tools.calendarTools
import com.edith.app.accounts.tools.mailTools
import com.edith.app.accounts.tools.taskTools
import com.edith.app.accounts.tasks.RankStore
import com.edith.app.accounts.tasks.TaskRanker
import com.edith.app.accounts.tools.MailHandles
import com.edith.app.accounts.tools.TaskHandles
import com.edith.app.google.browser.IosAuthSession
import com.edith.app.jarvis.DarwinPinnedHttp
import com.edith.app.jarvis.JarvisAccounts
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import com.edith.app.chat.engine.ReplyListener
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.Outcome
import com.edith.app.core.net.DarwinKtorClients
import com.edith.app.core.permissions.LocalNetworkAccess
import com.edith.app.core.permissions.ToolPermissions
import com.edith.app.core.secrets.IosSecretStore
import com.edith.app.core.secrets.SecretStore
import com.edith.app.core.time.SystemClock
import com.edith.app.data.db.EdithDatabase
import com.edith.app.data.db.MessageSource
import com.edith.app.data.db.open
import com.edith.app.voice.handsfree.HandsFree
import com.edith.app.voice.handsfree.TapWakeListener
import com.edith.app.voice.handsfree.iosEarcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.edith.app.alexa.AlexaApi
import com.edith.app.alexa.AlexaService
import com.edith.app.home.PrinterControlTool
import com.edith.app.printer.DataStorePrinterPrefs
import com.edith.app.printer.feed.PrinterFeed
import com.edith.app.printer.feed.PrinterSession
import com.edith.app.blink.BlinkApi
import com.edith.app.blink.BlinkService
import com.edith.app.meross.MerossApi
import com.edith.app.meross.MerossService
import com.edith.app.meross.MqttMerossBus
import com.edith.app.ring.RingApi
import com.edith.app.ring.RingService
import com.edith.app.home.DataStoreHomePrefs
import com.edith.app.home.Home
import com.edith.app.home.RoomCommandLog
import com.edith.app.home.homeTools
import com.edith.app.printer.MoonrakerApi
import com.edith.app.printer.PrinterService
import com.edith.app.settings.DataStoreSettingsRepository
import com.edith.app.settings.SettingsRepository
import com.edith.app.settings.Settings
import com.edith.app.tools.context.CurrentTimeTool
import com.edith.app.voice.capture.IosRecorder
import com.edith.app.voice.capture.NoSpeechInput
import com.edith.app.voice.capture.OllamaTranscriber
import com.edith.app.voice.capture.VoiceMemos
import com.edith.app.voice.speech.IosTtsEngine
import com.edith.app.voice.speech.SpeechOutput
import com.edith.app.tools.weather.WeatherTool
import com.edith.app.tools.web.PageReader
import com.edith.app.tools.web.ReadWebPageTool
import com.edith.app.tools.web.SearchWebTool
import com.edith.app.tools.web.publicHostAllowed
import com.edith.app.tools.framework.ToolRegistry
import com.edith.app.tools.location.IosLocationProvider
import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/** What the iPhone app keeps for the chat engine (K4.1): the shared container, and the two platform pieces a screen also needs. */
class IosApp(
    val container: SharedContainer,
    val secrets: SecretStore,
    val settings: SettingsRepository,
    val registry: ToolRegistry,
    /** Prepares picked photos (K4.3); its folder is emptied at startup. */
    val intake: AttachmentIntake,
    /** Where sent photos are kept and read back from (the chat shows them). */
    val attachments: AttachmentFiles,
    /** The connected accounts (K4.6): Google through the sign-in sheet, and the work account through Jarvis. */
    val accounts: IosAccounts,
    /** The smart home (K4.9): its services, for the More tab's forms. */
    val home: Home,
    /** The Printer page's data: Jarvis's printer feed (K3.4). */
    val printerSession: com.edith.app.printer.feed.PrinterSession,
    val printerPrefs: com.edith.app.printer.PrinterPrefs,
    /** Camera alerts while the app is open, and While you were away (K4.16). */
    val alerts: com.edith.app.alerts.ForegroundAlerts,
    val alertPrefs: com.edith.app.alerts.AlertPrefs,
    /** Edith's voice (K4.3): the Play button on a message speaks through it. Not a reply listener yet: nothing reads replies aloud by itself until there is a setting for it. */
    val speech: SpeechOutput,
    /** Voice memos (K4.3): record, have the model transcribe, send as the owner's message. */
    val voiceMemos: VoiceMemos,
    /** Plays an audio attachment from its bubble (K4.3). */
    val player: com.edith.app.attachments.AttachmentPlayer,
    /** Dictation into the text field (K4.3): Apple speech recognition. Not the voice memos path, which records and lets the model transcribe. */
    val dictation: com.edith.app.voice.capture.SpeechInput,
    /** Conversation mode (K4.11): tap Talk, ask, hear the answer, follow up. */
    val handsFree: HandsFree,
)

/**
 * Builds the shared engine for the iPhone from Apple's own parts (K4.1; plan §4.2): the Keychain for secrets, the Darwin engine for HTTP, the
 * database, settings and attachments as files under Application Support (kept by the system and backed up, not shown in the Files app).
 * Call once per launch. Tools so far: the time, the weather in a named place or "here" (Core Location), web search and reading a page; the others join as their services move (K2.5 to K2.8).
 */
fun createIosApp(): IosApp {
    val dir = applicationSupportDir()
    val secrets = IosSecretStore()
    val settings = DataStoreSettingsRepository(PreferenceDataStoreFactory.createWithPath { "$dir/settings.preferences_pb".toPath() }, defaults = Settings(speakReplies = false))
    val listeners = mutableListOf<ReplyListener>()
    val clients = DarwinKtorClients()
    val location = IosLocationProvider()
    val attachmentStore = PathAttachmentStore("$dir/files", "$dir/cache")
    val database = lazy { EdithDatabase.open("$dir/edith.db") }
    lateinit var container: SharedContainer
    // The accounts (K4.6): the list in its own DataStore file, Google through the system's sign-in sheet, the work account through Jarvis (pinned certificate).
    // The client ID of Google's iOS client is in Info.plist (EdithGoogleClientId); without it Google says it is not set up.
    val accounts by lazy {
        val google = IosGoogleAccounts(googleClientId(), IosAuthSession(), secrets, SystemClock, clients.ktorSecure, AppDispatchers.Default)
        IosAccounts(
            DataStoreAccountStore(PreferenceDataStoreFactory.createWithPath { "$dir/accounts.preferences_pb".toPath() }),
            container.appScope, google, JarvisAccounts(secrets, DarwinPinnedHttp(), AppDispatchers.Default),
            com.edith.app.accounts.IosMicrosoftAccounts(microsoftClientId(), NSBundle.mainBundle.bundleIdentifier.orEmpty(), IosAuthSession(), secrets, clients.ktorSecure, AppDispatchers.Default, SystemClock),
            com.edith.app.accounts.IosICloudAccounts(secrets, clients.ktorSecure, com.edith.app.icloud.mail.NsStreamTransportFactory(), AppDispatchers.Default),
        )
    }
    // The tools that need no permission: the time, the weather in a named place, web search and reading a page. The reader refuses names
    // that resolve to a private address (publicHostAllowed; Android does it inside its DNS lookup). With the accounts, the calendar, email and to-do
    // tools: those that change something (create, send, complete, delete) wait for the owner's tap on a confirmation card, which the shared chat shows.
    // The smart home (K4.9): the printer on the home Wi-Fi so far (Meross, Ring, Alexa and Blink register here as they move). The sign-in forms are on the More tab.
    val printer = PrinterService(secrets, MoonrakerApi(clients.ktorPlain), SystemClock)
    val alexa = AlexaService(secrets, AlexaApi(lazy { clients.alexaClient() }), SystemClock)
    val printerFeed = PrinterFeed { accounts.jarvisConnection() }
    val printerPrefs = DataStorePrinterPrefs(settings.store)
    val ring = RingService(secrets, RingApi(clients.ktorSecure), SystemClock)   // no live view yet: that needs WebRTC on the iPhone
    val blink = BlinkService(secrets, BlinkApi(lazy { clients.blinkClient() }), SystemClock)   // no live view yet: the stream server needs a TLS socket the iPhone does not have here
    val meross = MerossService(secrets, MerossApi(clients.ktorSecure, SystemClock), MqttMerossBus(com.edith.app.icloud.mail.NsStreamTransportFactory(), AppDispatchers.Default, SystemClock), SystemClock)
    val home by lazy {
        Home(DataStoreHomePrefs(settings.store), RoomCommandLog(database.value.homeCommandDao()), container.appScope, SystemClock).also { it.register(printer); it.register(alexa); it.register(ring); it.register(blink); it.register(meross) }
    }
    val registry by lazy {
        val calendarHandles = CalendarHandles(); val mailHandles = MailHandles(); val taskHandles = TaskHandles()
        val ranker = TaskRanker(container.ollama, RankStore("$dir/${RankStore.FILE_NAME}"), SystemClock)
        ToolRegistry(
            listOf(
                CurrentTimeTool(), WeatherTool(container.openMeteo, location), SearchWebTool(container.webSearch),
                ReadWebPageTool(container.webSearch.pages(PageReader(lazy { clients.pageReaderClient() }, AppDispatchers.Default, hostAllowed = ::publicHostAllowed))),
            ) + calendarTools(accounts, calendarHandles, SystemClock::zone) + mailTools(accounts, mailHandles) +
                taskTools(accounts, taskHandles, ranker, SystemClock) { settings.current() } + homeTools(home) +
                // The printer through Jarvis (C39), offered only while Jarvis lets this phone control it; every command waits for a confirmation card.
                PrinterControlTool(printerFeed) { accounts.jarvisConnection()?.printerInfo?.control == true },
            integrations = listOf(accounts, home),
        )
    }
    container = SharedContainer(
        PlatformServices(
            dispatchers = AppDispatchers.Default,
            clock = SystemClock,
            database = database,
            settings = settings,
            secrets = secrets,
            http = clients,
            attachments = attachmentStore,
            location = location,
            toolPermissions = ToolPermissions { true },
            localNetwork = object : LocalNetworkAccess { override fun isGranted() = true },   // iOS asks itself, at the first local connection
        ),
        toolRegistry = { registry },
        replyListeners = { listeners },
        afterAttachments = { container.retention.apply() },
    )
    container.appScope.launch { accounts.load() }   // connects and quietly checks every saved account
    val printerSession = PrinterSession(printerFeed, SystemClock, container.appScope)
    val alertPrefs = com.edith.app.alerts.DataStoreAlertPrefs(settings.store)
    val alerts = com.edith.app.alerts.ForegroundAlerts({ id, since -> home.activity(id, since) }, alertPrefs, SystemClock, container.appScope).also { it.start() }
    container.appScope.launch { printer.load() }   // picks up a saved printer
    container.appScope.launch { alexa.load() }   // picks up a saved Alexa sign-in
    container.appScope.launch { ring.load() }   // picks up a saved Ring sign-in
    container.appScope.launch { blink.load() }   // picks up a saved Blink sign-in
    container.appScope.launch { meross.load() }   // picks up a saved Meross sign-in
    val speech = SpeechOutput(IosTtsEngine(), settings, container.appScope)
    // Replies are read aloud only while Read replies aloud is on, which it is not until the owner turns it on (the More tab).
    listeners += container.conversationTitler
    listeners += speech
    val voiceMemos = VoiceMemos(
        recorder = IosRecorder("$dir/recordings"),
        transcriber = OllamaTranscriber(container.ollama, settings, AppDispatchers.Default),
        liveSpeech = NoSpeechInput,
        modelHears = {
            // Unknown (server unreachable, no model): try the model; its error is clearer than a fallback.
            val s = settings.current()
            val caps = s.model?.let { container.modelCatalog.capabilities(s.serverUrl, it) }
            caps !is Outcome.Success || "audio" in caps.value
        },
        send = { id, text -> container.chatEngine.send(id, text, MessageSource.VoiceMemo) },
        stopSpeaking = { speech.stop() },
        scope = container.appScope,
        dispatchers = AppDispatchers.Default,
    )
    // Conversation mode (K4.11): the hands-free loop with a Talk button in place of the wake word (the iPhone has no wake-word engine yet).
    val handsFree = HandsFree(
        wake = TapWakeListener,
        recognizer = com.edith.app.voice.capture.IosSpeechInput(),
        engineState = container.chatEngine.state,
        speaking = speech.speakingMessageId,
        stopSpeaking = { speech.stop() },
        cancelReply = { container.chatEngine.cancel() },
        send = { text -> container.chatEngine.send(container.currentConversation.await(), text, MessageSource.HandsFree) },
        earcon = { iosEarcon() },
        settings = settings,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        phrase = "Talk",
        say = { speech.announce(it) },
        wakeIsTap = true,
    )
    return IosApp(container, secrets, settings, registry, IosAttachmentIntake("$dir/pending", AppDispatchers.Default), attachmentStore, accounts, home, printerSession, printerPrefs, alerts, alertPrefs, speech, voiceMemos, com.edith.app.attachments.IosAttachmentPlayer { speech.stop() }, com.edith.app.voice.capture.IosSpeechInput(), handsFree)
}

/** The app's private folder for data, made on first use. */
private fun applicationSupportDir(): String {
    val url: NSURL? = NSFileManager.defaultManager.URLForDirectory(
        directory = NSApplicationSupportDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )
    val path = requireNotNull(url?.path) { "No Application Support folder" }
    NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
    return path
}

/** The client ID of Google's iOS client from Info.plist (`EdithGoogleClientId`), or empty when the build has none. */
private fun googleClientId(): String = (NSBundle.mainBundle.objectForInfoDictionaryKey("EdithGoogleClientId") as? String).orEmpty().trim()

/** The client ID of Edith's Microsoft app registration from Info.plist (`EdithMicrosoftClientId`), or empty when the build has none. */
private fun microsoftClientId(): String = (NSBundle.mainBundle.objectForInfoDictionaryKey("EdithMicrosoftClientId") as? String).orEmpty().trim()
