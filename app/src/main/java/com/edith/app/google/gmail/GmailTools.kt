package com.edith.app.google.gmail

import com.edith.app.chat.engine.ConfirmationRequest
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.google.GoogleAuth
import com.edith.app.tools.framework.ArgumentError
import com.edith.app.tools.framework.ParamType
import com.edith.app.tools.framework.Tool
import com.edith.app.tools.framework.ToolArgs
import com.edith.app.tools.framework.ToolContext
import com.edith.app.tools.framework.ToolParam
import com.edith.app.tools.framework.ToolResult
import com.edith.app.tools.framework.ToolSpec
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Emails Edith has found get short handles (`m1`, `m2`, …) the model passes back to read or reply to them (C19);
 * each keeps the summary, so a reply's confirmation card can say who it goes to without asking Gmail again.
 */
class GmailSession {
    private val handles = LinkedHashMap<String, EmailSummary>()
    private var next = 1

    @Synchronized fun handleFor(email: EmailSummary): String {
        handles.entries.firstOrNull { it.value.id == email.id }?.let { it.setValue(email); return it.key }
        val h = "m${next++}"
        handles[h] = email
        if (handles.size > MAX) handles.remove(handles.keys.first())
        return h
    }

    @Synchronized fun email(handle: String?): EmailSummary? = handle?.trim()?.let { handles[it] }

    companion object { const val MAX = 300 }
}

private val day = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
private val dayTime = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US)
private const val NEEDS_SEARCH = "I don't have that email. Search first (search_email) and use the message_id it gives."

private fun emailError(e: AppError): ToolResult = ToolResult.Error(
    when (e) {
        AppError.GoogleNotConnected -> "Google isn't connected. Tell the user to connect it in Settings → Google account."
        is AppError.GoogleAuth -> if (e.detail == GoogleAuth.NEEDS_PERMISSION) "I need permission to use Gmail. Tell the user to tap Grant access in Settings → Google account." else "Google: ${e.detail}"
        is AppError.ToolFailed -> e.detail
        else -> e.userMessage
    },
)

/** `search_email` (C19 §2): Gmail search, newest first. */
class SearchEmailTool(private val session: GmailSession, private val client: GmailClient) : Tool {
    override val spec = ToolSpec(
        "search_email",
        "Search the user's Gmail. Uses Gmail search syntax: from:name, to:, subject:word, is:unread, newer_than:7d, after:2026/11/01, has:attachment",
        listOf(
            ToolParam("query", ParamType.String, "Gmail search, e.g. \"from:school newer_than:7d\" or \"is:unread newer_than:1d\"", required = true, aliases = listOf("q", "search")),
            ToolParam("max_results", ParamType.Integer, "How many, 1–10 (default 5)", aliases = listOf("max", "limit")),
        ),
    )
    override val statusLabel = "Checking your email…"
    override val displayName = "email"
    override val integrationId = GoogleAuth.ID
    override val guidance = "For questions about the user's email, call search_email with Gmail search syntax, then read_email with a message_id " +
        "when the content matters. \"Today\" is newer_than:1d; dates use after:YYYY/MM/DD."

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val max = (args.int("max_results") ?: 5).coerceIn(1, GmailClient.MAX_SEARCH)
        val found = when (val r = client.search(args.string("query")!!, max)) { is Outcome.Success -> r.value; is Outcome.Failure -> return emailError(r.error) }
        if (found.isEmpty()) return ToolResult.Ok("No emails match.")
        return ToolResult.Ok(found.mapIndexed { i, e ->
            "${i + 1}. ${day.format(e.date)} — ${e.from} — \"${e.subject}\"${if (e.unread) " (unread)" else ""} — ${e.snippet.take(120)} {message_id: ${session.handleFor(e)}}"
        }.joinToString("\n"))
    }
}

/** `read_email` (C19 §2): one email in full, as plain text. */
class ReadEmailTool(private val session: GmailSession, private val client: GmailClient) : Tool {
    override val spec = ToolSpec(
        "read_email",
        "Read one email from search_email in full",
        listOf(ToolParam("message_id", ParamType.String, "The message_id from search_email, e.g. m2", required = true, aliases = listOf("id", "message"))),
    )
    override val statusLabel = "Reading the email…"
    override val displayName = "email"
    override val integrationId = GoogleAuth.ID
    override val maxResultChars = MailText.MAX_BODY + 600

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val known = session.email(args.string("message_id")) ?: return ToolResult.Error(NEEDS_SEARCH)
        val m = when (val r = client.read(known.id)) { is Outcome.Success -> r.value; is Outcome.Failure -> return emailError(r.error) }
        session.handleFor(m.summary)
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

/** What draft_email and send_email share: who, what, and the original when it's a reply. */
private abstract class WriteEmailTool(protected val session: GmailSession, protected val client: GmailClient) : Tool {
    protected fun params() = listOf(
        ToolParam("to", ParamType.String, "Email address (for a reply, leave it out to answer the sender)", aliases = listOf("recipient", "address")),
        ToolParam("subject", ParamType.String, "Subject (for a reply, leave it out to use \"Re: …\")"),
        ToolParam("body", ParamType.String, "The message text, in full", required = true, aliases = listOf("message", "text")),
        ToolParam("reply_to_message_id", ParamType.String, "To reply: the message_id from search_email", aliases = listOf("reply_to", "in_reply_to")),
    )
    override val displayName = "email"
    override val integrationId = GoogleAuth.ID

    /** The email to save or send, with a reply's recipient, subject and thread filled in from the original. */
    protected fun email(args: ToolArgs): OutgoingEmail {
        val replyHandle = args.string("reply_to_message_id")
        val original = replyHandle?.let { session.email(it) ?: throw ArgumentError("reply_to_message_id", NEEDS_SEARCH) }
        val to = args.string("to") ?: original?.let { replyAddress(it.from) } ?: throw ArgumentError("to", "missing to")
        if ('@' !in to) throw ArgumentError("to", "to must be an email address")
        val subject = args.string("subject") ?: original?.subject?.let { if (it.startsWith("Re:", ignoreCase = true)) it else "Re: $it" } ?: ""
        return OutgoingEmail(to, subject, args.string("body")!!, original?.messageIdHeader, original?.threadId)
    }

    protected fun details(e: OutgoingEmail) = listOf(
        "To" to e.to,
        "Subject" to e.subject.ifBlank { "(none)" },
        "Message" to e.body.let { if (it.length > PREVIEW) it.take(PREVIEW) + "…" else it },
    )

    companion object {
        /** The whole message is shown when it fits a card. */
        const val PREVIEW = 1_500

        /** "Sam Lee <sam@example.com>" → "sam@example.com". */
        fun replyAddress(from: String): String = Regex("<([^>]+)>").find(from)?.groupValues?.get(1) ?: from.trim()
    }
}

/** `draft_email` (C19 §2): saves a draft in Gmail, after the owner confirms (D11). */
private class DraftEmailTool(session: GmailSession, client: GmailClient) : WriteEmailTool(session, client) {
    override val spec = ToolSpec("draft_email", "Save an email as a draft in the user's Gmail, to send later from Gmail (the user confirms first)", params())
    override val statusLabel = "Saving the draft…"
    override val guidance = "To write an email, call draft_email, or send_email when the user clearly asks to send it. The app shows the " +
        "user the whole email to confirm, so don't ask in chat first. Write the full message yourself, signed as the user would."

    override fun confirmation(args: ToolArgs) = ConfirmationRequest("Save this draft?", details(email(args)), confirmLabel = "Save draft")

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult =
        when (val r = client.createDraft(email(args))) {
            is Outcome.Success -> ToolResult.Ok("Draft saved in Gmail — open Gmail to send it.")
            is Outcome.Failure -> emailError(r.error)
        }
}

/** `send_email` (D5): sends, only after the owner confirms a card with the whole email. */
private class SendEmailTool(session: GmailSession, client: GmailClient) : WriteEmailTool(session, client) {
    override val spec = ToolSpec("send_email", "Send an email from the user's Gmail (the user sees it and confirms first)", params())
    override val statusLabel = "Sending the email…"

    override fun confirmation(args: ToolArgs) = ConfirmationRequest("Send this email?", details(email(args)), confirmLabel = "Send")

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val e = email(args)
        return when (val r = client.send(e)) {
            is Outcome.Success -> ToolResult.Ok("Sent to ${e.to}.")
            is Outcome.Failure -> emailError(r.error)
        }
    }
}

/** The Gmail tools (C19), sharing one session. */
fun gmailTools(session: GmailSession, client: GmailClient): List<Tool> = listOf(
    SearchEmailTool(session, client), ReadEmailTool(session, client), DraftEmailTool(session, client), SendEmailTool(session, client),
)
