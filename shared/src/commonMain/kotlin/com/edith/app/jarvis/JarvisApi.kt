package com.edith.app.jarvis

import com.edith.app.core.crypto.hmacSha256
import com.edith.app.core.crypto.sha256
import com.edith.app.core.crypto.toLowerHex
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.error.SignInWords
import com.edith.app.core.error.isCertificateFailure
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.encodedPath
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.http.withCharset
import io.ktor.utils.io.charsets.Charsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * What Edith keeps about the paired PC (C38): where Jarvis's feed is, the certificate she pinned when pairing, and the
 * device token that opens the feed. Stored encrypted in the secret store, never logged.
 */
@Serializable
data class JarvisLink(val base: String, val fingerprint: String, val token: String, val pcName: String = "") {
    override fun toString() = "JarvisLink($base)"   // never the token
}

/** The bytes of an answer, with its status and type. */
class Raw(val status: Int, val contentType: String?, val body: ByteArray) {
    val text: String get() = body.decodeToString()
}

/** What a feed call came to (C39 part 5.1). */
sealed interface FeedResult<out T> {
    data class Ok<T>(val value: T) : FeedResult<T>
    /** Jarvis answered with an error: its own [code] (null when it sent none) and [message]; [body] is the whole answer, for the fields a code adds. */
    data class Err(val status: Int, val code: String?, val message: String, val body: String) : FeedResult<Nothing>
    /** No answer: the PC is off, Jarvis isn't running or the network is away. */
    data object Unreachable : FeedResult<Nothing>
    /** Jarvis doesn't know this phone (any more), or there is no pairing. */
    data object Unauthorized : FeedResult<Nothing>
}

/** The pure parts of pairing (spec part 6): the address, the fingerprint and the proof. */
object JarvisPairing {
    const val DEFAULT_PORT = 47821
    const val NOT_AN_ADDRESS = "Enter the address Jarvis shows, like 192.168.1.20:47821."
    const val CLEARTEXT = "Jarvis's feed is always https. Enter just the address, like 192.168.1.20:47821."

    /** "192.168.1.20", "192.168.1.20:47821" or "https://pc:47821/" → https://host:port/, or why not (null address, [error] set). */
    fun address(text: String): Pair<Url?, String?> {
        val t = text.trim()
        if (t.isEmpty()) return null to NOT_AN_ADDRESS
        if (t.startsWith("http://", ignoreCase = true)) return null to CLEARTEXT
        val withScheme = if (t.startsWith("https://", ignoreCase = true)) t else "https://$t"
        val parsed = runCatching { URLBuilder(withScheme) }.getOrNull() ?: return null to NOT_AN_ADDRESS
        if (parsed.host.isEmpty() || parsed.host.any { it.isWhitespace() }) return null to NOT_AN_ADDRESS
        val hostPart = withScheme.substringAfter("://").substringBefore('/')
        val port = if (hostPart.substringAfterLast(']').contains(':')) parsed.port else DEFAULT_PORT
        val url = URLBuilder(protocol = URLProtocol.HTTPS, host = parsed.host, port = port).apply { encodedPath = "/" }.build()
        return url to null
    }

    /** Lowercase hex SHA-256 of a certificate's DER bytes: what Jarvis shows and Edith pins. */
    fun fingerprint(der: ByteArray): String = sha256(der).toLowerHex()

    /** The first 24 characters in groups of four, as Jarvis shows them for the owner to compare: "a559 fd3a cd73 9f17 e54c 7701". */
    fun shortFingerprint(fingerprint: String): String = fingerprint.take(24).chunked(4).joinToString(" ")

    /**
     * `HMAC-SHA256(key = the code without dashes, uppercased, message = the fingerprint as lowercase hex)`, as lowercase
     * hex. Sending a proof, not the code, means whoever answers in the PC's place learns nothing usable.
     */
    fun proof(code: String, fingerprint: String): String {
        val key = code.filter { it.isLetterOrDigit() }.uppercase().encodeToByteArray()
        return hmacSha256(key, fingerprint.lowercase().encodeToByteArray()).toLowerHex()
    }
}

/**
 * The certificate check of one pinned connection (C38): the chain is accepted only when its first certificate's SHA-256 is [fingerprint].
 * With no fingerprint yet (pairing, step 1) nothing is compared: it only records what the server showed. The platform's TLS layer calls
 * [accepts] with the server's first certificate; the engine hook that does so is the part that differs per platform (OkHttp's trust manager
 * on Android, a URLSession challenge on the iPhone, K4.5).
 */
@OptIn(ExperimentalAtomicApi::class)
class PinCheck(val fingerprint: String?) {
    private val showed = AtomicReference<String?>(null)
    private val refused = AtomicBoolean(false)

    /** The fingerprint of the certificate the server last showed, or null if it showed none. */
    val seen: String? get() = showed.load()

    /** Called with the DER bytes of the server's first certificate. True to go on, false to refuse the connection (and remember why). */
    fun accepts(der: ByteArray): Boolean {
        val now = JarvisPairing.fingerprint(der)
        showed.store(now)
        if (fingerprint != null && !now.equals(fingerprint, ignoreCase = true)) {
            refused.store(true)
            return false
        }
        return true
    }

    /** True once if the pin refused a connection since the last call. The handshake wraps its exception differently on each platform, so this flag is what callers trust. */
    fun takeRefusal(): Boolean = refused.exchange(false)
}

/**
 * Makes the HTTP clients that talk to Jarvis. The certificate pin and the timeouts are an engine hook (OkHttp on Android, see
 * `OkHttpPinnedHttp`); everything above it is shared. [readTimeoutS] and [callTimeoutS] change the limits for this client only.
 */
interface PinnedHttp {
    fun client(check: PinCheck, readTimeoutS: Long? = null, callTimeoutS: Long? = null): HttpClient
}

/**
 * Talks to Jarvis's feed over HTTPS with the certificate pinned (C38): the chain is accepted only when its first
 * certificate's SHA-256 is [JarvisLink.fingerprint]; names aren't checked, because the address is an IP that can change
 * and the pin is what proves it's the owner's PC. While pairing there's nothing to compare yet ([probe]).
 */
@OptIn(ExperimentalAtomicApi::class)
class JarvisApi(
    private val link: () -> JarvisLink?,
    private val http: PinnedHttp,
    private val dispatchers: AppDispatchers,
    /** Jarvis answered 401: this phone was removed there. */
    private val onUnauthorized: suspend () -> Unit = {},
) {
    private class Pinned(val client: HttpClient, val check: PinCheck)

    /** One pinned client per fingerprint; made on first use. (A rare race makes two and keeps one: the spare is never used.) */
    private val pinned = AtomicReference<Map<String, Pinned>>(emptyMap())

    private fun pinnedFor(fingerprint: String): Pinned {
        val key = fingerprint.lowercase()
        while (true) {
            val now = pinned.load()
            now[key]?.let { return it }
            val check = PinCheck(key)
            val made = Pinned(http.client(check), check)
            if (pinned.compareAndSet(now, now + (key to made))) return made
        }
    }

    /** Pairing, step 1: the certificate Jarvis shows at [base], for the owner to compare. Nothing secret is sent. */
    suspend fun probe(base: Url): Outcome<String> = withContext(dispatchers.io) {
        val check = PinCheck(null)
        val url = feedUrl(base.toString(), listOf("hello"), emptyMap()) ?: return@withContext Outcome.Failure(AppError.ToolFailed("Jarvis", JarvisPairing.NOT_AN_ADDRESS))
        try {
            val r = http.client(check).get(url)
            val text = r.bodyAsText()
            val fp = check.seen
            if (!r.status.isSuccess() || fp == null) return@withContext Outcome.Failure(failureFor(r.status.value, text))
            val hello = runCatching { feedJson.decodeFromString(Hello.serializer(), text) }.getOrNull()
            if (hello?.app != "jarvis-feed") return@withContext Outcome.Failure(AppError.ToolFailed("Jarvis", NOT_JARVIS))
            Outcome.Success(fp)
        } catch (e: IOException) {
            Outcome.Failure(unreachable(e, check.takeRefusal()))
        }
    }

    /** Pairing, step 2: proves the code, on a connection pinned to the fingerprint the owner compared. */
    suspend fun pair(base: Url, fingerprint: String, code: String, deviceName: String): Outcome<JarvisLink> = withContext(dispatchers.io) {
        val body = feedJson.encodeToString(PairRequest.serializer(), PairRequest(deviceName.take(40), JarvisPairing.proof(code, fingerprint)))
        val pin = pinnedFor(fingerprint)
        val url = feedUrl(base.toString(), listOf("pair"), emptyMap()) ?: return@withContext Outcome.Failure(AppError.ToolFailed("Jarvis", JarvisPairing.NOT_AN_ADDRESS))
        try {
            val r = pin.client.post(url) { setBody(TextContent(body, JSON)) }
            val text = r.bodyAsText()
            if (!r.status.isSuccess()) {
                Outcome.Failure(failureFor(r.status.value, text))
            } else {
                val reply = feedJson.decodeFromString(PairReply.serializer(), text)
                Outcome.Success(JarvisLink(base.toString().trimEnd('/'), fingerprint.lowercase(), reply.token, reply.pcName))
            }
        } catch (e: SerializationException) {
            Outcome.Failure(AppError.MalformedResponse("Jarvis's pairing answer", e))
        } catch (e: IOException) {
            Outcome.Failure(unreachable(e, pin.check.takeRefusal()))
        }
    }

    /** [last] is one more path segment kept whole (a mail id has colons and is never split). */
    suspend fun <T> get(path: String, query: Map<String, String> = emptyMap(), serializer: KSerializer<T>, last: String? = null): Outcome<T> =
        call(path, query, null, serializer, last)

    suspend fun <T> post(path: String, body: String, serializer: KSerializer<T>): Outcome<T> = call(path, emptyMap(), body, serializer)

    private suspend fun <T> call(path: String, query: Map<String, String>, body: String?, serializer: KSerializer<T>, last: String? = null): Outcome<T> {
        val l = link() ?: return Outcome.Failure(AppError.GoogleAuth(SignInWords.NEEDS_SIGN_IN))
        val url = feedUrl(l.base, path.trim('/').split('/') + listOfNotNull(last), query)
            ?: return Outcome.Failure(AppError.ToolFailed("Jarvis", "the saved address is damaged: pair again"))
        val pin = pinnedFor(l.fingerprint)
        val outcome = withContext(dispatchers.io) {
            try {
                // Cancellable: when Edith's own time limit (or the owner's Stop) cancels this, the HTTP call is cancelled too.
                val r = pin.client.request(url) {
                    method = if (body != null) HttpMethod.Post else HttpMethod.Get
                    header(HttpHeaders.Authorization, "Bearer ${l.token}")
                    if (body != null) setBody(TextContent(body, JSON))
                }
                val text = r.bodyAsText()
                if (r.status.isSuccess()) Outcome.Success(feedJson.decodeFromString(serializer, text)) else Outcome.Failure(failureFor(r.status.value, text))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SerializationException) {
                Log.w(LogTag.Accounts, "Jarvis feed: unreadable answer from $path")
                Outcome.Failure(AppError.MalformedResponse("Jarvis's answer", e))
            } catch (e: IOException) {
                Outcome.Failure(unreachable(e, pin.check.takeRefusal()))
            }
        }
        if (outcome is Outcome.Failure && outcome.error == UNAUTHORIZED) onUnauthorized()
        return outcome
    }

    // --- The printer's routes (C39): answers keep their status and the server's own error code and words -----------------

    /**
     * One request to the feed. [segments] are path segments after `/v1`, each encoded whole (a camera id with odd characters
     * is never split). A non-2xx answer is an [FeedResult.Err] carrying Jarvis's `code` and `message`; 401 is [FeedResult.Unauthorized]
     * (and tells the connection this phone was removed); no answer is [FeedResult.Unreachable]. Cancellable like [call]; [readTimeoutS]
     * and [callTimeoutS] change the limits for this call only, on a client of its own.
     */
    suspend fun <R> exchange(
        segments: List<String>,
        query: Map<String, String> = emptyMap(),
        body: OutgoingContent? = null,
        readTimeoutS: Long? = null,
        callTimeoutS: Long? = null,
        parse: (Raw) -> R,
    ): FeedResult<R> {
        val l = link() ?: return FeedResult.Unauthorized
        val url = feedUrl(l.base, segments, query) ?: return FeedResult.Err(0, "BAD_ADDRESS", "The saved address is damaged: pair again.", "")
        // A call with its own limits gets its own client: the timeouts belong to the client, not to the request.
        val pin = if (readTimeoutS == null && callTimeoutS == null) pinnedFor(l.fingerprint) else PinCheck(l.fingerprint.lowercase()).let { Pinned(http.client(it, readTimeoutS, callTimeoutS), it) }
        val result: FeedResult<R> = withContext(dispatchers.io) {
            try {
                val r = pin.client.request(url) {
                    method = if (body != null) HttpMethod.Post else HttpMethod.Get
                    header(HttpHeaders.Authorization, "Bearer ${l.token}")
                    if (body != null) setBody(body)
                }
                val answer = Raw(r.status.value, r.headers[HttpHeaders.ContentType], r.bodyAsBytes())
                if (r.status.isSuccess()) FeedResult.Ok(parse(answer)) else errorOf(answer.status, answer.text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SerializationException) {
                Log.w(LogTag.Accounts, "Jarvis feed: unreadable answer from ${segments.joinToString("/")}")
                FeedResult.Err(200, "UNREADABLE", "Jarvis answered something Edith didn't expect.", "")
            } catch (e: IOException) {
                val pinFailed = pin.check.takeRefusal() || e.isCertificateFailure()
                Log.w(LogTag.Accounts, "Jarvis feed: ${if (pinFailed) "certificate doesn't match" else "unreachable"} (${e::class.simpleName})")
                if (pinFailed) FeedResult.Err(0, "CERTIFICATE_CHANGED", MOVED, "") else FeedResult.Unreachable
            }
        }
        if (result is FeedResult.Unauthorized) onUnauthorized()
        return result
    }

    private fun errorOf(status: Int, text: String): FeedResult<Nothing> {
        if (status == 401) return FeedResult.Unauthorized
        val e = runCatching { feedJson.decodeFromString(FeedErrorReply.serializer(), text).error }.getOrNull()
        return FeedResult.Err(status, e?.code?.ifBlank { null }, e?.message.orEmpty(), text)
    }

    /** GET and read the answer as JSON. */
    suspend fun <T> getJson(
        segments: List<String>,
        query: Map<String, String> = emptyMap(),
        serializer: KSerializer<T>,
        readTimeoutS: Long? = null,
    ): FeedResult<T> = exchange(segments, query, null, readTimeoutS) { feedJson.decodeFromString(serializer, it.text) }

    /** POST a JSON body and read the answer as JSON. */
    suspend fun <T> postJson(segments: List<String>, body: String, serializer: KSerializer<T>): FeedResult<T> =
        exchange(segments, emptyMap(), TextContent(body, JSON)) { feedJson.decodeFromString(serializer, it.text) }

    /** POST a JSON body whose answer has no content (204): only whether Jarvis took it. */
    suspend fun postNoContent(segments: List<String>, body: String): FeedResult<Unit> =
        exchange(segments, emptyMap(), TextContent(body, JSON)) { }

    /** GET the bytes of an answer (a camera picture). */
    suspend fun getBytes(segments: List<String>, query: Map<String, String> = emptyMap()): FeedResult<Raw> =
        exchange(segments, query) { it }

    /** POST raw bytes (an upload); the call may take [callTimeoutS], on this call only. The answer's text is returned. */
    suspend fun postBytes(segments: List<String>, query: Map<String, String>, body: OutgoingContent, callTimeoutS: Long): FeedResult<String> =
        exchange(segments, query, body, readTimeoutS = callTimeoutS, callTimeoutS = callTimeoutS) { it.text }

    companion object {
        private val JSON = ContentType.Application.Json.withCharset(Charsets.UTF_8)
        const val NOT_JARVIS = "That address answered, but it isn't Jarvis's feed. Check the address and that the feed is switched on."
        const val NOT_REACHABLE = "Edith can't reach Jarvis. Is the PC on, Jarvis running with the feed switched on, and this phone on the home Wi-Fi?"
        const val PAIRING_REFUSED = "Jarvis refused that code. Make a new one with Pair a phone in Jarvis and type it in within 5 minutes."
        const val OUTLOOK_DOWN = "Outlook isn't answering on the PC. Is Outlook open there?"
        const val MOVED = "This isn't the PC Edith paired with (its certificate changed). Pair again."
        /** Jarvis removed this phone (or its token no longer matches): the owner pairs again. */
        val UNAUTHORIZED = AppError.GoogleAuth(SignInWords.NEEDS_SIGN_IN)

        /**
         * `https://host:port` plus `/v1` and then [segments], each one whole (a slash in a segment is encoded, never a separator), and
         * the [query]. Null when [base] is not an address.
         */
        fun feedUrl(base: String, segments: List<String>, query: Map<String, String>): String? {
            val builder = runCatching { URLBuilder(base) }.getOrNull() ?: return null
            if (builder.host.isEmpty()) return null
            builder.encodedPath = ""
            builder.appendPathSegments(listOf("v1") + segments, encodeSlash = true)
            query.forEach { (k, v) -> builder.parameters.append(k, v) }
            return builder.buildString()
        }

        private fun unreachable(e: IOException, pinMismatch: Boolean): AppError {
            val pinFailed = pinMismatch || e.isCertificateFailure()
            Log.w(LogTag.Accounts, "Jarvis feed: ${if (pinFailed) "certificate doesn't match" else "unreachable"} (${e::class.simpleName})")
            return AppError.ToolFailed("Jarvis", if (pinFailed) MOVED else NOT_REACHABLE)
        }

        /** What an error answer from Jarvis means, in plain words (spec part 7 error codes). */
        fun failureFor(status: Int, body: String): AppError {
            val code = runCatching { feedJson.decodeFromString(FeedErrorReply.serializer(), body).error.code }.getOrDefault("")
            return when {
                status == 401 || code == "UNAUTHORIZED" -> UNAUTHORIZED
                code == "PAIRING_REFUSED" -> AppError.ToolFailed("Jarvis", PAIRING_REFUSED)
                code == "OUTLOOK_UNAVAILABLE" -> AppError.ToolFailed("Jarvis", OUTLOOK_DOWN)
                code == "NOT_SHARED" || status == 404 -> AppError.ToolFailed("Jarvis", "Jarvis isn't sharing that with Edith. Check what's ticked in Jarvis → Settings → Edith.")
                status == 429 -> AppError.ToolFailed("Jarvis", "Jarvis asked Edith to wait a minute (too many wrong tries). Try again soon.")
                status == 504 || code == "TIMEOUT" -> AppError.Timeout
                status == 503 || code == "BUSY" -> AppError.ToolFailed("Jarvis", "Jarvis is busy right now. Try again in a moment.")
                else -> AppError.ToolFailed("Jarvis", "Jarvis answered with an error (HTTP $status).")
            }
        }
    }
}
