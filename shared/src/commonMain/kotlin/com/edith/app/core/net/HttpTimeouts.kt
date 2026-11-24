package com.edith.app.core.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout

/**
 * A copy of this client with its own timeouts (connect, read, whole call): what `OkHttpClient.newBuilder().connectTimeout(..)
 * .readTimeout(..).callTimeout(..)` gave. Shares the engine and its connections.
 */
fun HttpClient.withTimeouts(connectSeconds: Long, readSeconds: Long, callSeconds: Long): HttpClient = config {
    install(HttpTimeout) {
        connectTimeoutMillis = connectSeconds * 1000
        socketTimeoutMillis = readSeconds * 1000
        requestTimeoutMillis = callSeconds * 1000
    }
}
