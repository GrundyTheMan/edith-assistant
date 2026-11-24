package com.edith.app.core.error

internal actual fun Throwable.isClassLoadFailure(): Boolean = this is LinkageError
