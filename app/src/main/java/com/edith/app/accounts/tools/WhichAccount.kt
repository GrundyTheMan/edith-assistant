package com.edith.app.accounts.tools

import com.edith.app.accounts.Account
import com.edith.app.accounts.AccountDirectory
import com.edith.app.accounts.AccountEntry
import com.edith.app.accounts.AccountState
import com.edith.app.accounts.sources.AccountErrors
import com.edith.app.accounts.sources.CalendarInfo
import com.edith.app.accounts.sources.Reads
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.tools.framework.ParamType
import com.edith.app.tools.framework.ToolParam
import com.edith.app.tools.framework.ToolResult

/** Every calendar and email tool's optional `account` parameter (C22 §4). */
internal val ACCOUNT_PARAM = ToolParam(
    "account", ParamType.String, "The account's name or address, only when the user named one",
    aliases = listOf("account_name", "mailbox"),
)

/**
 * Which account a **new** event or email goes to (C22 §4, D17), decided here in code and never left to the model
 * (trap 2):
 * 1. `account` given and it matches one account → that one; none or several → say so and list the real ones.
 * 2. `calendar` given and only one account has a calendar of that name → that account.
 * 3. Only one connected account can do it → that one.
 * 4. Otherwise: tell the model to ask the owner, then call again with `account`. No card, no request.
 */
object WhichAccount {
    sealed interface Choice {
        /** Use [entry]; [calendar] is the named calendar (null: the account's default). */
        data class Use(val entry: AccountEntry, val calendar: CalendarInfo? = null) : Choice
        /** Don't go ahead: [result] tells the model why (or to ask the owner). */
        data class Stop(val result: ToolResult) : Choice
    }

    suspend fun forCalendar(dir: AccountDirectory, accountArg: String?, calendarArg: String?): Choice {
        val all = dir.entries().filter { it.capabilities.calendar }
        val able = all.filter { it.state == AccountState.Connected && it.calendar != null }
        if (accountArg != null) {
            val entry = when (val c = named(dir, all, accountArg)) { is Choice.Use -> c.entry; is Choice.Stop -> return c }
            if (calendarArg == null) return Choice.Use(entry)
            val calendars = when (val r = requireNotNull(entry.calendar).calendars()) {
                is Outcome.Success -> r.value
                is Outcome.Failure -> return Choice.Stop(accountError(entry.account, r.error))
            }
            val cal = calendars.firstOrNull { it.name.equals(calendarArg.trim(), ignoreCase = true) }
                ?: return Choice.Stop(ToolResult.Error("${entry.account.display} has no calendar called '$calendarArg'. Its calendars are: ${calendars.joinToString { it.name }}."))
            return Choice.Use(entry, cal)
        }
        if (calendarArg != null) {
            val found = able.mapNotNull { e ->
                val cals = (requireNotNull(e.calendar).calendars() as? Outcome.Success)?.value ?: return@mapNotNull null
                e to cals
            }
            val matches = found.mapNotNull { (e, cals) -> cals.firstOrNull { it.name.equals(calendarArg.trim(), ignoreCase = true) }?.let { e to it } }
            when {
                matches.size == 1 -> return Choice.Use(matches[0].first, matches[0].second)
                matches.isEmpty() -> return Choice.Stop(ToolResult.Error(
                    "There's no calendar called '$calendarArg'. The calendars are: " +
                        found.joinToString("; ") { (e, cals) -> "${e.account.label}: ${cals.joinToString { it.name }}" } + ".",
                ))
                else -> return ask(matches.map { it.first })
            }
        }
        return onlyOneOrAsk(all, able, "add events")
    }

    suspend fun forMail(dir: AccountDirectory, accountArg: String?): Choice {
        val all = dir.entries().filter { it.capabilities.mail }
        val able = all.filter { it.state == AccountState.Connected && it.mail != null }
        if (accountArg != null) return named(dir, all, accountArg)
        return onlyOneOrAsk(all, able, "send email")
    }

    /** Rule 1: the account the owner named, if it's exactly one and it can be used now. */
    private fun named(dir: AccountDirectory, all: List<AccountEntry>, text: String): Choice {
        val matches = dir.byName(text).filter { a -> all.any { it.account.id == a.id } }
        if (matches.size != 1) {
            return Choice.Stop(ToolResult.Error("There's no account called '$text'. The accounts are: ${all.joinToString { it.account.display }}."))
        }
        val entry = all.first { it.account.id == matches[0].id }
        if (entry.state != AccountState.Connected) return Choice.Stop(notUsable(entry))
        return Choice.Use(entry)
    }

    private fun onlyOneOrAsk(all: List<AccountEntry>, able: List<AccountEntry>, what: String): Choice = when {
        able.size == 1 -> Choice.Use(able[0])
        able.isEmpty() -> Choice.Stop(ToolResult.Error(
            "No account can $what right now. " + all.joinToString(" ") { notUsable(it).text }.ifBlank { "Tell the user to add one in Settings → Accounts." },
        ))
        else -> ask(able)
    }

    /** Rule 4 (L27: the instruction is in the result). */
    private fun ask(choices: List<AccountEntry>): Choice {
        val names = choices.map { it.account.display }
        val list = if (names.size <= 2) names.joinToString(" or ") else names.dropLast(1).joinToString(", ") + " or " + names.last()
        return Choice.Stop(ToolResult.Ok("Ask the owner which account to use: $list. Then call this tool again with `account`."))
    }

    private fun notUsable(e: AccountEntry): ToolResult = ToolResult.Error(
        when (val s = e.state) {
            is AccountState.NeedsSignIn -> "${e.account.display} can't be used: it ${s.reason.substringBefore(" (")} — tell the user to open Settings → Accounts."
            is AccountState.Error -> "${e.account.display} can't be used right now: ${s.message}"
            AccountState.Checking -> "${e.account.display} is still connecting; try again in a moment."
            AccountState.Connected -> "${e.account.display} can't do that."
        },
    )
}

/** A failure in one account, said so the model tells the owner what to do (L6). */
internal fun accountError(account: Account, e: AppError): ToolResult = ToolResult.Error(
    when {
        e is AppError.GoogleAuth && (e.detail == AccountErrors.NEEDS_PERMISSION || e.detail == Reads.NEEDS_SIGN_IN) ->
            "${account.display} needs signing in again or your permission. Tell the user to open Settings → Accounts and tap it."
        e == AppError.GoogleNotConnected -> "${account.display} isn't connected. Tell the user to check Settings → Accounts."
        e is AppError.GoogleAuth -> "${account.display}: ${e.detail}"
        e is AppError.ToolFailed -> e.detail
        else -> e.userMessage
    },
)

/** The entry for an item's own account (C22: replies, changes and deletes stay there), or why it can't be used. */
internal fun ownAccount(dir: AccountDirectory, accountId: String): Pair<AccountEntry?, ToolResult?> {
    val entry = dir.entries().firstOrNull { it.account.id == accountId }
        ?: return null to ToolResult.Error("That item's account isn't connected any more.")
    if (entry.state != AccountState.Connected) {
        return null to ToolResult.Error("${entry.account.display} can't be used right now (${stateWords(entry.state)}). Tell the user to open Settings → Accounts.")
    }
    return entry to null
}

internal fun stateWords(s: AccountState): String = when (s) {
    AccountState.Connected -> "connected"
    AccountState.Checking -> "still connecting"
    is AccountState.NeedsSignIn -> s.reason.substringBefore(" (")
    is AccountState.Error -> s.message
}
