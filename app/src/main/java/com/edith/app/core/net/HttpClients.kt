package com.edith.app.core.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient

/**
 * The app's two HTTP clients (C37). Cleartext HTTP is allowed app-wide (`network_security_config.xml`, because
 * Ollama lives at a LAN IP the owner types), so "all other traffic is HTTPS" used to depend on every service
 * remembering to check. Two of them forgot (Meross's redirect, iCloud's password). Now the client decides:
 *
 * - [secure]: **HTTPS only**. Anything that carries a token, key, password or personal data, or talks to a named online
 *   service. It cannot make a cleartext request, whatever an answer tells it to follow.
 * - [plain]: plain HTTP allowed. Only for an address the owner typed on their own network (Ollama, the 3D printer) and
 *   for public web pages. None of them has a cloud account: the printer's optional Moonraker API key is a LAN one, and
 *   goes in the clear when its address is `http://`, as Ollama's traffic does.
 *
 * A new service takes [secure], unless it must speak plain HTTP to a LAN device; its PR says why (01-architecture §9).
 * [ktorPlain] and [ktorSecure] are the same two clients seen through Ktor (K1.4: services move to it one package at a time), running
 * over those very OkHttp clients, so the policy, the pool, the timeouts, certificate pinning and the socket tags are the same ones.
 * Ktor follows redirects itself (OkHttp's own following is switched off by its engine): [ktorSecure] never goes from https to http,
 * [ktorPlain] does, as [plain] always did.
 *
 * Both share [base]'s connection pool, dispatcher, timeouts and socket factory, and are built lazily: building TLS takes
 * ~190 ms, so the first use should be on an IO thread (C03 §5).
 */
class HttpClients(base: () -> OkHttpClient) {
    val plain: Lazy<OkHttpClient> = lazy(base)

    val secure: Lazy<OkHttpClient> = lazy {
        plain.value.newBuilder()
            // Only TLS: OkHttp then fails a `http://` request with UnknownServiceException (an IOException), at connect time.
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            // An https → http redirect comes back as a plain 3xx answer, which the services report as an HTTP error.
            .followSslRedirects(false)
            .build()
    }

    val ktorPlain: Lazy<HttpClient> = lazy { over(plain.value, followHttpsToHttp = true) }
    val ktorSecure: Lazy<HttpClient> = lazy { over(secure.value) }

    companion object {
        /**
         * The same client with limits on connecting, waiting for data and the whole call (what OkHttp's `newBuilder().connectTimeout(..)
         * .readTimeout(..).callTimeout(..)` gave). Shares the engine and its connections.
         */
        fun HttpClient.withTimeouts(connectSeconds: Long, readSeconds: Long, callSeconds: Long): HttpClient = config {
            install(HttpTimeout) {
                connectTimeoutMillis = connectSeconds * 1000
                socketTimeoutMillis = readSeconds * 1000
                requestTimeoutMillis = callSeconds * 1000
            }
        }

        /** A Ktor client that sends everything through [okHttp] as it is configured. */
        fun over(okHttp: OkHttpClient, followHttpsToHttp: Boolean = false, redirectAnyMethod: Boolean = false): HttpClient = HttpClient(OkHttp) {
            engine { preconfigured = okHttp }
            install(HttpRedirect) {
                allowHttpsDowngrade = followHttpsToHttp
                checkHttpMethod = !redirectAnyMethod   // Ktor follows only GET and HEAD by default; WebDAV's PROPFIND and REPORT are redirected too
            }
            install(HttpTimeout)   // no limits of its own: the OkHttp ones apply, and a call can set its own with `timeout { }`
            expectSuccess = false   // services read the status themselves, as they did with OkHttp
        }
    }
}
