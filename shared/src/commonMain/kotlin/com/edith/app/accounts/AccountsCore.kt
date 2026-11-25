package com.edith.app.accounts

import com.edith.app.accounts.sources.CalendarInfo
import com.edith.app.accounts.sources.DefaultCalendarSource
import com.edith.app.accounts.tasks.TasksAccess
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
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

/**
 * Every connected email-and-calendar account (C22, D17), and the integration their tools belong to: the calendar and email tools are offered
 * while at least one account is connected, and the prompt names every account and its state so Edith can say when one needs signing in (L6).
 *
 * K2.6a: this is everything about the list that does not depend on a provider; what does (making a provider's connection, and each way of
 * adding an account) is left to the platform's subclass: Android's `Accounts` has Google, Microsoft, iCloud and Jarvis. A subclass adds an
 * account with [upsert] and gets the provider's [connectionFor] on the way.
 */
abstract class AccountsCore(
    private val store: AccountStore,
    private val scope: CoroutineScope,
) : Integration, AccountDirectory {
    override val id = ACCOUNTS_INTEGRATION_ID

    private val list = MutableStateFlow<List<Account>>(emptyList())
    private val connectionsFlow = MutableStateFlow<Map<String, AccountConnection>>(emptyMap())
    private val editLock = Mutex()

    private val _loaded = MutableStateFlow(false)
    /** False until the saved accounts are read at startup (the Calendar tab waits for it). */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** Why the last sign-in didn't work, for Settings → Accounts; null when there's nothing to say. */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** The account whose consent screen is showing (one at a time). */
    @kotlin.concurrent.Volatile private var consentFor: String? = null

    /** What a provider makes of a saved account: its live connection, or null when this build can't use that kind of account. */
    protected abstract fun connectionFor(account: Account, accountOf: () -> Account): AccountConnection?

    /** The saved accounts, as last read or changed. */
    protected val savedAccounts: List<Account> get() = list.value

    /** Sets (or clears, with null) the note shown under the account list. */
    protected fun say(text: String?) { _message.value = text }

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
        val c = connectionsFlow.value[a.id]
        // The owner's default calendar, when chosen, replaces the provider's (Settings → Accounts).
        val calendar = c?.calendar?.let { src -> a.defaultCalendar?.let { DefaultCalendarSource(src, it) } ?: src }
        AccountEntry(
            a, c?.state?.value ?: AccountState.Error(UNSUPPORTED), c?.capabilities ?: Capabilities(false, false), calendar, c?.mail,
            c?.tasks, c?.tasksAccess?.value ?: TasksAccess.Unknown,
        )
    }

    /**
     * C35 §2: each account's to-do access by id, for Settings ("To-dos: Grant access") and the To-do view's banner.
     * Only accounts that have to-dos are in it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val tasksAccess: StateFlow<Map<String, TasksAccess>> = connectionsFlow.flatMapLatest { m ->
        val flows = m.mapNotNull { (id, c) -> c.tasksAccess?.map { id to it } }
        if (flows.isEmpty()) flowOf(emptyMap()) else combine(flows) { it.toMap() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** **To-dos: Grant access** for one account (C35 §2). */
    suspend fun grantTasks(id: String, activity: PlatformActivity, show: (PlatformPendingIntent) -> Unit) {
        _message.value = null
        consentFor = id
        connectionsFlow.value[id]?.grantTasks(activity, show)
    }

    /** One account's writable calendars, with the current default marked, for Settings → Accounts. */
    suspend fun writableCalendars(id: String): List<CalendarInfo> =
        (entries().firstOrNull { it.account.id == id }?.calendar?.calendars() as? Outcome.Success)?.value?.filter { it.canWrite }.orEmpty()

    /** Settings → Accounts → Default calendar: where new events go when no calendar is named (null: the provider's). */
    suspend fun setDefaultCalendar(id: String, calendarId: String?) = editLock.withLock {
        store.update { current -> current.map { if (it.id == id) it.copy(defaultCalendar = calendarId) else it } }
        list.value = store.current()
        Log.i(LogTag.Accounts, "Accounts: $id default calendar ${if (calendarId == null) "reset" else "chosen"}")
    }

    /** One account's live connection (the debug hook checks each Google account's token with it). */
    fun connection(id: String): AccountConnection? = connectionsFlow.value[id]

    /** The live connection of the first saved account of [provider], if there is one. */
    protected fun connectionOf(provider: Provider): AccountConnection? =
        list.value.firstOrNull { it.provider == provider }?.let { connectionsFlow.value[it.id] }

    /** At startup (after [AccountMigration]): connects every saved account and checks each quietly, in parallel. */
    suspend fun load() {
        val saved = store.current()
        saved.forEach(::connect)
        list.value = saved
        _loaded.value = true
        Log.i(LogTag.Accounts, "Accounts: ${saved.size} (${saved.joinToString { "${it.id}/${it.provider}" }})")
        saved.forEach { a -> scope.launch { connectionsFlow.value[a.id]?.start() } }
    }

    /** Grant access / Sign in again / Try again for one account. */
    suspend fun grant(id: String, activity: PlatformActivity, show: (PlatformPendingIntent) -> Unit) {
        _message.value = null
        consentFor = id
        connectionsFlow.value[id]?.grant(activity, show)
    }

    /** Google's consent screen returned, for the account that asked. */
    suspend fun onConsentResult(data: PlatformIntent?) {
        consentFor?.let { connectionsFlow.value[it]?.onConsentResult(data) }
        consentFor = null
    }

    /** Whether [rename] took the label, and if not, why. */
    enum class RenameOutcome { Done, Blank, Taken }

    /** Labels are unique, ignoring case, and can't be another account's address (so [byName] stays unambiguous). */
    protected suspend fun renameAccount(id: String, label: String): RenameOutcome = editLock.withLock {
        val wanted = label.trim()
        if (wanted.isEmpty()) return@withLock RenameOutcome.Blank
        val others = list.value.filter { it.id != id }
        if (others.any { it.label.equals(wanted, ignoreCase = true) || it.address.equals(wanted, ignoreCase = true) }) return@withLock RenameOutcome.Taken
        store.update { current -> current.map { if (it.id == id) it.copy(label = wanted) else it } }
        list.value = store.current()
        RenameOutcome.Done
    }

    /** Disconnect: revokes this account's access only and removes it from the list (C22 §2). */
    suspend fun disconnect(id: String) = editLock.withLock {
        connectionsFlow.value[id]?.disconnect()
        store.update { current -> current.filterNot { it.id == id } }
        connectionsFlow.value = connectionsFlow.value - id
        list.value = store.current()
        Log.i(LogTag.Accounts, "Accounts: disconnected $id")
    }

    /** Starts the account's quiet check (after it was added or signed in again). */
    protected suspend fun startConnection(id: String) {
        connectionsFlow.value[id]?.start()
    }

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
            // C35 §2: say when to-dos need permission, so Edith tells the owner instead of guessing (L6).
            AccountState.Connected -> if (e.capabilities.tasks && e.tasksAccess == TasksAccess.NeedsPermission) {
                e.capabilities.copy(tasks = false).text + "; to-dos need permission"
            } else e.capabilities.text
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
        val connection = connectionFor(account, accountOf) ?: return
        connectionsFlow.value = connectionsFlow.value + (id to connection)
    }

    /**
     * Adds the account a sign-in names (provider, address), or, if it's already here, signs it in again (C22 §2). Its connection is made on the
     * way, and the quiet check is left to the caller (after it has kept the account's secrets).
     */
    protected suspend fun upsert(provider: Provider, address: String, signIn: String?): Account = editLock.withLock {
        var result: Account? = null
        store.update { current ->
            val existing = current.firstOrNull { it.provider == provider && it.address.equals(address, ignoreCase = true) }
            if (existing != null) {
                val updated = existing.copy(signIn = signIn ?: existing.signIn)
                result = updated
                current.map { if (it.id == existing.id) updated else it }
            } else {
                val added = Account(Account.newId(current.map { it.id }), provider, address, AccountNames.uniqueLabel(address, current), signIn)
                result = added
                current + added
            }
        }
        val account = requireNotNull(result) { "the account list wasn't updated" }
        list.value = store.current()
        if (!connectionsFlow.value.containsKey(account.id)) {
            connect(account)
            Log.i(LogTag.Accounts, "Accounts: added ${account.id} (${account.provider})")
        } else {
            Log.i(LogTag.Accounts, "Accounts: signed ${account.id} in again")
        }
        account
    }

    /** Saves the iCloud login name that the check found (the account's address may differ from it). */
    protected suspend fun setLogin(id: String, login: String) = editLock.withLock {
        store.update { current -> current.map { if (it.id == id) it.copy(login = login) else it } }
        list.value = store.current()
    }

    /** Whether no saved account is of [provider] at [address] yet (a first pairing is called "Work" when nothing else is). */
    protected fun isNew(provider: Provider, address: String): Boolean =
        list.value.none { it.provider == provider && it.address.equals(address, ignoreCase = true) }

    companion object {
        const val UNSUPPORTED = "This version of Edith can't use this kind of account."
    }
}
