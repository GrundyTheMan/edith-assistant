package com.edith.app.core.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.URLProtocol
import platform.Foundation.NSHTTPCookieAcceptPolicy
import kotlinx.io.IOException

/**
 * The two HTTP clients on the iPhone (K4.1), on Apple's own networking (the Darwin engine), with the limits Android's OkHttp ones have:
 * 5 s to connect, 180 s of silence allowed (the first token after a model load can take a while), no overall limit. The silence limit is
 * Apple's own idle timeout, set on the session and on each request: Ktor's "infinite" request timeout means "not set" to it, and Apple's default
 * of 60 s then applied, so the first messages after Ollama had to load a model timed out (found on the owner's phone, build 102). [ktorSecure] refuses
 * anything that is not `https` before it is sent: the C37 rule, here as a plugin because Darwin has no connection-spec setting. (Redirects
 * are followed by iOS itself; App Transport Security stops an https to http hop on its own unless the app's `Info.plist` allows it, which
 * decision D49 will tighten.)
 */
class DarwinKtorClients : KtorClients {
    override val ktorPlain: Lazy<HttpClient> = lazy { newClient(httpsOnly = false) }
    override val ktorSecure: Lazy<HttpClient> = lazy { newClient(httpsOnly = true) }

    /** The page reader's client: 10 s in all, no redirects of its own (the reader follows them by hand so every hop's host is checked). */
    fun pageReaderClient(): HttpClient = HttpClient(Darwin) {
        engine {
            configureSession { timeoutIntervalForRequest = 10.0 }
            configureRequest { setTimeoutInterval(10.0) }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
            requestTimeoutMillis = 10_000
        }
        followRedirects = false
        expectSuccess = false
    }

    /** Alexa's client (K4.9): the secure one, but Apple's own cookie jar is off, because the Alexa client keeps and sends Amazon's cookies by hand across the redirects it follows itself. */
    fun alexaClient(): HttpClient = newClient(httpsOnly = true, keepCookiesByHand = true)

    /** Blink's client (K4.13): the same, since Blink's sign-in keeps a cookie jar of its own across the steps (Ktor's), and redirects are followed step by step by the client. */
    fun blinkClient(): HttpClient = newClient(httpsOnly = true, keepCookiesByHand = true)

    private fun newClient(httpsOnly: Boolean, keepCookiesByHand: Boolean = false) = HttpClient(Darwin) {
        engine {
            configureSession {
                timeoutIntervalForRequest = SILENCE_SECONDS   // Apple's idle timeout: seconds without any data
                timeoutIntervalForResource = 86_400.0         // a whole transfer, one day at most
                if (keepCookiesByHand) { HTTPShouldSetCookies = false; HTTPCookieAcceptPolicy = NSHTTPCookieAcceptPolicy.NSHTTPCookieAcceptPolicyNever }
            }
            configureRequest { setTimeoutInterval(SILENCE_SECONDS) }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 180_000
        }
        if (httpsOnly) install(HttpsOnly)
        expectSuccess = false   // services read the status themselves
    }

    private companion object {
        /** How long a request may go without any data arriving (Android: OkHttp's read timeout, 180 s). */
        const val SILENCE_SECONDS = 180.0
    }
}

/** Fails a request whose address is not https, before anything is sent (what OkHttp's TLS-only connection spec did on Android). */
private val HttpsOnly = createClientPlugin("HttpsOnly") {
    onRequest { request, _ ->
        if (request.url.protocol != URLProtocol.HTTPS) throw IOException("Refused a non-https request to ${request.url.host}: this client is HTTPS only.")
    }
}
