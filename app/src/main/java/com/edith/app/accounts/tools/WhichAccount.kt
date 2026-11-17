package com.edith.app.accounts.tools

import com.edith.app.accounts.Account
import com.edith.app.accounts.AccountDirectory
import com.edith.app.accounts.AccountEntry
import com.edith.app.accounts.AccountState
import com.edith.app.accounts.sources.AccountErrors
import com.edith.app.accounts.sources.CalendarInfo
import com.edith.app.accounts.sources.Reads
import com.edith.app.accounts.tasks.TaskList
import com.edith.app.accounts.tasks.TasksAccess
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
        // A read-only calendar (a work calendar through Jarvis, C38) is never a place for a new event.
        val all = dir.entries().filter { it.capabilities.calendar && it.calendar?.readOnly != true }
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

    /** Where a new to-do goes (C35 §3): an account and one of its lists, or why Edith must stop (or ask). */
    sealed interface TaskChoice {
        data class Use(val entry: AccountEntry, val list: TaskList) : TaskChoice
        data class Stop(val result: ToolResult) : TaskChoice
    }

    /**
     * C22's rule for a new to-do, extended with lists (C35 §3):
     * 1. `account` named → that account (unknown → "no account called…").
     * 2. `list` named and only one account has a list of that name → that account.
     * 3. Only one account can keep to-dos now → that one.
     * 4. Otherwise: ask the owner, with no card and no request.
     *
     * Within the account, no `list` → its first list (Google's default, usually "My Tasks"); an unknown list → the
     * lists there are, and no request.
     */
    suspend fun forTasks(dir: AccountDirectory, accountArg: String?, listArg: String?): TaskChoice {
        val all = dir.entries().filter { it.capabilities.tasks && it.tasks != null && it.tasks.readOnly.not() }
        val able = all.filter { it.state == AccountState.Connected && it.tasksAccess != TasksAccess.NeedsPermission }
        if (accountArg != null) {
            val entry = when (val c = named(dir, all, accountArg)) { is Choice.Use -> c.entry; is Choice.Stop -> return TaskChoice.Stop(c.result) }
            return inAccount(entry, listArg)
        }
        if (listArg != null) {
            val found = able.mapNotNull { e -> (requireNotNull(e.tasks).lists() as? Outcome.Success)?.value?.let { e to it } }
            val matches = found.mapNotNull { (e, lists) -> matchList(lists, listArg)?.let { e to it } }
            return when {
                matches.size == 1 -> TaskChoice.Use(matches[0].first, matches[0].second)
                matches.isEmpty() -> TaskChoice.Stop(ToolResult.Error(
                    "There's no list called '$listArg'. The lists are: " +
                        found.joinToString("; ") { (e, lists) -> (if (found.size > 1) "${e.account.label}: " else "") + lists.joinToString { it.title } } + ".",
                ))
                else -> TaskChoice.Stop(ask(matches.map { it.first }).result)
            }
        }
        return when {
            able.size == 1 -> inAccount(able[0], null)
            able.isEmpty() -> TaskChoice.Stop(ToolResult.Error(
                "No account can keep to-dos right now. " + all.joinToString(" ") { e ->
                    if (e.state == AccountState.Connected) "${e.account.display}: ${AccountErrors.TASKS_NEED_PERMISSION}." else notUsable(e).text
                }.ifBlank { "Tell the user to add a Google account in Settings → Accounts." },
            ))
            else -> TaskChoice.Stop(ask(able).result)
        }
    }

    /** The account's list called [listArg] (its first list when null), or why not. */
    private suspend fun inAccount(entry: AccountEntry, listArg: String?): TaskChoice {
        val lists = when (val r = requireNotNull(entry.tasks).lists()) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return TaskChoice.Stop(accountError(entry.account, r.error))
        }
        if (lists.isEmpty()) return TaskChoice.Stop(ToolResult.Error("${entry.account.display} has no to-do lists."))
        if (listArg == null) return TaskChoice.Use(entry, lists.first())
        val list = matchList(lists, listArg)
            ?: return TaskChoice.Stop(ToolResult.Error("There's no list called '$listArg'. The lists are: ${lists.joinToString { it.title }}."))
        return TaskChoice.Use(entry, list)
    }

    /** A list by name: exactly (ignoring case), else the one list whose name contains it (Jarvis's matchLists). */
    fun matchList(lists: List<TaskList>, name: String): TaskList? {
        val wanted = name.trim().lowercase()
        if (wanted.isEmpty()) return null
        lists.firstOrNull { it.title.lowercase() == wanted }?.let { return it }
        return lists.filter { it.title.lowercase().contains(wanted) }.singleOrNull()
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
    private fun ask(choices: List<AccountEntry>): Choice.Stop {
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
