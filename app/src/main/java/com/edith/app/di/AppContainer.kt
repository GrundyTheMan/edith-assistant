package com.edith.app.di

import android.app.Application
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.secrets.KeystoreSecretStore
import com.edith.app.core.secrets.SecretStore

/** Builds and holds the app's long-lived objects. Later chunks add fields here. */
class AppContainer(val app: Application) {
    val dispatchers: AppDispatchers = AppDispatchers.Default
    val secretStore: SecretStore by lazy { KeystoreSecretStore(app) }
}

object SystemClock {
    fun now(): Long = java.lang.System.currentTimeMillis()
}
