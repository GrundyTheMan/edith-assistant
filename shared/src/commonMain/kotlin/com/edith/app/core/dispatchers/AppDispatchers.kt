package com.edith.app.core.dispatchers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Injectable dispatcher configuration for tests and production. */
data class AppDispatchers(
    val main: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val default: CoroutineDispatcher
) {
    companion object {
        val Default = AppDispatchers(Dispatchers.Main, platformIoDispatcher, Dispatchers.Default)
    }
}

/** Where blocking work runs: the IO pool where there is one (Android, the desktop), the default pool on the iPhone, which has no IO pool. */
internal expect val platformIoDispatcher: CoroutineDispatcher
