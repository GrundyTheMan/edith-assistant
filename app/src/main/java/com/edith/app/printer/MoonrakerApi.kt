package com.edith.app.printer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Where a printer's Moonraker (the API) and its web server (the cameras) are. */
data class PrinterUrls(val api: String, val web: String)

/** How to reach a printer: where it is, and the API key when it has one. */
data class PrinterConn(val urls: PrinterUrls, val apiKey: String? = null)

object PrinterAddress {
    const val MOONRAKER_PORT = 7125

    /**
     * The API and web addresses for what the owner typed (Jarvis's `normalizeAddress`): "192.168.4.86" is the API on :7125
     * and the web server on :80; "http://host:7125" is the same; any other explicit port (a proxy) serves both. A failure
     * says what is wrong, in words for the owner.
     */
    fun parse(typed: String): Result<PrinterUrls> {
        val text = typed.trim()
        if (text.isEmpty()) return Result.failure(IllegalArgumentException("Type the printer's address, e.g. 192.168.4.86."))
        val withScheme = if (Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(text)) text else "http://$text"
        val uri = try { URI(withScheme) } catch (e: URISyntaxException) {
            return Result.failure(IllegalArgumentException("“$text” isn't an address Edith can use."))
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Result.failure(IllegalArgumentException("The printer's address must start with http:// or https://."))
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return Result.failure(IllegalArgumentException("“$text” isn't an address Edith can use."))
        val origin = "$scheme://$host"
        return Result.success(
            if (uri.port == -1 || uri.port == MOONRAKER_PORT) PrinterUrls("$origin:$MOONRAKER_PORT", origin)
            else PrinterUrls("$origin:${uri.port}", "$origin:${uri.port}"),
        )
    }
}

/** What a printer answered. Never thrown: every call returns one. */
sealed interface PrinterReply<out T> {
    data class Ok<T>(val value: T) : PrinterReply<T>
    /** Nothing answered: the printer is off, or this phone isn't on its network (away from home). */
    data object Unreachable : PrinterReply<Nothing>
    /** It answered, but not with what was asked for; [why] is in words, [status] the HTTP status. */
    data class Failed(val why: String, val status: Int) : PrinterReply<Nothing>
}

/**
 * Moonraker's HTTP API, plain (Jarvis's `moonraker.ts`, C34). Every answer is `{"result": …}` or `{"error": {"message"}}`;
 * these unwrap the result. A printer is only reachable on the home network, so a call that gets no answer at all is
 * [PrinterReply.Unreachable], not an error.
 */
class MoonrakerApi(private val http: Lazy<OkHttpClient>) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(conn: PrinterConn, path: String, timeoutS: Long = CALL_S): PrinterReply<JsonElement> = result(request(conn, "GET", conn.urls.api + path, timeoutS))

    suspend fun post(conn: PrinterConn, path: String, timeoutS: Long = CALL_S): PrinterReply<JsonElement> = result(request(conn, "POST", conn.urls.api + path, timeoutS))

    /** A picture (a camera snapshot) from [url], an absolute address. */
    suspend fun image(conn: PrinterConn, url: String, timeoutS: Long = IMAGE_S): PrinterReply<ByteArray> {
        return when (val r = request(conn, "GET", url, timeoutS, raw = true)) {
            Raw.Unreachable -> PrinterReply.Unreachable
            is Raw.Done -> when {
                r.code !in 200..299 -> PrinterReply.Failed("The printer's camera answered ${r.code}", r.code)
                !r.type.startsWith("image/") || r.bytes.isEmpty() -> PrinterReply.Failed("The printer's camera didn't send a picture", r.code)
                else -> PrinterReply.Ok(r.bytes)
            }
        }
    }

    private sealed interface Raw {
        data object Unreachable : Raw
        class Done(val code: Int, val bytes: ByteArray, val type: String) : Raw
    }

    private suspend fun request(conn: PrinterConn, method: String, url: String, timeoutS: Long, raw: Boolean = false): Raw {
        val builder = try { Request.Builder().url(url) } catch (e: IllegalArgumentException) { return Raw.Unreachable }
        conn.apiKey?.takeIf { it.isNotBlank() }?.let { builder.header("X-Api-Key", it) }
        builder.header("Accept", if (raw) "image/*" else "application/json")
        val request = builder.method(method, if (method == "POST") ByteArray(0).toRequestBody() else null).build()
        // The body is read in whatever thread the call resumes on, and Android kills the app for reading the network on the main thread
        // (`NetworkOnMainThreadException`, found with Alexa: small answers are already buffered, big or slow ones are not), so the whole
        // call and read run on the IO threads whoever asks.
        return withContext(Dispatchers.IO) {
            try {
                http.value.newBuilder().callTimeout(timeoutS, TimeUnit.SECONDS).connectTimeout(CONNECT_S, TimeUnit.SECONDS).build().newCall(request).await().use { r ->
                    Raw.Done(r.code, r.body?.bytes() ?: ByteArray(0), r.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase())
                }
            } catch (e: IOException) {
                Raw.Unreachable
            }
        }
    }

    private fun result(r: Raw): PrinterReply<JsonElement> = when (r) {
        Raw.Unreachable -> PrinterReply.Unreachable
        is Raw.Done -> {
            val body = try { json.parseToJsonElement(r.bytes.decodeToString()) } catch (e: SerializationException) { null } catch (e: IllegalArgumentException) { null }
            val obj = body as? JsonObject
            when {
                r.code == 401 || r.code == 403 -> PrinterReply.Failed("The printer wants an API key; add it in Settings → Home", r.code)
                r.code !in 200..299 -> {
                    val message = ((obj?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
                    PrinterReply.Failed(message?.takeIf { it.isNotBlank() }?.take(200) ?: "The printer answered HTTP ${r.code}", r.code)
                }
                obj == null || "result" !in obj -> PrinterReply.Failed("The printer's answer wasn't what Moonraker sends", r.code)
                else -> PrinterReply.Ok(obj.getValue("result"))
            }
        }
    }

    companion object {
        /** A normal call; a printer on the home Wi-Fi answers in well under a second. */
        const val CALL_S = 5L
        const val IMAGE_S = 6L
        /** A phone away from home gets no answer: don't wait long to say so. */
        const val CONNECT_S = 3L
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, r, _ -> r.close() }
    })
}
