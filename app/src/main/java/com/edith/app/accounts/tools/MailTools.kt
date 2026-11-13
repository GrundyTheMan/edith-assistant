package com.edith.app.accounts.tools

import com.edith.app.accounts.AccountDirectory
import com.edith.app.accounts.AccountEntry
import com.edith.app.accounts.Accounts
import com.edith.app.accounts.sources.MailQuery
import com.edith.app.accounts.sources.MailSource
import com.edith.app.accounts.sources.MailSummary
import com.edith.app.accounts.sources.OutgoingMail
import com.edith.app.accounts.sources.Readable
import com.edith.app.accounts.sources.Reads
import com.edith.app.chat.engine.ConfirmationRequest
import com.edith.app.core.error.Outcome
import com.edith.app.google.gmail.MailText
import com.edith.app.tools.framework.ArgumentError
import com.edith.app.tools.framework.ParamType
import com.edith.app.tools.framework.Tool
import com.edith.app.tools.framework.ToolArgs
import com.edith.app.tools.framework.ToolContext
import com.edith.app.tools.framework.ToolParam
import com.edith.app.tools.framework.ToolResult
import com.edith.app.tools.framework.ToolSpec
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Emails Edith has found get short handles (`m1`, `m2`, …) the model passes back to read or reply to them (C19). One
 * sequence for the whole app run, across accounts (C22 trap 4); each keeps the summary with its account, so a reply
 * goes from the account the email came to, and its card can say who it goes to without asking again.
 */
class MailHandles {
    private val handles = LinkedHashMap<String, MailSummary>()
    private var next = 1

    @Synchronized fun handleFor(email: MailSummary): String {
        handles.entries.firstOrNull { it.value.accountId == email.accountId && it.value.id == email.id }?.let { it.setValue(email); return it.key }
        val h = "m${next++}"
        handles[h] = email
        if (handles.size > MAX) handles.remove(handles.keys.first())
        return h
    }

    @Synchronized fun email(handle: String?): MailSummary? = handle?.trim()?.let { handles[it] }

    companion object { const val MAX = 300 }
}

/**
 * Dates for `search_email`'s `after`/`before` (C19's L31): a date or `today`/`yesterday`/`tomorrow`, as local
 * midnight. The model gives words, not arithmetic (it wrote yesterday's date for "today" in C19's live check).
 */
object MailDates {
    private val DAY = Regex("""^(\d{4})[/-](\d{1,2})[/-](\d{1,2})""")

    fun parse(text: String, today: LocalDate, zone: ZoneId): Instant? {
        val t = text.trim().lowercase()
        val day = when (t) {
            "today" -> today
            "yesterday" -> today.minusDays(1)
            "tomorrow" -> today.plusDays(1)
            else -> DAY.find(t)?.destructured?.let { (y, m, d) -> runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull() }
        } ?: return null
        return day.atStartOfDay(zone).toInstant()
    }
}

private val day = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
private val dayTime = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US)
private const val NEEDS_SEARCH = "I don't have that email. Search first (search_email) and use the message_id it gives."

/** `search_email` (C19, C22): every account's mail unless `account` is given, newest first. */
class SearchEmailTool(private val dir: AccountDirectory, private val handles: MailHandles) : Tool {
    override val spec = ToolSpec(
        "search_email",
        "Search the user's email (every connected account). All fields are optional; give only what the user asked for",
        listOf(
            ToolParam("from", ParamType.String, "Sender's name or address", aliases = listOf("sender")),
            ToolParam("to", ParamType.String, "Recipient's name or address", aliases = listOf("recipient")),
            ToolParam("subject", ParamType.String, "Words in the subject"),
            ToolParam("text", ParamType.String, "Words anywhere in the email", aliases = listOf("query", "q", "search", "words", "keywords")),
            ToolParam("unread_only", ParamType.Boolean, "True for unread emails only", aliases = listOf("unread", "is_unread")),
            ToolParam("after", ParamType.String, "Only emails on or after this day: YYYY-MM-DD, or today / yesterday", aliases = listOf("since", "newer_than")),
            ToolParam("before", ParamType.String, "Only emails before this day: YYYY-MM-DD, or today / yesterday", aliases = listOf("until", "older_than")),
            ToolParam("max_results", ParamType.Integer, "How many, 1–10 (default 5)", aliases = listOf("max", "limit")),
            ACCOUNT_PARAM,
        ),
    )
    override val statusLabel = "Checking your email…"
    override val displayName = "email"
    override val integrationId = Accounts.ID
    override val guidance = "For questions about the user's email, call search_email, then read_email with a message_id when the content " +
        "matters. For today's email use after: today; for yesterday's, after: yesterday and before: today."

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val max = (args.int("max_results") ?: 5).coerceIn(1, MAX)
        val zone = ctx.clock.zone()
        val today = ctx.clock.now().atZone(zone).toLocalDate()
        fun date(name: String) = args.string(name)?.let {
            MailDates.parse(it, today, zone) ?: throw ArgumentError(name, "$name must be a day like 2026-11-12, or today or yesterday")
        }
        val query = MailQuery(
            from = args.string("from"), to = args.string("to"), subject = args.string("subject"), text = args.string("text"),
            unreadOnly = args.bool("unread_only") == true, after = date("after"), before = date("before"),
        )
        val all = dir.entries().filter { it.mail != null }
        val chosen = args.string("account")?.let { name ->
            val ids = dir.byName(name).map { it.id }.toSet()
            all.filter { it.account.id in ids }.takeIf { it.size == 1 }
                ?: return ToolResult.Error("There's no account called '$name'. The accounts are: ${all.joinToString { it.account.display }}.")
        } ?: all
        val read = Reads.readAll(chosen.map { Readable(it.account, it.state, requireNotNull(it.mail)) }) { it.search(query, max) }
        if (read.results.isEmpty() && read.failures.isNotEmpty()) return ToolResult.Error(read.failureText())

        val label = all.size > 1
        val found = read.results.flatMap { (account, r) -> r.messages.map { account to it } }.sortedByDescending { it.second.date }
        val shown = found.take(max)
        val more = found.size > shown.size || read.results.any { it.second.more }
        val text = if (shown.isEmpty()) "No emails match." else shown.mapIndexed { i, (account, e) ->
            "${i + 1}. ${day.format(e.date)} — ${e.from} — \"${e.subject}\"${if (e.unread) " (unread)" else ""} — ${e.snippet.take(120)} " +
                "{message_id: ${handles.handleFor(e)}}" + if (label) " [${account.label}]" else ""
        }.joinToString("\n")
        val moreText = if (more) "More emails match than are shown here; don't say this is all of them." else ""
        return ToolResult.Ok(listOf(text, moreText, read.failureText()).filter { it.isNotEmpty() }.joinToString("\n"))
    }

    companion object { const val MAX = 10 }
}

/** `read_email` (C19): one email in full, from its own account. */
class ReadEmailTool(private val dir: AccountDirectory, private val handles: MailHandles) : Tool {
    override val spec = ToolSpec(
        "read_email",
        "Read one email from search_email in full",
        listOf(ToolParam("message_id", ParamType.String, "The message_id from search_email, e.g. m2", required = true, aliases = listOf("id", "message"))),
    )
    override val statusLabel = "Reading the email…"
    override val displayName = "email"
    override val integrationId = Accounts.ID
    override val maxResultChars = MailText.MAX_BODY + 600

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val known = handles.email(args.string("message_id")) ?: return ToolResult.Error(NEEDS_SEARCH)
        val (entry, refused) = ownAccount(dir, known.accountId)
        if (entry == null) return requireNotNull(refused)
        val m = when (val r = requireNotNull(entry.mail).read(known.id)) { is Outcome.Success -> r.value; is Outcome.Failure -> return accountError(entry.account, r.error) }
        handles.handleFor(m.summary)
        val s = m.summary
        return ToolResult.Ok(buildString {
            append("From: ").append(s.from).append('\n')
            s.to?.let { append("To: ").append(it).append('\n') }
            m.cc?.let { append("Cc: ").append(it).append('\n') }
            append("Date: ").append(dayTime.format(s.date)).append('\n')
            append("Subject: ").append(s.subject).append("\n\n")
            append(m.body.ifBlank { "(no text)" })
        })
    }
}

/** What draft_email and send_email share: which account, who, what, and the original when it's a reply. */
private abstract class WriteEmailTool(protected val dir: AccountDirectory, protected val handles: MailHandles) : Tool {
    protected fun params() = listOf(
        ToolParam("to", ParamType.String, "Email address (for a reply, leave it out to answer the sender)", aliases = listOf("recipient", "address")),
        ToolParam("subject", ParamType.String, "Subject (for a reply, leave it out to use \"Re: …\")"),
        ToolParam("body", ParamType.String, "The message text, in full", required = true, aliases = listOf("message", "text")),
        ToolParam("reply_to_message_id", ParamType.String, "To reply: the message_id from search_email", aliases = listOf("reply_to", "in_reply_to")),
        ACCOUNT_PARAM,
    )
    override val displayName = "email"
    override val integrationId = Accounts.ID

    private sealed interface Prepared {
        data class Ready(val entry: AccountEntry, val email: OutgoingMail) : Prepared
        data class Stop(val result: ToolResult) : Prepared
    }

    /** The account it goes from, and the email, with a reply's recipient, subject and thread filled in. */
    private suspend fun prepare(args: ToolArgs): Prepared {
        val replyHandle = args.string("reply_to_message_id")
        val original = replyHandle?.let { handles.email(it) ?: throw ArgumentError("reply_to_message_id", NEEDS_SEARCH) }
        val entry = if (original != null) {
            // A reply goes from the account the email came to (D17); no question.
            val (e, refused) = ownAccount(dir, original.accountId)
            e ?: return Prepared.Stop(requireNotNull(refused))
        } else {
            when (val c = WhichAccount.forMail(dir, args.string("account"))) {
                is WhichAccount.Choice.Use -> c.entry
                is WhichAccount.Choice.Stop -> return Prepared.Stop(c.result)
            }
        }
        val to = args.string("to") ?: original?.let { replyAddress(it.from) } ?: throw ArgumentError("to", "missing to")
        if ('@' !in to) throw ArgumentError("to", "to must be an email address")
        val subject = args.string("subject") ?: original?.subject?.let { if (it.startsWith("Re:", ignoreCase = true)) it else "Re: $it" } ?: ""
        return Prepared.Ready(entry, OutgoingMail(to, subject, requireNotNull(args.string("body")), original?.messageIdHeader, original?.threadId, original?.id))
    }

    /** The card names the account first, then shows the whole message, never a preview (D5). */
    protected fun details(entry: AccountEntry, e: OutgoingMail) = listOf(
        "From" to entry.account.display,
        "To" to e.to,
        "Subject" to e.subject.ifBlank { "(none)" },
        "Message" to e.body,
    )

    protected abstract val cardTitle: String
    protected abstract val cardButton: String
    protected abstract suspend fun deliver(source: MailSource, email: OutgoingMail): Outcome<Unit>
    protected abstract fun done(entry: AccountEntry, email: OutgoingMail): String

    override suspend fun confirmation(args: ToolArgs): ConfirmationRequest? = when (val p = prepare(args)) {
        is Prepared.Ready -> ConfirmationRequest(cardTitle, details(p.entry, p.email), confirmLabel = cardButton)
        is Prepared.Stop -> null   // execute explains (which account? / can't be used): no card, no request
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val p = when (val r = prepare(args)) { is Prepared.Ready -> r; is Prepared.Stop -> return r.result }
        return when (val r = deliver(requireNotNull(p.entry.mail), p.email)) {
            is Outcome.Success -> ToolResult.Ok(done(p.entry, p.email))
            is Outcome.Failure -> accountError(p.entry.account, r.error)
        }
    }

    companion object {
        /** "Sam Lee <sam@example.com>" → "sam@example.com". */
        fun replyAddress(from: String): String = Regex("<([^>]+)>").find(from)?.groupValues?.get(1) ?: from.trim()
    }
}

/** `draft_email` (C19): saves a draft, after the owner confirms (D11). */
private class DraftEmailTool(dir: AccountDirectory, handles: MailHandles) : WriteEmailTool(dir, handles) {
    override val spec = ToolSpec("draft_email", "Save an email as a draft in the user's mailbox, to send later (the user confirms first)", params())
    override val statusLabel = "Saving the draft…"
    override val guidance = "To write an email, call draft_email, or send_email when the user clearly asks to send it. The app shows the " +
        "user the whole email to confirm, so don't ask in chat first. Write the full message yourself, signed as the user would."
    override val cardTitle = "Save this draft?"
    override val cardButton = "Save draft"
    override suspend fun deliver(source: MailSource, email: OutgoingMail) = source.draft(email)
    override fun done(entry: AccountEntry, email: OutgoingMail) = "Draft saved in ${entry.account.display} — open that mailbox to send it."
}

/** `send_email` (D5): sends, only after the owner confirms a card with the whole email. */
private class SendEmailTool(dir: AccountDirectory, handles: MailHandles) : WriteEmailTool(dir, handles) {
    override val spec = ToolSpec("send_email", "Send an email from the user's mailbox (the user sees it and confirms first)", params())
    override val statusLabel = "Sending the email…"
    override val cardTitle = "Send this email?"
    override val cardButton = "Send"
    override suspend fun deliver(source: MailSource, email: OutgoingMail) = source.send(email)
    override fun done(entry: AccountEntry, email: OutgoingMail) = "Sent to ${email.to} from ${entry.account.display}."
}

/** The email tools (C19, C22), sharing one set of handles. */
fun mailTools(dir: AccountDirectory, handles: MailHandles): List<Tool> = listOf(
    SearchEmailTool(dir, handles), ReadEmailTool(dir, handles), DraftEmailTool(dir, handles), SendEmailTool(dir, handles),
)
