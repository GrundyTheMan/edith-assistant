package com.edith.app.core.net

import io.ktor.client.HttpClient

/**
 * The two HTTP clients the shared code asks for (K2.12; the C37 rule): [ktorSecure] is HTTPS only and carries anything with a token, key,
 * password or personal data; [ktorPlain] allows plain HTTP and is only for an address the owner typed on their own network (Ollama, the 3D
 * printer) and for public web pages. Android builds them over OkHttp (`HttpClients`), the iPhone over the Darwin engine. Built lazily:
 * the first use should be off the main thread.
 */
interface KtorClients {
    val ktorPlain: Lazy<HttpClient>
    val ktorSecure: Lazy<HttpClient>
}
