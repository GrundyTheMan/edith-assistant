package com.edith.app.di

import android.app.Application
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

    /** Creates/opens edith.db on a background thread at launch, so the first chat doesn't wait for it. */
    fun openDatabaseInBackground() {
        appScope.launch {
            try {
                database.openHelper.writableDatabase
                Log.d(LogTag.App, "Database ready")
            } catch (e: Exception) {
                Log.e(LogTag.App, "Couldn't open the database", e)
            }
        }
    }

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
