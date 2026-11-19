package com.edith.app.jarvis

import android.annotation.SuppressLint
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * What Edith keeps about the paired PC (C38): where Jarvis's feed is, the certificate she pinned when pairing, and the
 * device token that opens the feed. Stored encrypted in the secret store, never logged.
 */
@Serializable
internal data class JarvisLink(val base: String, val fingerprint: String, val token: String, val pcName: String = "") {
    override fun toString() = "JarvisLink($base)"   // never the token
}

/** The bytes of an answer, with its status and type. */
internal class Raw(val status: Int, val contentType: String?, val body: ByteArray)

/** What a feed call came to (C39 part 5.1). */
internal sealed interface FeedResult<out T> {
    data class Ok<T>(val value: T) : FeedResult<T>
    /** Jarvis answered with an error: its own [code] (null when it sent none) and [message]; [body] is the whole answer, for the fields a code adds. */
    data class Err(val status: Int, val code: String?, val message: String, val body: String) : FeedResult<Nothing>
    /** No answer: the PC is off, Jarvis isn't running or the network is away. */
    data object Unreachable : FeedResult<Nothing>
    /** Jarvis doesn't know this phone (any more), or there is no pairing. */
    data object Unauthorized : FeedResult<Nothing>
}

/** The pure parts of pairing (spec part 6): the address, the fingerprint and the proof. */
internal object JarvisPairing {
    const val DEFAULT_PORT = 47821
    const val NOT_AN_ADDRESS = "Enter the address Jarvis shows, like 192.168.1.20:47821."
    const val CLEARTEXT = "Jarvis's feed is always https. Enter just the address, like 192.168.1.20:47821."

    /** "192.168.1.20", "192.168.1.20:47821" or "https://pc:47821/" → https://host:port, or why not (null address, [error] set). */
    fun address(text: String): Pair<HttpUrl?, String?> {
        val t = text.trim()
        if (t.isEmpty()) return null to NOT_AN_ADDRESS
        if (t.startsWith("http://", ignoreCase = true)) return null to CLEARTEXT
        val withScheme = if (t.startsWith("https://", ignoreCase = true)) t else "https://$t"
        val url = withScheme.toHttpUrlOrNull() ?: return null to NOT_AN_ADDRESS
        val hostPart = withScheme.substringAfter("://").substringBefore('/')
        val port = if (hostPart.substringAfterLast(']').contains(':')) url.port else DEFAULT_PORT
        return url.newBuilder().port(port).encodedPath("/").query(null).fragment(null).build() to null
    }

    /** Lowercase hex SHA-256 of a certificate's DER bytes: what Jarvis shows and Edith pins. */
    fun fingerprint(der: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

    /** The first 24 characters in groups of four, as Jarvis shows them for the owner to compare: "a559 fd3a cd73 9f17 e54c 7701". */
    fun shortFingerprint(fingerprint: String): String = fingerprint.take(24).chunked(4).joinToString(" ")

    /**
     * `HMAC-SHA256(key = the code without dashes, uppercased, message = the fingerprint as lowercase hex)`, as lowercase
     * hex. Sending a proof, not the code, means whoever answers in the PC's place learns nothing usable.
     */
    fun proof(code: String, fingerprint: String): String {
        val key = code.filter { it.isLetterOrDigit() }.uppercase().toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return mac.doFinal(fingerprint.lowercase().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Talks to Jarvis's feed over HTTPS with the certificate pinned (C38): the chain is accepted only when its first
 * certificate's SHA-256 is [JarvisLink.fingerprint]; names aren't checked, because the address is an IP that can change
 * and the pin is what proves it's the owner's PC. While pairing there's nothing to compare yet ([probe]).
 */
internal class JarvisApi(
    private val link: () -> JarvisLink?,
    private val http: Lazy<OkHttpClient>,
    private val dispatchers: AppDispatchers,
    /** Jarvis answered 401: this phone was removed there. */
    private val onUnauthorized: suspend () -> Unit = {},
) {
    private val clients = HashMap<String, OkHttpClient>()
    /** Set by the pin check; the handshake wraps its exception differently on each platform, so the flag is what [unreachable] trusts. */
    private val pinMismatch = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The secure client (https only, no redirects to http), with the pin check and calls that give up after 30 s. */
    @SuppressLint("CustomX509TrustManager")   // Deliberate: the PC's certificate is self-signed, so trust is the pin (below), not a CA. With no pin yet (probe) it only reads the fingerprint, and nothing secret is sent.
    private fun pinned(fingerprint: String?, seen: AtomicReference<String?>? = null): OkHttpClient {
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("no client certificates")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("Jarvis sent no certificate")
                val seenNow = JarvisPairing.fingerprint(leaf.encoded)
                seen?.set(seenNow)
                if (fingerprint != null && !seenNow.equals(fingerprint, ignoreCase = true)) {
                    pinMismatch.set(true)
                    throw CertificateException("This isn't the PC Edith paired with")
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return http.value.newBuilder()
            .sslSocketFactory(context.socketFactory, trust)
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    @Synchronized private fun clientFor(fingerprint: String): OkHttpClient = clients.getOrPut(fingerprint.lowercase()) { pinned(fingerprint) }

    /** Pairing, step 1: the certificate Jarvis shows at [base], for the owner to compare. Nothing secret is sent. */
    suspend fun probe(base: HttpUrl): Outcome<String> = withContext(dispatchers.io) {
        val seen = AtomicReference<String?>(null)
        try {
            pinned(null, seen).newCall(Request.Builder().url(base.resolve("/v1/hello")!!).build()).execute().use { r ->
                val fp = seen.get()
                if (!r.isSuccessful || fp == null) return@withContext Outcome.Failure(failureFor(r.code, r.body.string()))
                val hello = runCatching { feedJson.decodeFromString(Hello.serializer(), r.body.string()) }.getOrNull()
                if (hello?.app != "jarvis-feed") return@withContext Outcome.Failure(AppError.ToolFailed("Jarvis", NOT_JARVIS))
                Outcome.Success(fp)
            }
        } catch (e: IOException) {
            Outcome.Failure(unreachable(e, pinMismatch.getAndSet(false)))
        }
    }

    /** Pairing, step 2: proves the code, on a connection pinned to the fingerprint the owner compared. */
    suspend fun pair(base: HttpUrl, fingerprint: String, code: String, deviceName: String): Outcome<JarvisLink> = withContext(dispatchers.io) {
        val body = feedJson.encodeToString(PairRequest.serializer(), PairRequest(deviceName.take(40), JarvisPairing.proof(code, fingerprint)))
        val request = Request.Builder().url(base.resolve("/v1/pair")!!).post(body.toRequestBody(JSON)).build()
        try {
            clientFor(fingerprint).newCall(request).execute().use { r ->
                val text = r.body.string()
                if (!r.isSuccessful) return@use Outcome.Failure(failureFor(r.code, text))
                val reply = feedJson.decodeFromString(PairReply.serializer(), text)
                Outcome.Success(JarvisLink(base.toString().trimEnd('/'), fingerprint.lowercase(), reply.token, reply.pcName))
            }
        } catch (e: SerializationException) {
            Outcome.Failure(AppError.MalformedResponse("Jarvis's pairing answer", e))
        } catch (e: IOException) {
            Outcome.Failure(unreachable(e, pinMismatch.getAndSet(false)))
        }
    }

    /** [last] is one more path segment kept whole (a mail id has colons and is never split). */
    suspend fun <T> get(path: String, query: Map<String, String> = emptyMap(), serializer: KSerializer<T>, last: String? = null): Outcome<T> =
        call(path, query, null, serializer, last)

    suspend fun <T> post(path: String, body: String, serializer: KSerializer<T>): Outcome<T> = call(path, emptyMap(), body, serializer)

    private suspend fun <T> call(path: String, query: Map<String, String>, body: String?, serializer: KSerializer<T>, last: String? = null): Outcome<T> {
        val l = link() ?: return Outcome.Failure(AppError.GoogleAuth(com.edith.app.accounts.sources.Reads.NEEDS_SIGN_IN))
        val base = l.base.toHttpUrlOrNull() ?: return Outcome.Failure(AppError.ToolFailed("Jarvis", "the saved address is damaged: pair again"))
        val url = base.newBuilder().apply {
            addPathSegment("v1")
            path.trim('/').split('/').forEach { addPathSegment(it) }
            if (last != null) addPathSegment(last)
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${l.token}").apply {
            if (body != null) post(body.toRequestBody(JSON)) else get()
        }.build()
        val outcome = withContext(dispatchers.io) {
            try {
                // Cancellable: when Edith's own time limit (or the owner's Stop) cancels this, the HTTP call is cancelled too.
                // A blocking execute() ignored both, so a slow Outlook left the whole reply stuck.
                clientFor(l.fingerprint).newCall(request).await().use { r ->
                    val text = r.body.string()
                    if (r.isSuccessful) Outcome.Success(feedJson.decodeFromString(serializer, text)) else Outcome.Failure(failureFor(r.code, text))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SerializationException) {
                Log.w(LogTag.Accounts, "Jarvis feed: unreadable answer from $path")
                Outcome.Failure(AppError.MalformedResponse("Jarvis's answer", e))
            } catch (e: IOException) {
                Outcome.Failure(unreachable(e, pinMismatch.getAndSet(false)))
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
     * and [callTimeoutS] change the limits for this call only, on a copy of the shared client.
     */
    internal suspend fun <R> exchange(
        segments: List<String>,
        query: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        readTimeoutS: Long? = null,
        callTimeoutS: Long? = null,
        parse: (Response) -> R,
    ): FeedResult<R> {
        val l = link() ?: return FeedResult.Unauthorized
        val base = l.base.toHttpUrlOrNull() ?: return FeedResult.Err(0, "BAD_ADDRESS", "The saved address is damaged: pair again.", "")
        val url = base.newBuilder().apply {
            addPathSegment("v1")
            segments.forEach { addPathSegment(it) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${l.token}").apply {
            if (body != null) post(body) else get()
        }.build()
        val result: FeedResult<R> = withContext(dispatchers.io) {
            try {
                val shared = clientFor(l.fingerprint)
                val client = if (readTimeoutS == null && callTimeoutS == null) shared else shared.newBuilder().apply {
                    readTimeoutS?.let { readTimeout(it, TimeUnit.SECONDS) }
                    callTimeoutS?.let { callTimeout(it, TimeUnit.SECONDS) }
                }.build()
                client.newCall(request).await().use { r ->
                    if (r.isSuccessful) FeedResult.Ok(parse(r)) else errorOf(r.code, r.body.string())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SerializationException) {
                Log.w(LogTag.Accounts, "Jarvis feed: unreadable answer from ${segments.joinToString("/")}")
                FeedResult.Err(200, "UNREADABLE", "Jarvis answered something Edith didn't expect.", "")
            } catch (e: IOException) {
                val pinFailed = pinMismatch.getAndSet(false) || generateSequence<Throwable>(e) { it.cause }.any { it is CertificateException }
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
    internal suspend fun <T> getJson(
        segments: List<String>,
        query: Map<String, String> = emptyMap(),
        serializer: KSerializer<T>,
        readTimeoutS: Long? = null,
    ): FeedResult<T> = exchange(segments, query, null, readTimeoutS) { feedJson.decodeFromString(serializer, it.body.string()) }

    /** POST a JSON body and read the answer as JSON. */
    internal suspend fun <T> postJson(segments: List<String>, body: String, serializer: KSerializer<T>): FeedResult<T> =
        exchange(segments, emptyMap(), body.toRequestBody(JSON)) { feedJson.decodeFromString(serializer, it.body.string()) }

    /** GET the bytes of an answer (a camera picture). */
    internal suspend fun getBytes(segments: List<String>, query: Map<String, String> = emptyMap()): FeedResult<Raw> =
        exchange(segments, query) { Raw(it.code, it.header("Content-Type"), it.body.bytes()) }

    /** POST raw bytes (an upload); the call may take [callTimeoutS], on this call only. The answer's text is returned. */
    internal suspend fun postBytes(segments: List<String>, query: Map<String, String>, body: RequestBody, callTimeoutS: Long): FeedResult<String> =
        exchange(segments, query, body, readTimeoutS = callTimeoutS, callTimeoutS = callTimeoutS) { it.body.string() }

    /** Runs the call without blocking a thread, and cancels it if the coroutine is cancelled. */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { c ->
        c.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (c.isActive) c.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                if (c.isActive) c.resume(response) else response.close()
            }
        })
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        const val NOT_JARVIS = "That address answered, but it isn't Jarvis's feed. Check the address and that the feed is switched on."
        const val NOT_REACHABLE = "Edith can't reach Jarvis. Is the PC on, Jarvis running with the feed switched on, and this phone on the home Wi-Fi?"
        const val PAIRING_REFUSED = "Jarvis refused that code. Make a new one with Pair a phone in Jarvis and type it in within 5 minutes."
        const val OUTLOOK_DOWN = "Outlook isn't answering on the PC. Is Outlook open there?"
        const val MOVED = "This isn't the PC Edith paired with (its certificate changed). Pair again."
        /** Jarvis removed this phone (or its token no longer matches): the owner pairs again. */
        val UNAUTHORIZED = AppError.GoogleAuth(com.edith.app.accounts.sources.Reads.NEEDS_SIGN_IN)

        private fun unreachable(e: IOException, pinMismatch: Boolean): AppError {
            val pinFailed = pinMismatch || generateSequence<Throwable>(e) { it.cause }.any { it is CertificateException }
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
