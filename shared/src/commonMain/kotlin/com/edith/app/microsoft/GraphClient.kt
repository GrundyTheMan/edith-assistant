package com.edith.app.microsoft

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.ParametersBuilder
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.http.withCharset
import io.ktor.utils.io.charsets.Charsets
import kotlinx.io.IOException
import kotlin.time.TimeSource
import kotlin.coroutines.resumeWithException

/**
 * One Microsoft account's Microsoft Graph (C23 §2–4), with a token from its [MicrosoftAccountAuth] for each call.
 * 401 refreshes once; 403 asks for signing in again; 429 waits for `Retry-After` once (at most 10 s). [tool] and
 * [service] word the errors ("email", "Outlook"). Request and response bodies are never logged.
 */
class GraphClient(
    private val http: Lazy<HttpClient>,
    private val auth: GraphAuth,
    private val dispatchers: AppDispatchers,
    private val tool: String,
    private val service: String,
    private val notFound: String,
    private val baseUrl: String = BASE_URL,
    /** Waits [ms] before a 429 retry; tests don't sleep. */
    private val sleep: suspend (ms: Long) -> Unit = { delay(it) },
) {
    fun url(vararg segments: String, query: ParametersBuilder.() -> Unit = {}): Url =
        URLBuilder(baseUrl.trimEnd('/')).apply {
            appendPathSegments(segments.toList(), encodeSlash = true)   // Graph ids can hold '/', '+' and '=': each is encoded
            parameters.query()
        }.build()

    suspend fun <T> get(url: Url, headers: Map<String, String> = emptyMap(), parse: (String) -> T): Outcome<T> =
        call("GET", url, null, headers, parse)

    suspend fun <T> send(method: String, url: Url, body: String?, headers: Map<String, String> = emptyMap(), parse: (String) -> T): Outcome<T> =
        call(method, url, body, headers, parse)

    private suspend fun <T> call(method: String, url: Url, body: String?, headers: Map<String, String>, parse: (String) -> T): Outcome<T> =
        auth.authorized { token -> execute(Sent(HttpMethod.parse(method), url, token, body, headers), parse, retryAfter429 = true) }

    /** What one request is made of, kept so a 429 can send it again. */
    private class Sent(val method: HttpMethod, val url: Url, val token: String, val body: String?, val headers: Map<String, String>)

    private suspend fun <T> execute(request: Sent, parse: (String) -> T, retryAfter429: Boolean): GraphCall<T> {
        val kind = requestKind(request.method.value, request.url)
        val started = TimeSource.Monotonic.markNow()
        val answer: Answer<T> = try {
            // A cancelled read (one account's 10 s timeout, C22 §4) cancels the HTTP call too.
            val response = http.value.request(request.url) {
                method = request.method
                header(HttpHeaders.Authorization, "Bearer ${request.token}")
                request.headers.forEach { (k, v) -> header(k, v) }
                if (request.body != null) setBody(TextContent(request.body, JSON_TYPE))
                else if (request.method == HttpMethod.Post) setBody(EMPTY_BODY)
            }
            val body = response.bodyAsText()
            val code = response.status.value
            Log.d(LogTag.Accounts, "$service: $kind HTTP $code in ${started.elapsedNow().inWholeMilliseconds} ms")
            when {
                        response.status.isSuccess() -> Done(GraphCall.Ok(parse(body)))
                        code == 401 -> Done(GraphCall.Unauthorized)
                        code == 403 -> Done(GraphCall.Forbidden)
                        code == 429 -> Retry(response.headers["Retry-After"]?.trim()?.toLongOrNull())
                        code == 404 || code == 410 -> Done(GraphCall.Failed(AppError.ToolFailed(tool, notFound)))
                        code == 400 -> {
                            Log.w(LogTag.Accounts, "$service HTTP 400 on $kind (${graphCode(body)})")
                            Done(GraphCall.Failed(AppError.ToolFailed(tool, "$service didn't accept that (check the words, dates or address).")))
                        }
                        else -> {
                            Log.w(LogTag.Accounts, "$service HTTP $code on $kind (${graphCode(body)})")
                            Done(GraphCall.Failed(AppError.ToolFailed(tool, "$service said no (HTTP $code).")))
                        }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.d(LogTag.Accounts, "$service: $kind stopped after ${started.elapsedNow().inWholeMilliseconds} ms")
            throw e
        } catch (e: IOException) {
            Log.w(LogTag.Accounts, "$service request failed on $kind (${e::class.simpleName})")
            Done(GraphCall.Failed(AppError.ToolFailed(tool, "Couldn't reach $service. Check the phone's connection.")))
        } catch (e: SerializationException) {
            Log.w(LogTag.Accounts, "Couldn't read $service's answer on $kind")   // its message would quote the body
            Done(GraphCall.Failed(AppError.ToolFailed(tool, "$service sent something Edith couldn't read.")))
        } catch (e: IllegalArgumentException) {
            Log.w(LogTag.Accounts, "Couldn't read $service's answer on $kind")
            Done(GraphCall.Failed(AppError.ToolFailed(tool, "$service sent something Edith couldn't read.")))
        }
        return when (answer) {
            is Retry -> {
                val seconds = answer.seconds ?: 1
                if (retryAfter429 && seconds <= MAX_RETRY_AFTER_SECONDS) {
                    Log.w(LogTag.Accounts, "$service is busy (429): trying again in $seconds s")
                    sleep(seconds * 1000)
                    execute(request, parse, retryAfter429 = false)
                } else {
                    GraphCall.Failed(AppError.ToolFailed(tool, "$service is busy right now. Try again in a minute."))
                }
            }
            is Done -> answer.call
        }
    }

    /** One attempt's answer: done, or a 429 to wait for. */
    private sealed interface Answer<out T>
    private class Done<T>(val call: GraphCall<T>) : Answer<T>
    private class Retry(val seconds: Long?) : Answer<Nothing>

    companion object {
        const val BASE_URL = "https://graph.microsoft.com/v1.0"
        const val MAX_RETRY_AFTER_SECONDS = 10L
        private val JSON_TYPE = ContentType.Application.Json.withCharset(Charsets.UTF_8)
        private val EMPTY_BODY = ByteArrayContent(ByteArray(0))
        private val CODE = Regex(""""code"\s*:\s*"([A-Za-z0-9_.]+)"""")

        private val KNOWN_SEGMENTS = setOf("me", "calendars", "calendarView", "events", "messages", "mailFolders", "inbox", "createReply", "send")

        /** "GET me/calendars/…/calendarView": which request, for the log, with every id left out. */
        fun requestKind(method: String, url: Url): String =
            method + " " + url.pathSegments.dropWhile { it != "me" }.joinToString("/") { if (it in KNOWN_SEGMENTS) it else "…" }

        /** Graph's error code ("ErrorItemNotFound"), safe to log; never the message. */
        fun graphCode(body: String): String = CODE.find(body)?.groupValues?.get(1) ?: "no code"
    }
}
