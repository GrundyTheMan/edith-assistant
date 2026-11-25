package com.edith.app.printer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLParserException
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.Url
import kotlinx.io.IOException

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
        // ktor fills in a default host for "http://"; an empty address is not one.
        if (withScheme.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }.isBlank()) {
            return Result.failure(IllegalArgumentException("“$text” isn't an address Edith can use."))
        }
        val uri = try { Url(withScheme) } catch (e: IllegalArgumentException) {
            return Result.failure(IllegalArgumentException("“$text” isn't an address Edith can use."))
        }
        val scheme = uri.protocol.name.lowercase()
        if (scheme != "http" && scheme != "https") return Result.failure(IllegalArgumentException("The printer's address must start with http:// or https://."))
        val host = uri.host.takeIf { it.isNotBlank() } ?: return Result.failure(IllegalArgumentException("“$text” isn't an address Edith can use."))
        val origin = "$scheme://$host"
        return Result.success(
            if (uri.specifiedPort == 0 || uri.specifiedPort == MOONRAKER_PORT) PrinterUrls("$origin:$MOONRAKER_PORT", origin)
            else PrinterUrls("$origin:${uri.specifiedPort}", "$origin:${uri.specifiedPort}"),
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
class MoonrakerApi(
    private val http: Lazy<HttpClient>,
    private val io: kotlinx.coroutines.CoroutineDispatcher = com.edith.app.core.dispatchers.AppDispatchers.Default.io,
) {
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
        // The body is read in whatever thread the call resumes on, and Android kills the app for reading the network on the main thread
        // (`NetworkOnMainThreadException`, found with Alexa: small answers are already buffered, big or slow ones are not), so the whole
        // call and read run on the IO threads whoever asks.
        return withContext(io) {
            try {
                val r = http.value.request(url) {
                    this.method = HttpMethod.parse(method)
                    timeout { requestTimeoutMillis = timeoutS * 1000; connectTimeoutMillis = CONNECT_S * 1000 }
                    conn.apiKey?.takeIf { it.isNotBlank() }?.let { header("X-Api-Key", it) }
                    header(HttpHeaders.Accept, if (raw) "image/*" else "application/json")
                    if (method == "POST") setBody(ByteArrayContent(ByteArray(0)))
                }
                Raw.Done(r.status.value, r.bodyAsBytes(), r.headers[HttpHeaders.ContentType].orEmpty().substringBefore(';').trim().lowercase())
            } catch (e: IOException) {
                Raw.Unreachable
            } catch (e: IllegalArgumentException) {   // an address that isn't one
                Raw.Unreachable
            } catch (e: URLParserException) {
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
