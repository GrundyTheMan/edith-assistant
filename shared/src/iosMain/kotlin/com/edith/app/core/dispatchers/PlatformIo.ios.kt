package com.edith.app.core.dispatchers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val platformIoDispatcher: CoroutineDispatcher = Dispatchers.Default
