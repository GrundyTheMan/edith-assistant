package com.edith.app.google.gmail

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.accounts.sources.MailMessage
import com.edith.app.accounts.sources.MailQuery
import com.edith.app.accounts.sources.MailSearchResult
import com.edith.app.accounts.sources.MailSummary
import com.edith.app.accounts.sources.OutgoingMail
import com.edith.app.google.GoogleAccountAuth
import com.edith.app.google.GoogleCall
import com.edith.app.google.GoogleScope
import com.edith.app.tools.web.PageReader
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64

/**
 * One Google account's Gmail over its REST API (C19 §1, per account since C22), with a token from that account's
 * [GoogleAccountAuth]: read-only for searching and reading, compose for drafts and sending (D5: sending only after
 * the owner confirms, which the tools enforce). Email content is never logged.
 */
class GmailClient(
    private val http: Lazy<OkHttpClient>,
    private val auth: GoogleAccountAuth,
    private val dispatchers: AppDispatchers,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val baseUrl: String = BASE_URL,
    /** Stamped on every message, so a reply goes from the account the email came to (C22). */
    private val accountId: String = "",
) {
    /**
     * Messages matching a Gmail search ([query] uses Gmail's syntax), newest first, at most [max] (≤ 10), and whether
     * more matched (Gmail gives a `nextPageToken` then).
     */
    suspend fun search(query: String, max: Int): Outcome<MailSearchResult> = call(setOf(GoogleScope.GmailReadonly)) { token ->
        val list = get(token, url("messages") { addQueryParameter("q", query); addQueryParameter("maxResults", max.coerceIn(1, MAX_SEARCH).toString()) }) {
            json.decodeFromString<MessageListDto>(it)
        }
        if (list !is GoogleCall.Ok) return@call list.map { MailSearchResult(emptyList(), false) }
        val out = mutableListOf<MailSummary>()
        for (ref in list.value.messages) {   // one at a time: at most 10, and Gmail limits concurrent requests per user
            val meta = get(token, url("messages/${ref.id}") {
                addQueryParameter("format", "metadata")
                METADATA_HEADERS.forEach { addQueryParameter("metadataHeaders", it) }
            }) { json.decodeFromString<MessageDto>(it).summary() }
            when (meta) {
                is GoogleCall.Ok -> out += meta.value
                else -> return@call meta.map { MailSearchResult(emptyList(), false) }
            }
        }
        GoogleCall.Ok(MailSearchResult(out, more = list.value.nextPageToken != null))
    }

    /** One message in full, its body as plain text. */
    suspend fun read(id: String): Outcome<MailMessage> = call(setOf(GoogleScope.GmailReadonly)) { token ->
        get(token, url("messages/$id") { addQueryParameter("format", "full") }) { body ->
            val dto = json.decodeFromString<MessageDto>(body)
            MailMessage(dto.summary(), dto.header("Cc"), MailText.body(dto.payload))
        }
    }

    /** Saves [email] as a Gmail draft. Returns the draft's id. */
    suspend fun createDraft(email: OutgoingMail): Outcome<String> = call(setOf(GoogleScope.GmailCompose)) { token ->
        val body = """{"message":{"raw":"${MailText.raw(email)}"${email.threadId?.let { ",\"threadId\":\"$it\"" }.orEmpty()}}}"""
        post(token, url("drafts") {}, body) { json.decodeFromString<IdDto>(it).id }
    }

    /** Sends [email] (D5: the tool asks the owner first). Returns the sent message's id. */
    suspend fun send(email: OutgoingMail): Outcome<String> = call(setOf(GoogleScope.GmailCompose)) { token ->
        val body = """{"raw":"${MailText.raw(email)}"${email.threadId?.let { ",\"threadId\":\"$it\"" }.orEmpty()}}"""
        post(token, url("messages/send") {}, body) { json.decodeFromString<IdDto>(it).id }
    }

    // --- HTTP -------------------------------------------------------------------------------------------------

    private suspend fun <T> call(scopes: Set<GoogleScope>, block: (String) -> GoogleCall<T>): Outcome<T> =
        auth.authorized(scopes) { token -> withContext(dispatchers.io) { block(token) } }

    private fun <T> get(token: String, url: HttpUrl, parse: (String) -> T) =
        execute(Request.Builder().url(url).header("Authorization", "Bearer $token").build(), parse)

    private fun <T> post(token: String, url: HttpUrl, body: String, parse: (String) -> T) =
        execute(Request.Builder().url(url).header("Authorization", "Bearer $token").post(body.toRequestBody(JSON_TYPE)).build(), parse)

    private fun <T> execute(request: Request, parse: (String) -> T): GoogleCall<T> = try {
        http.value.newCall(request).execute().use { response ->
            val body = response.body.string()
            when {
                response.isSuccessful -> GoogleCall.Ok(parse(body))
                response.code == 401 -> GoogleCall.Unauthorized
                response.code == 403 && (body.contains("insufficientPermissions") || body.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT")) -> GoogleCall.MissingScope
                response.code == 404 -> GoogleCall.Failed(AppError.ToolFailed("email", "That email isn't there any more."))
                response.code == 400 -> GoogleCall.Failed(AppError.ToolFailed("email", "Gmail didn't accept that (check the search words or the address)."))
                else -> {
                    Log.w(LogTag.Google, "Gmail HTTP ${response.code}")
                    GoogleCall.Failed(AppError.ToolFailed("email", "Gmail said no (HTTP ${response.code})."))
                }
            }
        }
    } catch (e: java.io.IOException) {
        Log.w(LogTag.Google, "Gmail request failed", e)
        GoogleCall.Failed(AppError.ToolFailed("email", "Couldn't reach Gmail. Check the phone's connection."))
    } catch (e: kotlinx.serialization.SerializationException) {
        Log.w(LogTag.Google, "Couldn't read Gmail's answer", e)
        GoogleCall.Failed(AppError.ToolFailed("email", "Gmail sent something Edith couldn't read."))
    }

    private fun <T, R> GoogleCall<T>.map(f: (T) -> R): GoogleCall<R> = when (this) {
        is GoogleCall.Ok -> GoogleCall.Ok(f(value))
        GoogleCall.Unauthorized -> GoogleCall.Unauthorized
        GoogleCall.MissingScope -> GoogleCall.MissingScope
        is GoogleCall.Failed -> this
    }

    private fun url(path: String, query: HttpUrl.Builder.() -> Unit): HttpUrl =
        (baseUrl.trimEnd('/') + "/" + path).toHttpUrl().newBuilder().apply(query).build()

    private fun MessageDto.header(name: String): String? = payload?.headers?.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

    private fun MessageDto.summary() = MailSummary(
        id = id,
        threadId = threadId ?: id,
        from = header("From") ?: "(unknown sender)",
        to = header("To"),
        subject = header("Subject")?.takeIf { it.isNotBlank() } ?: "(no subject)",
        date = Instant.ofEpochMilli(internalDate?.toLongOrNull() ?: 0).atZone(zone()),
        snippet = Jsoup.parse(snippet.orEmpty()).text(),   // Gmail's snippets carry HTML entities
        messageIdHeader = header("Message-ID") ?: header("Message-Id"),
        unread = "UNREAD" in labelIds,
        accountId = accountId,
    )

    companion object {
        const val BASE_URL = "https://gmail.googleapis.com/gmail/v1/users/me"
        const val MAX_SEARCH = 10
        private val METADATA_HEADERS = listOf("From", "To", "Subject", "Date", "Message-ID")
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** Gmail search words `search_email` adjusts before sending. */
object GmailQuery {
    private val DATE = Regex("""\b(after|before|newer|older):(today|yesterday|tomorrow|(\d{4})[/-](\d{1,2})[/-](\d{1,2}))\b""", RegexOption.IGNORE_CASE)

    /**
     * Dates become local midnight in [zone], in epoch seconds:
     * - Gmail reads `after:2026/11/11` as midnight *Pacific* time, so "today" in Michigan would start at 3 AM; its
     *   guide says to pass seconds for other zones.
     * - `today`, `yesterday` and `tomorrow` are Edith's own words, so the model needn't work out the date (it wrote
     *   yesterday's date for "today" in the live check).
     * A date that doesn't exist is left as it is (Gmail then answers 400, which the tool explains).
     */
    fun localDates(query: String, today: LocalDate, zone: ZoneId): String = DATE.replace(query) { m ->
        val (op, word, y, mo, d) = m.destructured
        val day = when (word.lowercase()) {
            "today" -> today
            "yesterday" -> today.minusDays(1)
            "tomorrow" -> today.plusDays(1)
            else -> runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()
        }
        day?.let { "$op:${it.atStartOfDay(zone).toEpochSecond()}" } ?: m.value
    }

    /**
     * A provider-neutral search (C22 §3) in Gmail's syntax. Dates are already exact instants (local midnight, from the
     * tool), so they go as epoch seconds; free text keeps Gmail's own words, with any dates in it made local too.
     */
    fun build(q: MailQuery, today: LocalDate, zone: ZoneId): String = buildList {
        q.from?.takeIf { it.isNotBlank() }?.let { add("from:${term(it)}") }
        q.to?.takeIf { it.isNotBlank() }?.let { add("to:${term(it)}") }
        q.subject?.takeIf { it.isNotBlank() }?.let { add("subject:${group(it)}") }
        if (q.unreadOnly) add("is:unread")
        q.after?.let { add("after:${it.epochSecond}") }
        q.before?.let { add("before:${it.epochSecond}") }
        q.text?.takeIf { it.isNotBlank() }?.let { add(localDates(it.trim(), today, zone)) }
    }.joinToString(" ")

    /** `Lincoln Elementary` → `"Lincoln Elementary"`; one word stays as it is. */
    private fun term(value: String): String {
        val v = value.replace("\"", "").trim()
        return if (v.any { it.isWhitespace() }) "\"$v\"" else v
    }

    /** `field trip` → `(field trip)`: every word in the subject, in any order. */
    private fun group(value: String): String {
        val v = value.replace("(", "").replace(")", "").replace("\"", "").trim()
        return if (v.any { it.isWhitespace() }) "($v)" else v
    }
}

/**
 * The text of an email (C19 §1): a MIME tree to plain text, and an outgoing email to Gmail's `raw` (RFC 2822,
 * base64url).
 */
object MailText {
    const val MAX_BODY = 6_000

    /** Plain text when there is any, else the HTML part made readable; quoted history removed; capped at [MAX_BODY]. */
    internal fun body(part: PartDto?): String {
        val plain = find(part, "text/plain")?.let(::decode)
        val text = plain ?: find(part, "text/html")?.let(::decode)?.let(::htmlToText) ?: ""
        return clean(text)
    }

    /** Plain text with the quoted history removed, capped at [MAX_BODY] (Outlook's text bodies too, C23 §3). */
    fun clean(text: String): String {
        val clean = stripQuoted(text.replace("\r\n", "\n")).trim()
        return if (clean.length <= MAX_BODY) clean else clean.take(MAX_BODY) + "\n[…the rest of the email is cut]"
    }

    /** The first part of [mime] in the tree (multipart/alternative, multipart/mixed, …), skipping attachments. */
    private fun find(part: PartDto?, mime: String): PartDto? {
        if (part == null) return null
        if (part.mimeType.equals(mime, ignoreCase = true) && part.filename.isNullOrEmpty() && part.body?.data != null) return part
        return part.parts.firstNotNullOfOrNull { find(it, mime) }
    }

    private fun decode(part: PartDto): String = String(Base64.getUrlDecoder().decode(part.body!!.data!!.trimEnd('=')), Charsets.UTF_8)

    internal fun htmlToText(html: String): String = PageReader.readable(Jsoup.parse(html)).content

    /**
     * Removes the quoted earlier messages where it's obvious: from an "On … wrote:" line (Gmail, Apple Mail) or an
     * "-----Original Message-----" line (Outlook) on, and lines starting with ">".
     */
    fun stripQuoted(text: String): String {
        val lines = text.lines()
        val cut = lines.indices.firstOrNull { i ->
            val l = lines[i].trim()
            l.startsWith("-----Original Message-----", ignoreCase = true) ||
                (l.startsWith("On ") && (l.endsWith("wrote:") || (i + 1 < lines.size && lines[i + 1].trim().endsWith("wrote:") && l.length < 120)))
        } ?: lines.size
        return lines.take(cut).filterNot { it.trimStart().startsWith(">") }.joinToString("\n").trimEnd()
    }

    /** RFC 2822, base64url: To, Subject (RFC 2047 when not ASCII), reply headers, a UTF-8 plain-text body. */
    fun raw(email: OutgoingMail): String {
        val headers = buildList {
            add("To: ${email.to.replace(Regex("[\r\n]"), " ")}")
            add("Subject: ${encodeHeader(email.subject.replace(Regex("[\r\n]"), " "))}")
            email.inReplyTo?.let { add("In-Reply-To: $it"); add("References: $it") }
            add("MIME-Version: 1.0")
            add("Content-Type: text/plain; charset=UTF-8")
            add("Content-Transfer-Encoding: base64")
        }
        val body = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(email.body.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(Charsets.UTF_8))
        val message = headers.joinToString("\r\n") + "\r\n\r\n" + body
        return Base64.getUrlEncoder().withoutPadding().encodeToString(message.toByteArray(Charsets.UTF_8))
    }

    /** `Café` → `=?UTF-8?B?Q2Fmw6k=?=`; plain ASCII is left as it is. */
    fun encodeHeader(value: String): String =
        if (value.all { it.code in 32..126 }) value else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)) + "?="
}

@Serializable private class MessageListDto(val messages: List<MessageRefDto> = emptyList(), val nextPageToken: String? = null)
@Serializable private class MessageRefDto(val id: String)
@Serializable private class IdDto(val id: String)
@Serializable internal class MessageDto(
    val id: String,
    val threadId: String? = null,
    val labelIds: List<String> = emptyList(),
    val snippet: String? = null,
    val internalDate: String? = null,
    val payload: PartDto? = null,
)
@Serializable internal class PartDto(
    val mimeType: String? = null,
    val filename: String? = null,
    val headers: List<HeaderDto> = emptyList(),
    val body: BodyDto? = null,
    val parts: List<PartDto> = emptyList(),
)
@Serializable internal class HeaderDto(val name: String, val value: String)
@Serializable internal class BodyDto(val data: String? = null, val size: Int? = null)
