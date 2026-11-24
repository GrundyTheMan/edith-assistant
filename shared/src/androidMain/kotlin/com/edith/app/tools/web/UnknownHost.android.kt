package com.edith.app.tools.web

internal actual fun Throwable.isUnknownHost(): Boolean = this is java.net.UnknownHostException
