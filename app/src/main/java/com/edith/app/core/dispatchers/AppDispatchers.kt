package com.edith.app.core.dispatchers

import kotlinx.coroutines.CoroutineDispatcher

/** Injectable dispatcher configuration for tests and production. */
data class AppDispatchers(
    val main: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val default: CoroutineDispatcher
) {
    companion object {
        val Default = AppDispatchers(kotlinx.coroutines.Dispatchers.Main, kotlinx.coroutines.Dispatchers.IO, kotlinx.coroutines.Dispatchers.Default)
    }
}
