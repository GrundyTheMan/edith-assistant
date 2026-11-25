package com.edith.app.icloud.mail

import com.edith.app.accounts.Account
import com.edith.app.accounts.sources.MailBody
import com.edith.app.accounts.sources.MailMessage
import com.edith.app.accounts.sources.MailQuery
import com.edith.app.accounts.sources.MailSearchResult
import com.edith.app.accounts.sources.MailSource
import com.edith.app.accounts.sources.MailSummary
import com.edith.app.accounts.sources.OutgoingMail
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.IOException
import kotlin.time.Clock
import kotlin.time.Instant

/** Where iCloud's mail servers are (Apple's settings); tests give a fake transport and ignore them. */
data class ImapServers(
    val imapHost: String = "imap.mail.me.com",
    val imapPort: Int = 993,
    val smtpHost: String = "smtp.mail.me.com",
    val smtpPort: Int = 587,
)

/**
 * One iCloud account's mail over IMAP and SMTP on the iPhone (K4.10; Android's is `ICloudMailSource` over Jakarta Mail), in `INBOX`: one connection per
 * operation. Searching and reading never mark mail as read: the inbox is opened read-only (`EXAMINE`) and fetches use `BODY.PEEK`. Message ids are
 * `UIDVALIDITY:UID`. Nothing about the mail, the address or the password is logged.
 */
class ImapMailSource(
    private val accountOf: () -> Account,
    private val password: suspend () -> String?,
    private val transports: MailTransportFactory,
    private val dispatchers: AppDispatchers,
    private val servers: ImapServers = ImapServers(),
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val now: () -> Instant = Clock.System::now,
    private val onPasswordRejected: suspend () -> Unit = {},
) : MailSource {
    override val account: Account get() = accountOf()

    override suspend fun search(q: MailQuery, max: Int): Outcome<MailSearchResult> = op {
        val limit = max.coerceIn(1, MAX_SEARCH)
        withInbox { imap, box ->
            val uids = if (box.exists == 0) emptyList() else {
                val criteria = criteria(q)
                if (criteria == null) imap.newestUids(SEARCH_WINDOW) else imap.uidSearch(criteria).takeLast(SEARCH_WINDOW)
            }
            val rows = imap.fetchHeaders(uids).map { summary(box.uidValidity, it) }
            // IMAP dates are whole days: the search narrows, and the exact instants (and any words the server could not be asked) are checked here.
            val exact = rows.filter { s ->
                (q.after == null || s.date >= q.after) && (q.before == null || s.date < q.before) && clientMatches(q, s)
            }.sortedByDescending { it.date }
            MailSearchResult(exact.take(limit), more = exact.size > limit)
        }
    }

    override suspend fun read(id: String): Outcome<MailMessage> = op {
        val (validity, uid) = parseId(id) ?: throw MailException(LIST_AGAIN)
        withInbox { imap, box ->
            if (box.uidValidity != validity) throw MailException(LIST_AGAIN)
            val row = imap.fetchHeaders(listOf(uid)).firstOrNull() ?: throw MailException(GONE)
            val raw = imap.fetchMessage(uid) ?: throw MailException(GONE)
            val entity = Mime.parse(raw)
            val cc = entity.header("Cc")?.let(::displayList)
            MailMessage(summary(validity, row), cc, MailBody.clean(Mime.readableText(entity)))
        }
    }

    /** `APPEND` to iCloud's Drafts folder (the one flagged `\Drafts` if any, else its fixed name), marked `\Draft \Seen`. */
    override suspend fun draft(m: OutgoingMail): Outcome<Unit> = op {
        val pw = password() ?: throw MailException(PASSWORD_STOPPED, passwordRejected = true)
        val imap = connect(pw)
        try {
            val folder = imap.list().let { all -> all.firstOrNull { "\\Drafts" in it.attributes } ?: all.firstOrNull { it.name == "Drafts" } }
                ?: throw MailException("Edith couldn't find iCloud's Drafts folder.")
            imap.append(folder.name, "\\Draft \\Seen", SmtpClient.composeMessage(accountOf().address, m, now()))
        } finally {
            imap.logout()
        }
    }

    /** SMTP with STARTTLS and the full address. iCloud keeps the copy in Sent itself, so none is appended. */
    override suspend fun send(m: OutgoingMail): Outcome<Unit> = op {
        val pw = password() ?: throw MailException(PASSWORD_STOPPED, passwordRejected = true)
        val recipients = addresses(m.to)
        if (recipients.isEmpty()) throw MailException("That email has no recipient address.")
        val transport = transports.open(servers.smtpHost, servers.smtpPort, tls = false)
        try {
            SmtpClient(transport).send(accountOf().address, pw, accountOf().address, recipients, SmtpClient.composeMessage(accountOf().address, m, now()))
        } finally {
            transport.close()
        }
        Log.i(LogTag.Accounts, "iCloud mail ${accountOf().id}: sent")
    }

    /** IMAP login only: the add-account check. Throws [MailException] when the password is refused. */
    suspend fun login(user: String, pw: String) = withContext(dispatchers.io) {
        val imap = ImapClient(transports.open(servers.imapHost, servers.imapPort, tls = true))
        try {
            imap.greeting()
            imap.login(user, pw)
        } finally {
            imap.logout()
        }
    }

    // ---- Inside ----

    private suspend fun connect(pw: String): ImapClient {
        val imap = ImapClient(transports.open(servers.imapHost, servers.imapPort, tls = true))
        try {
            imap.greeting()
            imap.login(accountOf().login ?: accountOf().address, pw)
        } catch (e: Exception) {
            imap.logout()
            throw e
        }
        return imap
    }

    private suspend fun <T> withInbox(block: suspend (ImapClient, ImapClient.Mailbox) -> T): T {
        val pw = password() ?: throw MailException(PASSWORD_STOPPED, passwordRejected = true)
        val imap = connect(pw)
        try {
            return block(imap, imap.examine("INBOX"))   // EXAMINE: nothing Edith does here can set \Seen
        } finally {
            imap.logout()
        }
    }

    private suspend fun <T> op(block: suspend () -> T): Outcome<T> = withContext(dispatchers.io) {
        try {
            Outcome.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: MailException) {
            if (e.passwordRejected) {
                Log.w(LogTag.Accounts, "iCloud mail ${accountOf().id}: the password was refused")
                onPasswordRejected()
                Outcome.Failure(AppError.GoogleAuth(PASSWORD_STOPPED))
            } else {
                Outcome.Failure(AppError.ToolFailed("email", e.why))
            }
        } catch (e: IOException) {
            Log.w(LogTag.Accounts, "iCloud mail ${accountOf().id}: no connection")
            Outcome.Failure(AppError.ToolFailed("email", NO_NETWORK))
        }
    }

    private fun summary(validity: Long, row: ImapHeaderRow): MailSummary {
        val h = Mime.parse(row.header + "\r\n\r\n")
        val messageId = h.header("Message-ID")?.trim()?.takeIf { it.isNotEmpty() }
        val id = "$validity:${row.uid}"
        return MailSummary(
            id = id,
            threadId = messageId ?: id,
            from = h.header("From")?.let(::displayList)?.takeIf { it.isNotBlank() } ?: "(unknown sender)",
            to = h.header("To")?.let(::displayList)?.ifBlank { null },
            subject = h.header("Subject")?.let(Mime::decodeWords)?.takeIf { it.isNotBlank() } ?: "(no subject)",
            date = row.internalDate?.let(::parseInternalDate) ?: h.header("Date")?.let(::parseRfc2822Date) ?: Instant.fromEpochSeconds(0),
            snippet = "",   // only the headers are fetched for a search
            messageIdHeader = messageId,
            unread = "\\Seen" !in row.flags,
            accountId = accountOf().id,
        )
    }

    /** Search words the server can be asked in plain ASCII go in the criteria; the rest are matched on the headers afterwards ([clientMatches]). */
    private fun criteria(q: MailQuery): String? {
        val parts = ArrayList<String>()
        fun ascii(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() && it.all { c -> c.code in 32..126 } }
        ascii(q.from)?.let { parts += "FROM ${ImapClient.quote(it)}" }
        ascii(q.to)?.let { parts += "TO ${ImapClient.quote(it)}" }
        ascii(q.subject)?.let { parts += "SUBJECT ${ImapClient.quote(it)}" }
        ascii(q.text)?.let { parts += "OR SUBJECT ${ImapClient.quote(it)} BODY ${ImapClient.quote(it)}" }
        if (q.unreadOnly) parts += "UNSEEN"
        q.after?.let { parts += "SINCE ${imapDate(it.toLocalDateTime(zone()).date)}" }
        // BEFORE excludes its own day, so the day after [before] makes sure the day it falls in is searched; the exact instant is checked afterwards.
        q.before?.let { parts += "BEFORE ${imapDate(it.toLocalDateTime(zone()).date.plus(1, DateTimeUnit.DAY))}" }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun clientMatches(q: MailQuery, s: MailSummary): Boolean {
        fun nonAscii(x: String?) = x?.trim()?.takeIf { it.isNotEmpty() && it.any { c -> c.code !in 32..126 } }
        nonAscii(q.from)?.let { if (!s.from.contains(it, ignoreCase = true)) return false }
        nonAscii(q.to)?.let { if (s.to?.contains(it, ignoreCase = true) != true) return false }
        nonAscii(q.subject)?.let { if (!s.subject.contains(it, ignoreCase = true)) return false }
        nonAscii(q.text)?.let { if (!s.subject.contains(it, ignoreCase = true)) return false }
        return true
    }

    companion object {
        const val MAX_SEARCH = 10
        /** The newest messages looked at for a search. */
        const val SEARCH_WINDOW = 50
        const val PASSWORD_STOPPED = "The app-specific password stopped working: it was revoked, or your Apple Account password changed. Make a new one."
        const val NO_NETWORK = "Couldn't reach iCloud Mail. Check the phone's connection."
        const val LIST_AGAIN = "The mailbox changed since that list; list the emails again."
        const val GONE = "That email isn't there any more."

        private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

        /** "1234:56" → (UIDVALIDITY, UID). */
        fun parseId(id: String): Pair<Long, Long>? {
            val parts = id.split(':').takeIf { it.size == 2 } ?: return null
            return (parts[0].toLongOrNull() ?: return null) to (parts[1].toLongOrNull() ?: return null)
        }

        /** IMAP's date for SEARCH: `25-Nov-2026`. */
        fun imapDate(d: LocalDate): String = "${d.day}-${MONTHS[d.month.ordinal]}-${d.year}"

        /** INTERNALDATE: `25-Nov-2026 14:03:22 +0100`. */
        fun parseInternalDate(s: String): Instant? {
            val m = Regex("""^\s*(\d{1,2})-([A-Za-z]{3})-(\d{4}) (\d{2}):(\d{2}):(\d{2}) ([+-])(\d{2})(\d{2})""").find(s) ?: return null
            val month = MONTHS.indexOfFirst { it.equals(m.groupValues[2], ignoreCase = true) }.takeIf { it >= 0 } ?: return null
            return zoned(m.groupValues[3].toInt(), month + 1, m.groupValues[1].toInt(), m.groupValues[4].toInt(), m.groupValues[5].toInt(), m.groupValues[6].toInt(), m.groupValues[7], m.groupValues[8].toInt(), m.groupValues[9].toInt())
        }

        /** The `Date:` header: `Wed, 25 Nov 2026 14:03:22 +0100` (the weekday is optional; named zones are read as UTC). */
        fun parseRfc2822Date(s: String): Instant? {
            val m = Regex("""(\d{1,2}) ([A-Za-z]{3}) (\d{4}) (\d{1,2}):(\d{2})(?::(\d{2}))? ?([+-])?(\d{2})?(\d{2})?""").find(s) ?: return null
            val month = MONTHS.indexOfFirst { it.equals(m.groupValues[2], ignoreCase = true) }.takeIf { it >= 0 } ?: return null
            return zoned(
                m.groupValues[3].toInt(), month + 1, m.groupValues[1].toInt(), m.groupValues[4].toInt(), m.groupValues[5].toInt(), m.groupValues[6].toIntOrNull() ?: 0,
                m.groupValues[7].ifEmpty { "+" }, m.groupValues[8].toIntOrNull() ?: 0, m.groupValues[9].toIntOrNull() ?: 0,
            )
        }

        private fun zoned(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, sign: String, offH: Int, offM: Int): Instant? = runCatching {
            val seconds = (offH * 3600 + offM * 60) * if (sign == "-") -1 else 1
            LocalDateTime(year, month, day, hour, minute, second).toInstant(UtcOffset(seconds = seconds))
        }.getOrNull()

        /** `Name <a@b>, c@d` with RFC 2047 words decoded. */
        fun displayList(raw: String): String = Mime.decodeWords(raw).replace(Regex("\\s+"), " ").trim()

        /** The bare addresses in a `To:` text: `Name <a@b>, c@d` → `a@b`, `c@d`. */
        fun addresses(text: String): List<String> = text.split(',', ';').mapNotNull { part ->
            val t = part.trim()
            val bracket = Regex("""<([^<>\s]+@[^<>\s]+)>""").find(t)?.groupValues?.get(1)
            (bracket ?: t.takeIf { Regex("""^[^<>\s@]+@[^<>\s@]+$""").matches(it) })
        }
    }
}
