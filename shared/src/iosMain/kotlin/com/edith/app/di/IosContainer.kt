@file:OptIn(ExperimentalForeignApi::class)

package com.edith.app.di

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.edith.app.attachments.PathAttachmentStore
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
class IosApp(val container: SharedContainer, val secrets: SecretStore, val settings: SettingsRepository, val registry: ToolRegistry)

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
    lateinit var container: SharedContainer
    // The tools that need no permission and no account: the time, the weather in a named place, web search and reading a page. The reader refuses names
    // that resolve to a private address (publicHostAllowed; Android does it inside its DNS lookup).
    val registry by lazy { ToolRegistry(listOf(CurrentTimeTool(), WeatherTool(container.openMeteo, NoLocation), SearchWebTool(container.webSearch), ReadWebPageTool(container.webSearch.pages(PageReader(lazy { clients.pageReaderClient() }, AppDispatchers.Default, hostAllowed = ::publicHostAllowed))))) }
    container = SharedContainer(
        PlatformServices(
            dispatchers = AppDispatchers.Default,
            clock = SystemClock,
            database = lazy { EdithDatabase.open("$dir/edith.db") },
            settings = settings,
            secrets = secrets,
            http = clients,
            attachments = PathAttachmentStore("$dir/files", "$dir/cache"),
            location = NoLocation,
            toolPermissions = ToolPermissions { true },
            localNetwork = object : LocalNetworkAccess { override fun isGranted() = true },   // iOS asks itself, at the first local connection
        ),
        toolRegistry = { registry },
        replyListeners = { listeners.also { if (it.isEmpty()) it += container.conversationTitler } },
        afterAttachments = { container.retention.apply() },
    )
    return IosApp(container, secrets, settings, registry)
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
