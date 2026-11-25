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
import com.edith.app.accounts.tools.FindFreeTimeTool
import com.edith.app.accounts.tools.ListCalendarEventsTool
import com.edith.app.accounts.tools.MailHandles
import com.edith.app.accounts.tools.ReadEmailTool
import com.edith.app.accounts.tools.SearchEmailTool
import com.edith.app.accounts.tools.ListTasksTool
import com.edith.app.accounts.tools.TaskHandles
import com.edith.app.google.browser.IosAuthSession
import com.edith.app.jarvis.DarwinPinnedHttp
import com.edith.app.jarvis.JarvisAccounts
import kotlinx.coroutines.launch
import platform.Foundation.NSBundle
import com.edith.app.chat.engine.ReplyListener
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.net.DarwinKtorClients
import com.edith.app.core.permissions.LocalNetworkAccess
import com.edith.app.core.permissions.ToolPermissions
import com.edith.app.core.secrets.IosSecretStore
import com.edith.app.core.secrets.SecretStore
import com.edith.app.core.time.SystemClock
import com.edith.app.data.db.EdithDatabase
import com.edith.app.data.db.open
import com.edith.app.settings.DataStoreSettingsRepository
import com.edith.app.settings.SettingsRepository
import com.edith.app.tools.context.CurrentTimeTool
import com.edith.app.tools.weather.WeatherTool
import com.edith.app.tools.web.PageReader
import com.edith.app.tools.web.ReadWebPageTool
import com.edith.app.tools.web.SearchWebTool
import com.edith.app.tools.web.publicHostAllowed
import com.edith.app.tools.framework.ToolRegistry
import com.edith.app.tools.location.Coordinates
import com.edith.app.tools.location.LocationProvider
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
)

/**
 * Builds the shared engine for the iPhone from Apple's own parts (K4.1; plan §4.2): the Keychain for secrets, the Darwin engine for HTTP, the
 * database, settings and attachments as files under Application Support (kept by the system and backed up, not shown in the Files app).
 * Call once per launch. Tools so far: the time, the weather in a named place ("here" needs Core Location, not built yet), web search and reading a page; the others join as their services move (K2.5 to K2.8).
 */
fun createIosApp(): IosApp {
    val dir = applicationSupportDir()
    val secrets = IosSecretStore()
    val settings = DataStoreSettingsRepository(PreferenceDataStoreFactory.createWithPath { "$dir/settings.preferences_pb".toPath() })
    val listeners = mutableListOf<ReplyListener>()
    val clients = DarwinKtorClients()
    val attachmentStore = PathAttachmentStore("$dir/files", "$dir/cache")
    lateinit var container: SharedContainer
    // The accounts (K4.6): the list in its own DataStore file, Google through the system's sign-in sheet, the work account through Jarvis (pinned certificate).
    // The client ID of Google's iOS client is in Info.plist (EdithGoogleClientId); without it Google says it is not set up.
    val accounts by lazy {
        val google = IosGoogleAccounts(googleClientId(), IosAuthSession(), secrets, SystemClock, clients.ktorSecure, AppDispatchers.Default)
        IosAccounts(
            DataStoreAccountStore(PreferenceDataStoreFactory.createWithPath { "$dir/accounts.preferences_pb".toPath() }),
            container.appScope, google, JarvisAccounts(secrets, DarwinPinnedHttp(), AppDispatchers.Default),
            com.edith.app.accounts.IosMicrosoftAccounts(microsoftClientId(), NSBundle.mainBundle.bundleIdentifier.orEmpty(), IosAuthSession(), secrets, clients.ktorSecure, AppDispatchers.Default, SystemClock),
        )
    }
    // The tools that need no permission: the time, the weather in a named place, web search and reading a page. The reader refuses names
    // that resolve to a private address (publicHostAllowed; Android does it inside its DNS lookup). With the accounts, only the tools that READ (the
    // calendar, email and to-dos): the ones that change something wait for the confirmation cards on the iPhone.
    val registry by lazy {
        val calendarHandles = CalendarHandles(); val mailHandles = MailHandles(); val taskHandles = TaskHandles()
        ToolRegistry(
            listOf(
                CurrentTimeTool(), WeatherTool(container.openMeteo, NoLocation), SearchWebTool(container.webSearch),
                ReadWebPageTool(container.webSearch.pages(PageReader(lazy { clients.pageReaderClient() }, AppDispatchers.Default, hostAllowed = ::publicHostAllowed))),
                ListCalendarEventsTool(accounts, calendarHandles), FindFreeTimeTool(accounts),
                SearchEmailTool(accounts, mailHandles), ReadEmailTool(accounts, mailHandles),
                ListTasksTool(accounts, taskHandles),
            ),
            integrations = listOf(accounts),
        )
    }
    container = SharedContainer(
        PlatformServices(
            dispatchers = AppDispatchers.Default,
            clock = SystemClock,
            database = lazy { EdithDatabase.open("$dir/edith.db") },
            settings = settings,
            secrets = secrets,
            http = clients,
            attachments = attachmentStore,
            location = NoLocation,
            toolPermissions = ToolPermissions { true },
            localNetwork = object : LocalNetworkAccess { override fun isGranted() = true },   // iOS asks itself, at the first local connection
        ),
        toolRegistry = { registry },
        replyListeners = { listeners.also { if (it.isEmpty()) it += container.conversationTitler } },
        afterAttachments = { container.retention.apply() },
    )
    container.appScope.launch { accounts.load() }   // connects and quietly checks every saved account
    return IosApp(container, secrets, settings, registry, IosAttachmentIntake("$dir/pending", AppDispatchers.Default), attachmentStore, accounts)
}

/** No location yet (K4.2 adds Core Location): the weather tool is not offered, so this is never asked. */
private object NoLocation : LocationProvider {
    override suspend fun current(): Outcome<Coordinates> = Outcome.Failure(AppError.PermissionDenied("location"))
    override suspend fun placeName(at: Coordinates): String? = null
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
