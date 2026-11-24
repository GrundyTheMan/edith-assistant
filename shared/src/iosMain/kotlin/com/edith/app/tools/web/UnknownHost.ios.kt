package com.edith.app.tools.web

internal actual fun Throwable.isUnknownHost(): Boolean = false   // K4.5: the iPhone reports a missing host through its own error codes
