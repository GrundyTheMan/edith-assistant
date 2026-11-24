package com.edith.app.core.error

/** A class that failed to load (Java's `LinkageError`, e.g. an `ExceptionInInitializerError`): a bug, but one that must fail a turn, not the app (L25). The iPhone has no such thing. */
internal expect fun Throwable.isClassLoadFailure(): Boolean
