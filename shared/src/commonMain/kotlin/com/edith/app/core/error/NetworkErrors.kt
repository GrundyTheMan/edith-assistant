package com.edith.app.core.error

/** The connection timed out while waiting for data. */
internal expect fun Throwable.isSocketTimeout(): Boolean

/** The server couldn't be reached at all: refused, no route, or a name that doesn't resolve. */
internal expect fun Throwable.isUnreachable(): Boolean
