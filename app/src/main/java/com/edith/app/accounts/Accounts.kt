package com.edith.app.accounts

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import com.edith.app.accounts.sources.CalendarInfo
import com.edith.app.accounts.sources.CalendarSource
import com.edith.app.accounts.sources.DefaultCalendarSource
import com.edith.app.accounts.sources.MailSource
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.google.GoogleAccounts
import com.edith.app.google.GoogleSignIn
import com.edith.app.google.browser.BrowserOAuth
import com.edith.app.icloud.ICloudAccounts
import com.edith.app.microsoft.MicrosoftAccounts
import com.edith.app.microsoft.MicrosoftOAuth
import com.edith.app.tools.framework.Integration
import com.edith.app.tools.framework.IntegrationStatus
import com.edith.app.tools.framework.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** One account as it is right now: its state and what it can read and write. */
data class AccountEntry(
    val account: Account,
    val state: AccountState,
    val capabilities: Capabilities,
    val calendar: CalendarSource?,
    val mail: MailSource?,
)

/** What the calendar and email tools need from the accounts (a fake in tests). */
interface AccountDirectory {
    /** Every account, in the order the owner added them, with its state now. */
    fun entries(): List<AccountEntry>
    /** The accounts [text] names: a label or address, or a prefix of one (C22 §1). None, one, or several. */
    fun byName(text: String): List<Account> = Accounts.match(entries().map { it.account }, text)
}

/** A live account: its provider's connection (C22 §1). */
interface AccountConnection {
    val state: StateFlow<AccountState>
    val capabilities: Capabilities
    val calendar: CalendarSource?
    val mail: MailSource?
    /** At startup and after signing in: checks quietly whether access is still granted. */
    suspend fun start()
    /** Grant access / Sign in again / Try again. [show] shows a consent screen when the provider has one. */
    suspend fun grant(activity: Activity, show: (PendingIntent) -> Unit)
    suspend fun onConsentResult(data: Intent?)
    /** Revokes Edith's access at the provider (a failure is only logged) and deletes the account's secrets. */
    suspend fun disconnect()
}

/**
 * Every connected email-and-calendar account (C22, D17), and the integration their tools belong to: the calendar and
 * email tools are offered while at least one account is connected, and the prompt names every account and its state
 * so Edith can say when one needs signing in (L6).
 */
class Accounts(
    private val store: AccountStore,
    val google: GoogleAccounts,
    private val scope: CoroutineScope,
    /** C23: Microsoft accounts (null in tests that don't need it). */
    val microsoft: MicrosoftAccounts? = null,
    /** C24: iCloud accounts (null in tests that don't need it). */
    val icloud: ICloudAccounts? = null,
) : Integration, AccountDirectory {
    override val id = ID

    private val list = MutableStateFlow<List<Account>>(emptyList())
    private val connections = ConcurrentHashMap<String, AccountConnection>()
    private val connectionsFlow = MutableStateFlow<Map<String, AccountConnection>>(emptyMap())
    private val editLock = Mutex()

    private val _loaded = MutableStateFlow(false)
    /** False until the saved accounts are read at startup (the Calendar tab waits for it). */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** Why the last sign-in didn't work, for Settings → Accounts; null when there's nothing to say. */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** The account whose consent screen is showing (one at a time). */
    @Volatile private var consentFor: String? = null

    /** Every account and its state, for Settings and the Calendar tab. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val accounts: StateFlow<List<Pair<Account, AccountState>>> = combine(
        list,
        connectionsFlow.flatMapLatest { m ->
            if (m.isEmpty()) flowOf(emptyMap()) else combine(m.map { (id, c) -> c.state.map { id to it } }) { it.toMap() }
        },
    ) { accounts, states -> accounts.map { it to (states[it.id] ?: AccountState.Checking) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val status: StateFlow<IntegrationStatus> = accounts
        .map { l -> if (l.any { it.second == AccountState.Connected }) IntegrationStatus.Connected else IntegrationStatus.NotConnected }
        .stateIn(scope, SharingStarted.Eagerly, IntegrationStatus.NotConnected)

    override fun entries(): List<AccountEntry> = list.value.map { a ->
        val c = connections[a.id]
        // The owner's default calendar, when chosen, replaces the provider's (Settings → Accounts).
        val calendar = c?.calendar?.let { src -> a.defaultCalendar?.let { DefaultCalendarSource(src, it) } ?: src }
        AccountEntry(a, c?.state?.value ?: AccountState.Error(UNSUPPORTED), c?.capabilities ?: Capabilities(false, false), calendar, c?.mail)
    }

    /** One account's writable calendars, with the current default marked, for Settings → Accounts. */
    suspend fun writableCalendars(id: String): List<CalendarInfo> =
        (entries().firstOrNull { it.account.id == id }?.calendar?.calendars() as? com.edith.app.core.error.Outcome.Success)
            ?.value?.filter { it.canWrite }.orEmpty()

    /** Settings → Accounts → Default calendar: where new events go when no calendar is named (null: the provider's). */
    suspend fun setDefaultCalendar(id: String, calendarId: String?) = editLock.withLock {
        store.update { current -> current.map { if (it.id == id) it.copy(defaultCalendar = calendarId) else it } }
        list.value = store.current()
        Log.i(LogTag.Accounts, "Accounts: $id default calendar ${if (calendarId == null) "reset" else "chosen"}")
    }

    /** One account's live connection (the debug hook checks each Google account's token with it). */
    fun connection(id: String): AccountConnection? = connections[id]

    /** At startup (after [AccountMigration]): connects every saved account and checks each quietly, in parallel. */
    suspend fun load() {
        val saved = store.current()
        saved.forEach(::connect)
        list.value = saved
        _loaded.value = true
        Log.i(LogTag.Accounts, "Accounts: ${saved.size} (${saved.joinToString { "${it.id}/${it.provider}" }})")
        saved.forEach { a -> scope.launch { connections[a.id]?.start() } }
    }

    /**
     * At launch: a browser sign-in an earlier run left waiting (C17b note 1), for whichever provider it was (C23).
     */
    fun resumePending() {
        scope.launch {
            val oauthFor: (String) -> BrowserOAuth? = { p ->
                when (p) {
                    MicrosoftOAuth.PROVIDER -> microsoft?.oauth
                    else -> google.browserOAuth.takeIf { p == "google" }
                }
            }
            val r = google.signInFlow.resumePending(oauthFor) ?: return@launch
            when (r.provider) {
                MicrosoftOAuth.PROVIDER -> microsoft?.let { onMicrosoftSignIn(it.result(r.result)) }
                else -> onGoogleSignIn(google.browserResult(r.result))
            }
        }
    }

    /** Add account → Google. [useBrowser]: **Use a browser instead** (C17b). */
    suspend fun addGoogle(activity: Activity, useBrowser: Boolean = false) {
        _message.value = null
        onGoogleSignIn(google.signInNew(activity, useBrowser))
    }

    /**
     * Add account → iCloud (C24 §1): checks the address and app-specific password, then adds the account (or signs
     * it in again with the new password). Returns why not, or a note when only mail works; null when all is well.
     */
    suspend fun addICloud(address: String, password: String): String? {
        _message.value = null
        val ic = icloud ?: return NOT_SET_UP_ICLOUD
        val check = ic.check(address, password)
        if (check is ICloudAccounts.Check.Failed) return check.message
        check as ICloudAccounts.Check.Ok
        val account = upsert(Provider.ICloud, address.trim(), null)
        editLock.withLock {
            store.update { current -> current.map { if (it.id == account.id) it.copy(login = check.login) else it } }
            list.value = store.current()
        }
        ic.keep(account.id, password)
        connections[account.id]?.start()
        return if (check.calendar) null else ICloudAccounts.CALENDAR_UNAVAILABLE
    }

    /** Add account → Microsoft (C23). */
    suspend fun addMicrosoft(activity: Activity) {
        _message.value = null
        val m = microsoft ?: return run { _message.value = MicrosoftAccounts.NOT_SET_UP }
        onMicrosoftSignIn(m.signInNew(activity))
    }

    /** Grant access / Sign in again / Try again for one account. */
    suspend fun grant(id: String, activity: Activity, show: (PendingIntent) -> Unit) {
        _message.value = null
        consentFor = id
        connections[id]?.grant(activity, show)
    }

    /** Google's consent screen returned, for the account that asked. */
    suspend fun onConsentResult(data: Intent?) {
        consentFor?.let { connections[it]?.onConsentResult(data) }
        consentFor = null
    }

    enum class RenameResult { Done, Blank, Taken }

    /** Labels are unique, ignoring case, and can't be another account's address (so [byName] stays unambiguous). */
    suspend fun rename(id: String, label: String): RenameResult = editLock.withLock {
        val wanted = label.trim()
        if (wanted.isEmpty()) return@withLock RenameResult.Blank
        val others = list.value.filter { it.id != id }
        if (others.any { it.label.equals(wanted, ignoreCase = true) || it.address.equals(wanted, ignoreCase = true) }) return@withLock RenameResult.Taken
        store.update { current -> current.map { if (it.id == id) it.copy(label = wanted) else it } }
        list.value = store.current()
        RenameResult.Done
    }

    /** Disconnect: revokes this account's access only and removes it from the list (C22 §2). */
    suspend fun disconnect(id: String) = editLock.withLock {
        connections[id]?.disconnect()
        store.update { current -> current.filterNot { it.id == id } }
        connections.remove(id)
        connectionsFlow.value = connections.toMap()
        list.value = store.current()
        Log.i(LogTag.Accounts, "Accounts: disconnected $id")
    }

    // --- C17b's browser sign-in, app-wide ------------------------------------------------------------------------

    val browserWaiting: StateFlow<Boolean> get() = google.waiting
    /** The provider being signed in to ("google", "microsoft"), for the waiting dialog. */
    val browserWaitingFor: StateFlow<String?> get() = google.signInFlow.waitingFor
    fun pasteBrowserAddress(address: String) = google.paste(address)
    fun cancelBrowserSignIn() = google.cancel()

    // --- The prompt -----------------------------------------------------------------------------------------------

    override val notConnectedNote: String
        get() {
            val all = entries()
            if (all.isEmpty()) {
                return "No email or calendar account is connected. If asked about the calendar or email, say the owner can add one in Settings → Accounts."
            }
            return "${accountsLine(all)} None can be used right now: if asked about the calendar or email, say so and point the owner to Settings → Accounts."
        }

    override fun connectedNote(tools: List<Tool>): String = accountsLine(entries())

    /** "Accounts: personal — a@gmail.com (Google: calendar and email); work — sam@contoso.com (Google: needs signing in again)." */
    private fun accountsLine(all: List<AccountEntry>): String = "Accounts: " + all.joinToString("; ") { e ->
        val who = if (e.account.label.equals(e.account.address, ignoreCase = true)) e.account.address else "${e.account.label} — ${e.account.address}"
        val what = when (val s = e.state) {
            AccountState.Connected -> e.capabilities.text
            AccountState.Checking -> "checking the connection"
            is AccountState.NeedsSignIn -> s.reason.substringBefore(" (")
            is AccountState.Error -> "can't be reached right now"
        }
        "$who (${e.account.provider.displayName}: $what)"
    } + "."

    // --- Inside ---------------------------------------------------------------------------------------------------

    private fun connect(account: Account) {
        val id = account.id
        val accountOf = { list.value.firstOrNull { it.id == id } ?: account }
        val connection = when (account.provider) {
            Provider.Google -> google.connect(accountOf) { activity -> onGoogleSignIn(google.signInAgain(activity, accountOf())) }
            Provider.Microsoft -> microsoft?.connect(accountOf) { activity -> microsoft.signInAgain(activity, accountOf()).let { onMicrosoftSignIn(it) } }
            Provider.ICloud -> icloud?.connect(accountOf)
        } ?: return
        connections[id] = connection
        connectionsFlow.value = connections.toMap()
    }

    /** A Google sign-in finished: the account it names is added, or, if it's already here, signed in again (C22 §2). */
    private suspend fun onGoogleSignIn(r: GoogleSignIn.Result) {
        when (r) {
            is GoogleSignIn.Result.SignedIn -> {
                val mode = if (r.grant != null) Account.SIGN_IN_BROWSER else Account.SIGN_IN_PLAY
                val account = upsert(Provider.Google, r.email, mode)
                r.grant?.let { google.keep(account.id, it) }
                connections[account.id]?.start()
            }
            GoogleSignIn.Result.Cancelled -> Unit
            is GoogleSignIn.Result.Failed -> _message.value = r.message
        }
    }

    /** A Microsoft sign-in finished: the account it names is added, or signed in again (C23). */
    private suspend fun onMicrosoftSignIn(r: MicrosoftAccounts.Result) {
        when (r) {
            is MicrosoftAccounts.Result.SignedIn -> {
                val account = upsert(Provider.Microsoft, r.address, null)
                microsoft?.keep(account.id, r.grant)
                connections[account.id]?.start()
            }
            MicrosoftAccounts.Result.Cancelled -> Unit
            is MicrosoftAccounts.Result.Failed -> _message.value = r.message
        }
    }

    private suspend fun upsert(provider: Provider, address: String, signIn: String?): Account = editLock.withLock {
        var result: Account? = null
        store.update { current ->
            val existing = current.firstOrNull { it.provider == provider && it.address.equals(address, ignoreCase = true) }
            if (existing != null) {
                val updated = existing.copy(signIn = signIn ?: existing.signIn)
                result = updated
                current.map { if (it.id == existing.id) updated else it }
            } else {
                val added = Account(Account.newId(current.map { it.id }), provider, address, uniqueLabel(address, current), signIn)
                result = added
                current + added
            }
        }
        val account = requireNotNull(result) { "the account list wasn't updated" }
        list.value = store.current()
        if (!connections.containsKey(account.id)) {
            connect(account)
            Log.i(LogTag.Accounts, "Accounts: added ${account.id} (${account.provider})")
        } else {
            Log.i(LogTag.Accounts, "Accounts: signed ${account.id} in again")
        }
        account
    }

    companion object {
        const val ID = "accounts"
        const val NOT_SET_UP_ICLOUD = "iCloud isn't available in this build."
        const val UNSUPPORTED = "This version of Edith can't use this kind of account."

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
            val exact = accounts.filter { it.label.lowercase() == t || it.address.lowercase() == t }
            if (exact.isNotEmpty()) return exact
            return accounts.filter { it.label.lowercase().startsWith(t) || it.address.lowercase().startsWith(t) }
        }
    }
}
