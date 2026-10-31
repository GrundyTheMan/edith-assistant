package com.edith.app.di

import android.app.Application
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.secrets.KeystoreSecretStore
import com.edith.app.core.secrets.SecretStore
import com.edith.app.core.time.Clock
import com.edith.app.core.time.SystemClock
import com.edith.app.ollama.ModelCatalog
import com.edith.app.ollama.OkHttpOllamaClient
import com.edith.app.ollama.OllamaClient
import com.edith.app.ollama.OllamaDtos
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Builds and holds the app's long-lived objects. Later chunks add fields here. Created once, by [com.edith.app.EdithApp]. */
class AppContainer(val app: Application) {
    init {
        Log.d(LogTag.App, "AppContainer created")
    }

    val dispatchers: AppDispatchers = AppDispatchers.Default
    val clock: Clock = SystemClock
    val secretStore: SecretStore by lazy { KeystoreSecretStore(app) }

    /**
     * One shared HTTP client (C03 §5). Long read timeout: the first token after a model load can take a while.
     * Lazy: building it sets up TLS (~20 ms), which shouldn't delay app start.
     */
    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    val ollama: OllamaClient by lazy { OkHttpOllamaClient(okHttp, OllamaDtos.json, dispatchers) }
    val modelCatalog: ModelCatalog by lazy { ModelCatalog(ollama) }
}
