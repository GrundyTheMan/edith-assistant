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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.resumeWithException

/** Runs the call without blocking a thread; cancelling the coroutine cancels the HTTP call (C22 §4's per-account timeout). */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, r, _ -> r.close() }
    })
}

/**
 * One Microsoft account's Microsoft Graph (C23 §2–4), with a token from its [MicrosoftAccountAuth] for each call.
 * 401 refreshes once; 403 asks for signing in again; 429 waits for `Retry-After` once (at most 10 s). [tool] and
 * [service] word the errors ("email", "Outlook"). Request and response bodies are never logged.
 */
class GraphClient(
    private val http: Lazy<OkHttpClient>,
    private val auth: MicrosoftAccountAuth,
    private val dispatchers: AppDispatchers,
    private val tool: String,
    private val service: String,
    private val notFound: String,
    private val baseUrl: String = BASE_URL,
    /** Waits [ms] before a 429 retry; tests don't sleep. */
    private val sleep: suspend (ms: Long) -> Unit = { delay(it) },
) {
    fun url(vararg segments: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        baseUrl.trimEnd('/').toHttpUrl().newBuilder().apply {
            segments.forEach { addPathSegment(it) }   // Graph ids can hold '/', '+' and '=': each is encoded
            query()
        }.build()

    suspend fun <T> get(url: HttpUrl, headers: Map<String, String> = emptyMap(), parse: (String) -> T): Outcome<T> =
        call("GET", url, null, headers, parse)

    suspend fun <T> send(method: String, url: HttpUrl, body: String?, headers: Map<String, String> = emptyMap(), parse: (String) -> T): Outcome<T> =
        call(method, url, body, headers, parse)

    private suspend fun <T> call(method: String, url: HttpUrl, body: String?, headers: Map<String, String>, parse: (String) -> T): Outcome<T> =
        auth.authorized { token ->
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .method(method, body?.toRequestBody(JSON_TYPE) ?: if (method == "POST") EMPTY_BODY else null)
                .build()
            execute(request, parse, retryAfter429 = true)
        }

    private suspend fun <T> execute(request: Request, parse: (String) -> T, retryAfter429: Boolean): GraphCall<T> {
        val kind = requestKind(request)
        val startedMs = System.currentTimeMillis()
        val answer: Answer<T> = try {
            // Enqueued, not execute(): a cancelled read (one account's 10 s timeout, C22 §4) cancels the HTTP call too.
            http.value.newCall(request).await().use { response ->
                val body = withContext(dispatchers.io) { response.body.string() }
                Log.d(LogTag.Accounts, "$service: $kind HTTP ${response.code} in ${System.currentTimeMillis() - startedMs} ms")
                run {
                    when {
                        response.isSuccessful -> Done(GraphCall.Ok(parse(body)))
                        response.code == 401 -> Done(GraphCall.Unauthorized)
                        response.code == 403 -> Done(GraphCall.Forbidden)
                        response.code == 429 -> Retry(response.header("Retry-After")?.trim()?.toLongOrNull())
                        response.code == 404 || response.code == 410 -> Done(GraphCall.Failed(AppError.ToolFailed(tool, notFound)))
                        response.code == 400 -> {
                            Log.w(LogTag.Accounts, "$service HTTP 400 on $kind (${graphCode(body)})")
                            Done(GraphCall.Failed(AppError.ToolFailed(tool, "$service didn't accept that (check the words, dates or address).")))
                        }
                        else -> {
                            Log.w(LogTag.Accounts, "$service HTTP ${response.code} on $kind (${graphCode(body)})")
                            Done(GraphCall.Failed(AppError.ToolFailed(tool, "$service said no (HTTP ${response.code}).")))
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.d(LogTag.Accounts, "$service: $kind stopped after ${System.currentTimeMillis() - startedMs} ms")
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
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        private val CODE = Regex(""""code"\s*:\s*"([A-Za-z0-9_.]+)"""")

        private val KNOWN_SEGMENTS = setOf("me", "calendars", "calendarView", "events", "messages", "mailFolders", "inbox", "createReply", "send")

        /** "GET me/calendars/…/calendarView": which request, for the log, with every id left out. */
        fun requestKind(request: Request): String =
            request.method + " " + request.url.pathSegments.dropWhile { it != "me" }.joinToString("/") { if (it in KNOWN_SEGMENTS) it else "…" }

        /** Graph's error code ("ErrorItemNotFound"), safe to log; never the message. */
        fun graphCode(body: String): String = CODE.find(body)?.groupValues?.get(1) ?: "no code"
    }
}
