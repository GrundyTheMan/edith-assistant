package com.edith.app.accounts

import com.edith.app.accounts.sources.CalendarSource
import com.edith.app.accounts.sources.MailSource
import com.edith.app.accounts.tasks.TaskSource
import com.edith.app.accounts.tasks.TasksAccess

/** The id of the accounts integration (the calendar, email and to-do tools belong to it). */
const val ACCOUNTS_INTEGRATION_ID = "accounts"

/** One account as it is right now: its state and what it can read and write. */
data class AccountEntry(
    val account: Account,
    val state: AccountState,
    val capabilities: Capabilities,
    val calendar: CalendarSource?,
    val mail: MailSource?,
    /** C35: the account's to-dos (Google accounts), and whether Edith may use them now. */
    val tasks: TaskSource? = null,
    val tasksAccess: TasksAccess = TasksAccess.Unknown,
)

/** What the calendar and email tools need from the accounts (a fake in tests). */
interface AccountDirectory {
    /** Every account, in the order the owner added them, with its state now. */
    fun entries(): List<AccountEntry>
    /** The accounts [text] names: a label or address, or a prefix of one (C22 §1). None, one, or several. */
    fun byName(text: String): List<Account> = AccountNames.match(entries().map { it.account }, text)
}

/** How an owner or the model names an account (C22 §1). */
object AccountNames {
    /** A label no other account has (ignoring case): the address, or "address (2)" in the odd case it's taken. */
    fun uniqueLabel(wanted: String, others: List<Account>): String {
        fun taken(l: String) = others.any { it.label.equals(l, ignoreCase = true) }
        if (!taken(wanted)) return wanted
        var n = 2
        while (taken("$wanted ($n)")) n++
        return "$wanted ($n)"
    }

    /**
     * The accounts [text] names (C22 §1, trap 3): an exact label or address, ignoring case; otherwise every account
     * whose label or address starts with it. Nothing fuzzier.
     */
    fun match(accounts: List<Account>, text: String): List<Account> {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return emptyList()
        // The owner, or the model, may also say the account the way Edith lists it: "work (sam@contoso.com)", or with a
        // dash. Spelling out label and address is still an exact name, not a fuzzy one: only case and punctuation are ignored.
        val shown = plain(t)
        val exact = accounts.filter { it.label.lowercase() == t || it.address.lowercase() == t || plain(it.display) == shown }
        if (exact.isNotEmpty()) return exact
        return accounts.filter { it.label.lowercase().startsWith(t) || it.address.lowercase().startsWith(t) }
    }

    /** Letters and digits only, lower case: "Work (FAKE-PC)" and "work - fake-pc" are the same name. */
    private fun plain(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
