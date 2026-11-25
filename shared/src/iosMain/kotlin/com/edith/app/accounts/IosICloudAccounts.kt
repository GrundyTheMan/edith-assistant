package com.edith.app.accounts

import com.edith.app.accounts.sources.CalendarSource
import com.edith.app.accounts.sources.MailSource
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.secrets.SecretStore
import com.edith.app.google.GoogleAccountAuth
import com.edith.app.icloud.calendar.DavResult
import com.edith.app.icloud.calendar.ICloudCalendarSource
import com.edith.app.icloud.calendar.KtorDavClient
import com.edith.app.icloud.mail.ImapMailSource
import com.edith.app.icloud.mail.MailException
import com.edith.app.icloud.mail.MailTransportFactory
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.io.IOException

/**
 * iCloud on the iPhone (K4.8, K4.10): an iCloud address and an app-specific password, which the owner types into Edith and Edith keeps in the Keychain
 * (`account/<id>/password`). Mail over IMAP and SMTP ([ImapMailSource], Apple's TLS), the calendar over CalDAV ([KtorDavClient]). There is no OAuth for
 * iCloud, and the password reaches all of the account's mail and calendars: the add screen says to use an app-specific one.
 */
class IosICloudAccounts(
    private val secrets: SecretStore,
    private val http: Lazy<HttpClient>,
    private val transports: MailTransportFactory,
    private val dispatchers: AppDispatchers,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val calDavBaseUrl: String = KtorDavClient.BASE_URL,
) {
    /** What the add-account check found. [login] is the IMAP user name that worked; [calendar] whether CalDAV discovery worked too. */
    sealed interface Check {
        data class Ok(val login: String, val calendar: Boolean) : Check
        data class Failed(val message: String) : Check
    }

    /**
     * The add-account check (a 20 s limit): IMAP login with the name before the `@`, then the full address (iCloud takes either, by account); then CalDAV
     * discovery. One login at a time, since iCloud may refuse many in a row.
     */
    suspend fun check(address: String, password: String): Check = withContext(dispatchers.io) {
        withTimeoutOrNull(CHECK_TIMEOUT_MS) { checkNow(address.trim(), password.trim()) } ?: Check.Failed(TIMED_OUT)
    }

    private suspend fun checkNow(address: String, password: String): Check {
        if ('@' !in address) return Check.Failed(NOT_AN_ADDRESS)
        val probe = ImapMailSource({ Account("check", Provider.ICloud, address, address) }, { password }, transports, dispatchers, zone = zone)
        var login: String? = null
        for (user in listOf(address.substringBefore('@'), address).distinct()) {
            try {
                probe.login(user, password)
                login = user
                break
            } catch (e: MailException) {
                if (e.passwordRejected) continue   // try the full address next
                Log.w(LogTag.Accounts, "iCloud check: IMAP failed")
                return Check.Failed("iCloud Mail didn't answer properly. Try again.")
            } catch (e: IOException) {
                Log.w(LogTag.Accounts, "iCloud check: IMAP unreachable")
                return Check.Failed(ImapMailSource.NO_NETWORK)
            }
        }
        if (login == null) {
            Log.i(LogTag.Accounts, "iCloud check: the password was refused")
            return Check.Failed(WRONG_PASSWORD)
        }
        val calendar = KtorDavClient(http, dispatchers, address, password, calDavBaseUrl).discover()
        val works = calendar is DavResult.Ok && calendar.value.calendars.isNotEmpty()
        Log.i(LogTag.Accounts, "iCloud check: mail OK, calendar ${if (works) "OK" else "not available"}")
        return Check.Ok(login, works)
    }

    /** Keeps the password for [accountId] (after the check). */
    suspend fun keep(accountId: String, password: String) = secrets.put(AccountSecrets.password(accountId), password.trim())

    fun connect(accountOf: () -> Account): AccountConnection = IosICloudConnection(accountOf, secrets, http, transports, dispatchers, zone, calDavBaseUrl)

    companion object {
        const val CHECK_TIMEOUT_MS = 20_000L
        const val WRONG_PASSWORD = "Apple didn't accept that password. Use an app-specific password, not your Apple Account password."
        const val NOT_AN_ADDRESS = "Enter your iCloud email address."
        const val TIMED_OUT = "iCloud didn't answer within 20 seconds. Check the connection and try again."
        const val CALENDAR_UNAVAILABLE = "Mail works, but iCloud Calendar didn't answer, so Edith added this account for email only."
        const val NEEDS_NEW_PASSWORD = "needs a new app-specific password"
        const val NOT_SET_UP = "iCloud isn't available in this build."
    }
}

/** One iCloud account on the iPhone: its mail and calendar, with a state from asking iCloud. */
private class IosICloudConnection(
    private val accountOf: () -> Account,
    private val secrets: SecretStore,
    private val http: Lazy<HttpClient>,
    transports: MailTransportFactory,
    private val dispatchers: AppDispatchers,
    zone: () -> TimeZone,
    private val calDavBaseUrl: String,
) : AccountConnection {
    private val _state = MutableStateFlow<AccountState>(AccountState.Checking)
    override val state: StateFlow<AccountState> = _state.asStateFlow()

    @kotlin.concurrent.Volatile private var calendarWorks = true
    override val capabilities: Capabilities get() = Capabilities(calendar = calendarWorks, mail = true)

    private val id get() = accountOf().id
    @kotlin.concurrent.Volatile private var dav: KtorDavClient? = null
    @kotlin.concurrent.Volatile private var cachedPassword: String? = null

    private val calendarSource = ICloudCalendarSource(
        accountOf,
        { dav ?: KtorDavClient(http, dispatchers, accountOf().address, cachedPassword.orEmpty(), calDavBaseUrl).also { dav = it } },
        zone,
        onUnauthorized = ::passwordRejected,
    )
    private val mailSource = ImapMailSource(accountOf, { secrets.get(AccountSecrets.password(id)) }, transports, dispatchers, zone = zone, onPasswordRejected = ::passwordRejected)

    override val calendar: CalendarSource? get() = calendarSource.takeIf { calendarWorks }
    override val mail: MailSource get() = mailSource

    /** At startup and after adding: one quiet mail search (which proves the password) and the calendar's discovery. */
    override suspend fun start() {
        _state.value = AccountState.Checking
        val pw = secrets.get(AccountSecrets.password(id))
        if (pw == null) { _state.value = AccountState.NeedsSignIn(IosICloudAccounts.NEEDS_NEW_PASSWORD); return }
        if (pw != cachedPassword) { cachedPassword = pw; dav = null }
        // One mail login and the calendar's discovery, one connection per operation.
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

    /** Try again (after no network). A refused password needs a new one, which the accounts page asks for. */
    override suspend fun grant(activity: PlatformActivity, show: (PlatformPendingIntent) -> Unit) {
        if (_state.value !is AccountState.NeedsSignIn) start()
    }

    override suspend fun onConsentResult(data: PlatformIntent?) = Unit

    /** Deletes the password on the phone; only the owner can revoke it at Apple (account.apple.com → App-Specific Passwords). */
    override suspend fun disconnect() {
        secrets.put(AccountSecrets.password(id), null)
        cachedPassword = null
        dav = null
    }

    private suspend fun passwordRejected() {
        Log.w(LogTag.Accounts, "Account $id: iCloud refused the app-specific password")
        _state.value = AccountState.NeedsSignIn(IosICloudAccounts.NEEDS_NEW_PASSWORD)
    }
}
