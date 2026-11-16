package com.edith.app.core.net

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
}
