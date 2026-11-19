package com.edith.app.jarvis

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import com.edith.app.accounts.Account
import com.edith.app.accounts.AccountConnection
import com.edith.app.accounts.AccountSecrets
import com.edith.app.accounts.AccountState
import com.edith.app.accounts.Capabilities
import com.edith.app.accounts.sources.CalendarSource
import com.edith.app.accounts.sources.MailSource
import com.edith.app.accounts.sources.Reads
import com.edith.app.accounts.tasks.TaskSource
import com.edith.app.accounts.tasks.TasksAccess
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.secrets.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

/**
 * The owner's PC as an account provider (C38, D25): Jarvis serves the work Outlook mail, calendar and tasks that only
 * the PC can reach. Edith pairs once (a code Jarvis shows, a pinned certificate, a device token kept encrypted in
 * [SecretStore] as `account/<id>/jarvis_link`) and then only reads, plus saves drafts. No work-account credential ever
 * reaches the phone: the token opens Jarvis's feed and nothing else, and Jarvis can remove it at any time.
 */
class JarvisAccounts(
    private val secrets: SecretStore,
    private val http: Lazy<OkHttpClient>,
    private val dispatchers: AppDispatchers,
) {
    /** Pairing, step 1 (the owner then compares [fingerprint] with Jarvis's screen). */
    sealed interface Probe {
        data class Found(val base: String, val fingerprint: String, val groups: String) : Probe
        data class Failed(val message: String) : Probe
    }

    /** Pairing, step 2. */
    sealed interface Paired {
        data class Ok(val pcName: String, val link: JarvisLinkHandle) : Paired
        data class Failed(val message: String) : Paired
    }

    /** What pairing produced, kept opaque outside this package until [keep] stores it. */
    class JarvisLinkHandle internal constructor(internal val link: JarvisLink)

    private val pairingApi = JarvisApi({ null }, http, dispatchers)

    suspend fun probe(address: String): Probe {
        val (base, problem) = JarvisPairing.address(address)
        if (base == null) return Probe.Failed(problem ?: JarvisPairing.NOT_AN_ADDRESS)
        return when (val r = pairingApi.probe(base)) {
            is Outcome.Success -> Probe.Found(base.toString(), r.value, JarvisPairing.shortFingerprint(r.value))
            is Outcome.Failure -> Probe.Failed(messageOf(r.error))
        }
    }

    suspend fun pair(base: String, fingerprint: String, code: String, deviceName: String): Paired {
        val (url, problem) = JarvisPairing.address(base)
        if (url == null) return Paired.Failed(problem ?: JarvisPairing.NOT_AN_ADDRESS)
        if (code.filter { it.isLetterOrDigit() }.length < CODE_LENGTH) return Paired.Failed("The code has 10 letters and numbers, like ABCDE-FGHJK.")
        return when (val r = pairingApi.pair(url, fingerprint, code, deviceName)) {
            is Outcome.Success -> Paired.Ok(r.value.pcName.ifBlank { url.host }, JarvisLinkHandle(r.value))
            is Outcome.Failure -> Paired.Failed(messageOf(r.error))
        }
    }

    /** Keeps the paired PC's address, pin and token for [accountId]. */
    suspend fun keep(accountId: String, paired: Paired.Ok) =
        secrets.put(AccountSecrets.jarvisLink(accountId), feedJson.encodeToString(JarvisLink.serializer(), paired.link.link))

    /** The live connection for the account. */
    fun connect(accountOf: () -> Account): JarvisConnection = JarvisConnection(accountOf, secrets, http, dispatchers)

    private fun messageOf(e: AppError): String = when (e) {
        is AppError.ToolFailed -> e.detail
        is AppError.GoogleAuth -> "Jarvis didn't accept this phone. Pair again."
        else -> e.userMessage
    }

    companion object {
        const val CODE_LENGTH = 10
        const val NEEDS_PAIRING = "Jarvis removed this phone: pair it again"
    }
}

/** One paired PC: its work mail, calendar and to-dos, with a state from asking what Jarvis shares (C38 §2). */
class JarvisConnection internal constructor(
    private val accountOf: () -> Account,
    private val secrets: SecretStore,
    http: Lazy<OkHttpClient>,
    dispatchers: AppDispatchers,
) : AccountConnection {
    private val _state = MutableStateFlow<AccountState>(AccountState.Checking)
    override val state: StateFlow<AccountState> = _state.asStateFlow()

    @Volatile private var shared: Shared? = null
    @Volatile private var link: JarvisLink? = null

    private val id get() = accountOf().id

    private val api = JarvisApi({ link }, http, dispatchers, onUnauthorized = {
        Log.w(LogTag.Accounts, "Account $id: Jarvis no longer accepts this phone")
        _state.value = AccountState.NeedsSignIn(JarvisAccounts.NEEDS_PAIRING)
    })

    /** The routes of the feed, for the printer (C39). Its calls keep this connection's pin, token and removed-phone handling. */
    internal val feedApi: JarvisApi get() = api

    /** What Jarvis last said about the printer: null when it isn't shared (or this Jarvis doesn't know it). */
    internal val printerInfo: com.edith.app.printer.feed.SharedPrinter? get() = shared?.printer

    /** Whether this phone is paired at all (a saved link), without asking Jarvis. */
    internal val isPaired: Boolean get() = link != null

    /**
     * Asks Jarvis what it shares again (the printer page does this each time it opens). Success holds the printer entry, which is
     * null when the owner doesn't share it (or this Jarvis is too old to know it); Failure says why Jarvis couldn't be asked, so the
     * page can tell "not shared" from "the PC is off".
     */
    internal suspend fun refreshShared(): Outcome<com.edith.app.printer.feed.SharedPrinter?> {
        if (link == null) link = secrets.get(AccountSecrets.jarvisLink(id))?.let { runCatching { feedJson.decodeFromString(JarvisLink.serializer(), it) }.getOrNull() }
        return when (val r = api.get("shared", emptyMap(), Shared.serializer())) {
            is Outcome.Success -> { shared = r.value; Outcome.Success(r.value.printer) }
            is Outcome.Failure -> r
        }
    }

    override val capabilities: Capabilities get() = Capabilities(
        calendar = shared?.calendars?.isNotEmpty() == true,
        mail = shared?.mail?.isNotEmpty() == true,
        tasks = shared?.tasks?.isNotEmpty() == true,
    )

    private val mailSource = JarvisMailSource(accountOf(), api)
    private val calendarSource = JarvisCalendarSource(accountOf(), api)
    private val taskSource = JarvisTaskSource(accountOf(), api)

    override val mail: MailSource? get() = mailSource.takeIf { capabilities.mail }
    override val calendar: CalendarSource? get() = calendarSource.takeIf { capabilities.calendar }
    override val tasks: TaskSource? get() = taskSource.takeIf { capabilities.tasks }
    override val tasksAccess: StateFlow<TasksAccess> = MutableStateFlow(TasksAccess.Granted)

    /** At startup, after pairing and on **Try again**: reads what Jarvis shares (which also proves the PC is there). */
    override suspend fun start() {
        _state.value = AccountState.Checking
        // Always from the secret store: pairing again keeps a new token there, and a stale one in memory would be refused.
        link = secrets.get(AccountSecrets.jarvisLink(id))?.let { runCatching { feedJson.decodeFromString(JarvisLink.serializer(), it) }.getOrNull() }
        if (link == null) { _state.value = AccountState.NeedsSignIn(JarvisAccounts.NEEDS_PAIRING); return }
        when (val r = api.get("shared", emptyMap(), Shared.serializer())) {
            is Outcome.Success -> {
                shared = r.value
                _state.value = AccountState.Connected
                Log.d(LogTag.Accounts, "Account $id: Jarvis shares ${r.value.mail.size} mailboxes, ${r.value.calendars.size} calendars, ${r.value.tasks.size} task lists")
            }
            is Outcome.Failure -> if (_state.value !is AccountState.NeedsSignIn) {
                _state.value = AccountState.Error(Reads.why(r.error))
            }
        }
    }

    /** Try again, when Jarvis couldn't be reached. A removed phone needs pairing again, which Settings asks for. */
    override suspend fun grant(activity: Activity, show: (PendingIntent) -> Unit) {
        if (_state.value !is AccountState.NeedsSignIn) start()
    }

    override suspend fun onConsentResult(data: Intent?) = Unit

    /** Forgets the pairing on this phone; the owner removes the phone in Jarvis too, to be sure it can't be used. */
    override suspend fun disconnect() {
        secrets.put(AccountSecrets.jarvisLink(id), null)
        link = null
        shared = null
    }
}
