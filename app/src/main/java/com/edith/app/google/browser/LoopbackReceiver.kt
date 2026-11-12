package com.edith.app.google.browser

import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import kotlin.time.Duration

/**
 * Where Google's sign-in page sends the browser back (C17b §1): a one-shot HTTP listener on 127.0.0.1 only, never
 * on the network. The first request carrying `code=` or `error=` is the answer; anything else (a favicon) gets a 404.
 * Request lines are never logged: the code in them is a credential.
 */
class LoopbackReceiver private constructor(
    private val server: ServerSocket,
    private val io: CoroutineDispatcher,
    /** The **Back to Edith** link on the page: `${applicationId}://signed-in`. It only brings Edith forward. */
    private val backLink: String,
) : Closeable {
    val port: Int get() = server.localPort

    /** The `redirect_uri` for this listener. */
    val redirectUri: String get() = "http://127.0.0.1:$port"

    private val clients: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())

    /**
     * Waits for the redirect, up to [timeout]. Returns the whole address it arrived at
     * (`http://127.0.0.1:<port>/?…`), or null on timeout. The listener is closed afterwards in every case,
     * cancellation included.
     */
    suspend fun await(timeout: Duration): String? = try {
        withContext(io) {
            withTimeoutOrNull(timeout) {
                coroutineScope {
                    val answer = CompletableDeferred<String>()
                    launch {
                        server.soTimeout = ACCEPT_POLL_MS   // so the loop notices cancellation
                        while (isActive && !answer.isCompleted) {
                            val socket = try {
                                server.accept()
                            } catch (e: SocketTimeoutException) {
                                continue
                            } catch (e: IOException) {
                                break   // closed
                            }
                            clients += socket
                            launch { serve(socket, answer) }
                        }
                    }
                    try {
                        answer.await()
                    } finally {
                        close()   // unblocks the accept loop and any connection still reading
                    }
                }
            }
        }
    } finally {
        close()
    }

    override fun close() {
        runCatching { server.close() }
        synchronized(clients) { clients.forEach { runCatching { it.close() } }; clients.clear() }
    }

    private fun serve(socket: Socket, answer: CompletableDeferred<String>) {
        try {
            socket.use {
                it.soTimeout = READ_TIMEOUT_MS
                val target = readRequestTarget(it.getInputStream())
                val url = target?.let { t -> "$redirectUri$t".toHttpUrlOrNull() }
                val out = it.getOutputStream()
                if (url != null && (url.queryParameter("code") != null || url.queryParameter("error") != null) && !answer.isCompleted) {
                    val page = page(signedIn = url.queryParameter("code") != null)
                    out.write(response("200 OK", "text/html; charset=utf-8", page))
                    out.flush()
                    Log.d(LogTag.Google, "Browser sign-in: the redirect arrived")
                    answer.complete("$redirectUri$target")
                } else {
                    out.write(response("404 Not Found", "text/plain; charset=utf-8", "Not found"))
                    out.flush()
                }
            }
        } catch (e: IOException) {
            // A connection that closed or stalled (Chrome opens spare ones): nothing to answer.
        } finally {
            clients -= socket
        }
    }

    /** The target of an HTTP request line (`GET <target> HTTP/1.1`), reading at most [MAX_REQUEST_BYTES]. */
    private fun readRequestTarget(input: InputStream): String? {
        val buffer = ByteArray(MAX_REQUEST_BYTES)
        var size = 0
        while (size < buffer.size) {
            val n = input.read(buffer, size, buffer.size - size)
            if (n < 0) break
            size += n
            if (String(buffer, 0, size, Charsets.ISO_8859_1).contains("\r\n")) break
        }
        val firstLine = String(buffer, 0, size, Charsets.ISO_8859_1).substringBefore("\r\n")
        val parts = firstLine.split(' ')
        if (parts.size < 3 || parts[0] != "GET" || !parts[1].startsWith("/")) return null
        return parts[1]
    }

    private fun page(signedIn: Boolean): String {
        val heading = if (signedIn) "Signed in." else "Sign-in didn't finish."
        val text = if (signedIn) "Go back to Edith to finish." else "Go back to Edith to see why."
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Edith</title>
            |<style>body{font-family:sans-serif;margin:2em;line-height:1.5}a{display:inline-block;margin-top:1em;padding:.6em 1.2em;border-radius:1.5em;background:#4a5bd4;color:#fff;text-decoration:none}</style></head>
            |<body><h1>$heading</h1><p>$text</p><a href="$backLink">Back to Edith</a><p>Then switch back to Edith if the link doesn't open it.</p></body></html>""".trimMargin()
    }

    private fun response(status: String, type: String, body: String): ByteArray {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        return head.toByteArray(Charsets.ISO_8859_1) + bytes
    }

    companion object {
        private const val ACCEPT_POLL_MS = 250
        private const val READ_TIMEOUT_MS = 10_000
        private const val MAX_REQUEST_BYTES = 8 * 1024

        /** Opens a listener on 127.0.0.1 at [port] (0: the OS picks one). Throws [IOException] if it can't. */
        fun open(io: CoroutineDispatcher, backLink: String, port: Int = 0): LoopbackReceiver =
            LoopbackReceiver(ServerSocket(port, 8, InetAddress.getByName("127.0.0.1")), io, backLink)
    }
}
