package com.edith.app.core.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.URLProtocol
import kotlinx.io.IOException

/**
 * The two HTTP clients on the iPhone (K4.1), on Apple's own networking (the Darwin engine), with the limits Android's OkHttp ones have:
 * 5 s to connect, 180 s of silence allowed (the first token after a model load can take a while), no overall limit. [ktorSecure] refuses
 * anything that is not `https` before it is sent: the C37 rule, here as a plugin because Darwin has no connection-spec setting. (Redirects
 * are followed by iOS itself; App Transport Security stops an https to http hop on its own unless the app's `Info.plist` allows it, which
 * decision D49 will tighten.)
 */
class DarwinKtorClients : KtorClients {
    override val ktorPlain: Lazy<HttpClient> = lazy { newClient(httpsOnly = false) }
    override val ktorSecure: Lazy<HttpClient> = lazy { newClient(httpsOnly = true) }

    private fun newClient(httpsOnly: Boolean) = HttpClient(Darwin) {
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 180_000
            requestTimeoutMillis = HttpTimeoutConfigInfinite
        }
        if (httpsOnly) install(HttpsOnly)
        expectSuccess = false   // services read the status themselves
    }

    private companion object {
        /** Ktor's "no limit" for a whole request. */
        const val HttpTimeoutConfigInfinite = Long.MAX_VALUE
    }
}

/** Fails a request whose address is not https, before anything is sent (what OkHttp's TLS-only connection spec did on Android). */
private val HttpsOnly = createClientPlugin("HttpsOnly") {
    onRequest { request, _ ->
        if (request.url.protocol != URLProtocol.HTTPS) throw IOException("Refused a non-https request to ${request.url.host}: this client is HTTPS only.")
    }
}
