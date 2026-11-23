package com.edith.app.core.error

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal actual fun Throwable.isSocketTimeout(): Boolean = this is SocketTimeoutException

internal actual fun Throwable.isUnreachable(): Boolean = this is ConnectException || this is UnknownHostException || this is NoRouteToHostException
