package com.edith.app.icloud

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
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.secrets.SecretStore
import com.edith.app.google.GoogleAccountAuth
import com.edith.app.icloud.calendar.CalDavClient
import com.edith.app.icloud.calendar.DavResult
import com.edith.app.icloud.calendar.ICloudCalendarSource
import com.edith.app.icloud.mail.ICloudMailSource
import com.edith.app.icloud.mail.MailServers
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.time.ZoneId

/**
 * iCloud as an account provider (C24, D17): an iCloud address and an app-specific password, which the owner types into
 * Edith and Edith keeps encrypted in [SecretStore] (`account/<id>/password`). Mail over IMAP and SMTP, the calendar over
 * CalDAV. There's no OAuth for iCloud, and the password reaches all of the account's mail and calendars (trap 4).
 */
class ICloudAccounts(
    private val secrets: SecretStore,
    private val http: Lazy<OkHttpClient>,
    private val dispatchers: AppDispatchers,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val servers: MailServers = MailServers(),
    private val calDavBaseUrl: String = CalDavClient.BASE_URL,
    private val checkTimeoutMs: Long = CHECK_TIMEOUT_MS,
) {
    /** What the add-account check found (C24 §1). */
    sealed interface Check {
        /** [login] is the IMAP user name that worked; [calendar] whether CalDAV discovery worked too. */
        data class Ok(val login: String, val calendar: Boolean) : Check
        data class Failed(val message: String) : Check
    }

    /**
     * The add-account check (§1, a 20 s limit): IMAP login with the mail name, then the full address; then CalDAV
     * discovery. Only one login at a time (iCloud may refuse many in a row, §4).
     */
    suspend fun check(address: String, password: String): Check = withContext(dispatchers.io) {
        withTimeoutOrNull(checkTimeoutMs) { checkNow(address.trim(), password.trim()) } ?: Check.Failed(TIMED_OUT)
    }

    private suspend fun checkNow(address: String, password: String): Check {
        if ('@' !in address) return Check.Failed(NOT_AN_ADDRESS)
        val probe = ICloudMailSource({ Account("check", com.edith.app.accounts.Provider.ICloud, address, address) }, { password }, dispatchers, servers, zone)
        var login: String? = null
        for (user in listOf(address.substringBefore('@'), address).distinct()) {
            try {
                probe.login(user, password)
                login = user
                break
            } catch (e: AuthenticationFailedException) {
                continue   // try the full address next
            } catch (e: MessagingException) {
                val network = generateSequence<Throwable>(e) { it.cause }.any { it is IOException }
                Log.w(LogTag.Accounts, "iCloud check: IMAP ${if (network) "unreachable" else "failed"}")
                return Check.Failed(if (network) ICloudMailSource.NO_NETWORK else "iCloud Mail didn't answer properly. Try again.")
            }
        }
        if (login == null) {
            Log.i(LogTag.Accounts, "iCloud check: the password was refused")
            return Check.Failed(WRONG_PASSWORD)
        }
        val calendar = CalDavClient(http, dispatchers, address, password, calDavBaseUrl).discover()
        Log.i(LogTag.Accounts, "iCloud check: mail OK, calendar ${if (calendar is DavResult.Ok) "OK" else "not available"}")
        return Check.Ok(login, calendar is DavResult.Ok)
    }

    /** Keeps the password for [accountId] (after the check). */
    suspend fun keep(accountId: String, password: String) = secrets.put(AccountSecrets.password(accountId), password.trim())

    /** The live connection for one iCloud account. */
    fun connect(accountOf: () -> Account): ICloudConnection = ICloudConnection(accountOf, secrets, http, dispatchers, zone, servers, calDavBaseUrl)

    companion object {
        const val CHECK_TIMEOUT_MS = 20_000L
        const val WRONG_PASSWORD = "Apple didn't accept that password. Use an app-specific password, not your Apple Account password."
        const val NOT_AN_ADDRESS = "Enter your iCloud email address."
        const val TIMED_OUT = "iCloud didn't answer within 20 seconds. Check the connection and try again."
        const val CALENDAR_UNAVAILABLE = "Mail works, but iCloud Calendar didn't answer, so Edith added this account for email only."
        const val NEEDS_NEW_PASSWORD = "needs a new app-specific password"
        /** Where to stop the password working everywhere (§1). */
        const val REVOKE_HINT = "To stop it working everywhere, revoke 'Edith' at account.apple.com → App-Specific Passwords."
    }
}

/** One iCloud account: its mail and calendar, and a state from both (C24 §1, §4). */
class ICloudConnection(
    private val accountOf: () -> Account,
    private val secrets: SecretStore,
    http: Lazy<OkHttpClient>,
    dispatchers: AppDispatchers,
    zone: () -> ZoneId,
    servers: MailServers,
    calDavBaseUrl: String,
) : AccountConnection {
    private val _state = MutableStateFlow<AccountState>(AccountState.Checking)
    override val state: StateFlow<AccountState> = _state.asStateFlow()

    @Volatile private var calendarWorks = true
    override val capabilities: Capabilities get() = Capabilities(calendar = calendarWorks, mail = true)

    private val id get() = accountOf().id
    private suspend fun password(): String? = secrets.get(AccountSecrets.password(id))

    @Volatile private var dav: CalDavClient? = null
    private val davClient: () -> CalDavClient = {
        dav ?: CalDavClient(http, dispatchers, accountOf().address, cachedPassword.orEmpty(), calDavBaseUrl).also { dav = it }
    }
    @Volatile private var cachedPassword: String? = null

    private val calendarSource = ICloudCalendarSource(accountOf, davClient, zone, onUnauthorized = ::passwordRejected)
    private val mailSource = ICloudMailSource(accountOf, ::password, dispatchers, servers, zone, onPasswordRejected = ::passwordRejected)

    override val calendar: CalendarSource? get() = calendarSource.takeIf { calendarWorks }
    override val mail: MailSource get() = mailSource

    /** At startup and after adding: the calendar's discovery (which also proves the password), quietly. */
    override suspend fun start() {
        _state.value = AccountState.Checking
        val pw = password()
        if (pw == null) { _state.value = AccountState.NeedsSignIn(ICloudAccounts.NEEDS_NEW_PASSWORD); return }
        if (pw != cachedPassword) { cachedPassword = pw; dav = null }
        // One mail login (§4: one connection per operation, no loops) and the calendar's discovery.
        val mailOk = when (val r = mailSource.search(com.edith.app.accounts.sources.MailQuery(), 1)) {
            is Outcome.Success -> true
            is Outcome.Failure -> { if (_state.value is AccountState.NeedsSignIn) return; _state.value = AccountState.Error(com.edith.app.accounts.sources.Reads.why(r.error)); false }
        }
        calendarWorks = when (calendarSource.home()) {
            is Outcome.Success -> true
            is Outcome.Failure -> { if (_state.value is AccountState.NeedsSignIn) return; false }
        }
        if (mailOk) _state.value = AccountState.Connected
        Log.d(LogTag.Accounts, "Account $id: ${GoogleAccountAuth.stateName(_state.value)}, calendar ${if (calendarWorks) "yes" else "no"}")
    }

    /** Try again (after no network). A refused password needs a new one, which Settings asks for. */
    override suspend fun grant(activity: Activity, show: (PendingIntent) -> Unit) {
        if (_state.value !is AccountState.NeedsSignIn) start()
    }

    override suspend fun onConsentResult(data: Intent?) = Unit

    /** Deletes the password on the phone; only the owner can revoke it at Apple (§1). */
    override suspend fun disconnect() {
        secrets.put(AccountSecrets.password(id), null)
        cachedPassword = null
        dav = null
    }

    private suspend fun passwordRejected() {
        Log.w(LogTag.Accounts, "Account $id: iCloud refused the app-specific password")
        _state.value = AccountState.NeedsSignIn(ICloudAccounts.NEEDS_NEW_PASSWORD)
    }
}
