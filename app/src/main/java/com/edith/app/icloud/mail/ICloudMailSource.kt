package com.edith.app.icloud.mail

import kotlinx.datetime.TimeZone
import com.edith.app.accounts.Account
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
import com.edith.app.accounts.sources.MailBody
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.search.AndTerm
import jakarta.mail.search.BodyTerm
import jakarta.mail.search.ComparisonTerm
import jakarta.mail.search.FlagTerm
import jakarta.mail.search.FromStringTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.ReceivedDateTerm
import jakarta.mail.search.RecipientStringTerm
import jakarta.mail.search.SearchTerm
import jakarta.mail.search.SubjectTerm
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.time.Instant
import java.util.Date
import java.util.Properties

/** Where iCloud's mail servers are (C24 §2, Apple's settings); tests point them at GreenMail. */
data class MailServers(
    val imapHost: String = "imap.mail.me.com",
    val imapPort: Int = 993,
    /** IMAP over TLS from the start (iCloud); false only for a test server. */
    val imapSsl: Boolean = true,
    val smtpHost: String = "smtp.mail.me.com",
    val smtpPort: Int = 587,
    /** STARTTLS required (iCloud); false only for a test server. */
    val smtpStartTls: Boolean = true,
    val timeoutMs: Int = 15_000,
)

/** A mail operation's failure, in words, and whether it means the password stopped working. */
class ICloudMailException(val why: String, val passwordRejected: Boolean = false, val network: Boolean = false) : Exception(why)

/**
 * One iCloud account's mail over IMAP and SMTP (C24 §2) with Jakarta Mail: one connection per operation, in `INBOX`.
 * Searching and reading never mark mail as read: the inbox is opened read-only and fetches use `BODY.PEEK` (trap 2).
 * Message ids are `UIDVALIDITY:UID`. Nothing about the mail, the address or the password is logged (trap 4).
 */
class ICloudMailSource(
    private val accountOf: () -> Account,
    private val password: suspend () -> String?,
    private val dispatchers: AppDispatchers,
    private val servers: MailServers = MailServers(),
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    /** Copy a sent email into the `\Sent` folder (only if iCloud doesn't already: checked live, C24 §2). */
    private val appendToSent: Boolean = APPEND_TO_SENT,
    private val onPasswordRejected: suspend () -> Unit = {},
    /** Finds a SPECIAL-USE folder (tests swap it: GreenMail doesn't report the flags). */
    private val findFolder: (Store, String) -> Folder? = ::specialFolder,
) : MailSource {
    override val account: Account get() = accountOf()

    override suspend fun search(q: MailQuery, max: Int): Outcome<MailSearchResult> = withInbox { inbox ->
        val limit = max.coerceIn(1, MAX_SEARCH)
        val term = term(q)
        // IMAP dates are whole days: SEARCH narrows, then the exact instants are checked here with INTERNALDATE.
        val found = if (term == null) {
            val n = inbox.messageCount
            inbox.getMessages((n - SEARCH_WINDOW + 1).coerceAtLeast(1), n).toList().takeIf { n > 0 } ?: emptyList()
        } else {
            inbox.search(term).toList()
        }
        inbox.fetch(found.toTypedArray(), FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.FLAGS); add(UIDFolder.FetchProfileItem.UID) })
        val exact = found.filter { m ->
            val at = m.receivedDate?.let { Instant.fromEpochMilliseconds(it.time) } ?: return@filter true
            q.after.let { a -> a == null || at >= a } && q.before.let { b -> b == null || at < b }
        }.sortedByDescending { it.receivedDate }
        val validity = (inbox as UIDFolder).uidValidity
        MailSearchResult(exact.take(limit).map { summary(inbox, validity, it) }, more = exact.size > limit)
    }

    override suspend fun read(id: String): Outcome<MailMessage> = withInbox { inbox ->
        val (validity, uid) = parseId(id) ?: throw ICloudMailException(LIST_AGAIN)
        val folder = inbox as UIDFolder
        if (folder.uidValidity != validity) throw ICloudMailException(LIST_AGAIN)
        val m = folder.getMessageByUID(uid) ?: throw ICloudMailException("That email isn't there any more.")
        val cc = (m.getRecipients(Message.RecipientType.CC) ?: emptyArray()).joinToString(", ") { it.toString() }.ifBlank { null }
        MailMessage(summary(inbox, validity, m), cc, MailBody.clean(text(m)))
    }

    /** `APPEND` to the folder flagged `\Drafts` (by SPECIAL-USE, never by name), marked `\Draft`. */
    override suspend fun draft(m: OutgoingMail): Outcome<Unit> = withStore { store ->
        val drafts = findFolder(store, "\\Drafts") ?: throw ICloudMailException("Edith couldn't find iCloud's Drafts folder.")
        val message = message(m).apply { setFlag(Flags.Flag.DRAFT, true); setFlag(Flags.Flag.SEEN, true) }
        drafts.open(Folder.READ_WRITE)
        try { drafts.appendMessages(arrayOf(message)) } finally { drafts.close(false) }
    }

    /** SMTP with STARTTLS and the full address; then a copy in `\Sent` if iCloud doesn't keep one itself. */
    override suspend fun send(m: OutgoingMail): Outcome<Unit> = op {
        val pw = password() ?: throw ICloudMailException(PASSWORD_STOPPED, passwordRejected = true)
        val message = message(m)
        session().getTransport("smtp").use { t ->
            t.connect(servers.smtpHost, servers.smtpPort, accountOf().address, pw)
            t.sendMessage(message, message.allRecipients)
        }
        Log.i(LogTag.Accounts, "iCloud mail ${accountOf().id}: sent")
        if (appendToSent) {
            withStoreBlocking(pw) { store ->
                findFolder(store, "\\Sent")?.let { sent ->
                    message.setFlag(Flags.Flag.SEEN, true)
                    sent.open(Folder.READ_WRITE)
                    try { sent.appendMessages(arrayOf(message)) } finally { sent.close(false) }
                }
            }
        }
    }

    /** IMAP login only: the add-account check (C24 §1). Throws on failure. */
    fun login(user: String, pw: String) {
        session().getStore(storeProtocol()).use { it.connect(servers.imapHost, servers.imapPort, user, pw) }
    }

    // --- Inside -------------------------------------------------------------------------------------------------

    private suspend fun <T> withInbox(block: (Folder) -> T): Outcome<T> = withStore { store ->
        val inbox = store.getFolder("INBOX")
        inbox.open(Folder.READ_ONLY)   // EXAMINE: nothing Edith does here can set \Seen
        try { block(inbox) } finally { runCatching { inbox.close(false) } }
    }

    private suspend fun <T> withStore(block: (Store) -> T): Outcome<T> = op {
        val pw = password() ?: throw ICloudMailException(PASSWORD_STOPPED, passwordRejected = true)
        withStoreBlocking(pw, block)
    }

    private fun <T> withStoreBlocking(pw: String, block: (Store) -> T): T =
        session().getStore(storeProtocol()).use { store ->
            store.connect(servers.imapHost, servers.imapPort, accountOf().login ?: accountOf().address, pw)
            block(store)
        }

    private suspend fun <T> op(block: suspend () -> T): Outcome<T> = withContext(dispatchers.io) {
        try {
            Outcome.Success(block())
        } catch (e: AuthenticationFailedException) {
            Log.w(LogTag.Accounts, "iCloud mail ${accountOf().id}: the password was refused")
            onPasswordRejected()
            Outcome.Failure(AppError.GoogleAuth(PASSWORD_STOPPED))
        } catch (e: ICloudMailException) {
            if (e.passwordRejected) onPasswordRejected()
            Outcome.Failure(if (e.passwordRejected) AppError.GoogleAuth(e.why) else AppError.ToolFailed("email", e.why))
        } catch (e: MessagingException) {
            val network = generateSequence<Throwable>(e) { it.cause }.any { it is IOException }
            Log.w(LogTag.Accounts, "iCloud mail ${accountOf().id}: ${if (network) "no connection" else "the server said no"}")
            Outcome.Failure(AppError.ToolFailed("email", if (network) NO_NETWORK else "iCloud Mail said no. Try again."))
        } catch (e: IOException) {
            Log.w(LogTag.Accounts, "iCloud mail ${accountOf().id}: no connection")
            Outcome.Failure(AppError.ToolFailed("email", NO_NETWORK))
        }
    }

    private fun storeProtocol() = if (servers.imapSsl) "imaps" else "imap"

    private fun session(): Session {
        val p = Properties()
        val imap = storeProtocol()
        p["mail.$imap.connectiontimeout"] = servers.timeoutMs.toString()
        p["mail.$imap.timeout"] = servers.timeoutMs.toString()
        p["mail.$imap.peek"] = "true"   // BODY.PEEK: reading never sets \Seen (trap 2)
        if (servers.imapSsl) p["mail.imaps.ssl.checkserveridentity"] = "true"
        p["mail.smtp.auth"] = "true"
        p["mail.smtp.connectiontimeout"] = servers.timeoutMs.toString()
        p["mail.smtp.timeout"] = servers.timeoutMs.toString()
        if (servers.smtpStartTls) {
            p["mail.smtp.starttls.enable"] = "true"
            p["mail.smtp.starttls.required"] = "true"
            p["mail.smtp.ssl.checkserveridentity"] = "true"
        }
        return Session.getInstance(p).apply { debug = false }   // trap 4: never the IMAP conversation
    }

    private fun term(q: MailQuery): SearchTerm? {
        val terms = buildList<SearchTerm> {
            q.from?.takeIf { it.isNotBlank() }?.let { add(FromStringTerm(it.trim())) }
            q.to?.takeIf { it.isNotBlank() }?.let { add(RecipientStringTerm(Message.RecipientType.TO, it.trim())) }
            q.subject?.takeIf { it.isNotBlank() }?.let { add(SubjectTerm(it.trim())) }
            q.text?.takeIf { it.isNotBlank() }?.let { add(OrTerm(SubjectTerm(it.trim()), BodyTerm(it.trim()))) }
            if (q.unreadOnly) add(FlagTerm(Flags(Flags.Flag.SEEN), false))
            q.after?.let { add(ReceivedDateTerm(ComparisonTerm.GE, Date(it.toEpochMilliseconds()))) }
            q.before?.let { add(ReceivedDateTerm(ComparisonTerm.LE, Date(it.toEpochMilliseconds()))) }   // whole days: checked exactly afterwards
        }
        return when (terms.size) { 0 -> null; 1 -> terms[0]; else -> AndTerm(terms.toTypedArray()) }
    }

    private fun summary(inbox: Folder, validity: Long, m: Message): MailSummary {
        val uid = (inbox as UIDFolder).getUID(m)
        val messageId = (m as? MimeMessage)?.messageID
        return MailSummary(
            id = "$validity:$uid",
            threadId = messageId ?: "$validity:$uid",
            from = m.from?.firstOrNull()?.let(::display) ?: "(unknown sender)",
            to = m.getRecipients(Message.RecipientType.TO)?.joinToString(", ") { display(it) }?.ifBlank { null },
            subject = m.subject?.takeIf { it.isNotBlank() } ?: "(no subject)",
            date = Instant.fromEpochMilliseconds((m.receivedDate ?: m.sentDate ?: Date(0)).time),
            snippet = "",   // only the envelope is fetched for a search (§2)
            messageIdHeader = messageId,
            unread = !m.isSet(Flags.Flag.SEEN),
            accountId = accountOf().id,
        )
    }

    private fun display(a: jakarta.mail.Address): String =
        (a as? InternetAddress)?.let { ia -> ia.personal?.takeIf { it.isNotBlank() }?.let { "$it <${ia.address}>" } ?: ia.address } ?: a.toString()

    /** Plain text when there is any, else the HTML part made readable; attachments skipped. */
    private fun text(p: Part): String {
        fun find(part: Part, mime: String): String? {
            if (Part.ATTACHMENT.equals(part.disposition, ignoreCase = true)) return null
            if (part.isMimeType(mime)) return part.content as? String
            val multi = part.content as? Multipart ?: return null
            return (0 until multi.count).firstNotNullOfOrNull { find(multi.getBodyPart(it), mime) }
        }
        return find(p, "text/plain") ?: find(p, "text/html")?.let(MailBody::htmlToText) ?: ""
    }

    /** RFC 2822 with RFC 2047 headers; CR/LF never reach a header (no header injection). */
    private fun message(m: OutgoingMail): MimeMessage = MimeMessage(session()).apply {
        val clean = { s: String -> s.replace(Regex("[\r\n]+"), " ").trim() }
        setFrom(InternetAddress(accountOf().address))
        setRecipients(Message.RecipientType.TO, InternetAddress.parse(clean(m.to), false))
        setSubject(clean(m.subject), "UTF-8")
        m.inReplyTo?.let(clean)?.takeIf { it.isNotEmpty() }?.let { setHeader("In-Reply-To", it); setHeader("References", it) }
        setText(m.body, "UTF-8")
        sentDate = Date()
        saveChanges()
    }

    companion object {
        const val MAX_SEARCH = 10
        /** Without search words, the newest messages looked at. */
        const val SEARCH_WINDOW = 50
        /** Set after the live check of whether iCloud copies SMTP sends into Sent (C24 §2). */
        const val APPEND_TO_SENT = false
        const val PASSWORD_STOPPED = "The app-specific password stopped working: it was revoked, or your Apple Account password changed. Make a new one."
        const val NO_NETWORK = "Couldn't reach iCloud Mail. Check the phone's connection."
        const val LIST_AGAIN = "The mailbox changed since that list; list the emails again."

        /** "1234:56" → (UIDVALIDITY, UID). */
        fun parseId(id: String): Pair<Long, Long>? {
            val (a, b) = id.split(':').takeIf { it.size == 2 } ?: return null
            return (a.toLongOrNull() ?: return null) to (b.toLongOrNull() ?: return null)
        }

        /**
         * iCloud's own server names, used only when no folder carries the flag. Checked live on 2026-11-13: iCloud has no
         * SPECIAL-USE capability and flags only `\Sent` ("Sent Messages") and `\Trash` ("Deleted Messages"); "Drafts"
         * has no flag. These IMAP names are iCloud's fixed English names, not translated by the phone's language.
         */
        private val ICLOUD_NAMES = mapOf("\\Drafts" to listOf("Drafts"), "\\Sent" to listOf("Sent Messages"))

        /**
         * The first folder carrying [flag] (`\Drafts`, `\Sent`) as a SPECIAL-USE attribute (RFC 6154; trap 3); when no
         * folder carries it, iCloud's own server name for it.
         */
        fun specialFolder(store: Store, flag: String): Folder? {
            val folders = store.defaultFolder.list("*")
            return folders.firstOrNull { f ->
                (f as? org.eclipse.angus.mail.imap.IMAPFolder)?.attributes?.any { it.equals(flag, ignoreCase = true) } == true
            } ?: ICLOUD_NAMES[flag]?.firstNotNullOfOrNull { name -> folders.firstOrNull { it.fullName == name } }
        }
    }
}
