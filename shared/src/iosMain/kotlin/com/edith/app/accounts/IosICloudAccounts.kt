package com.edith.app.accounts

import com.edith.app.accounts.sources.CalendarSource
import com.edith.app.accounts.sources.MailSource
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.secrets.SecretStore
import com.edith.app.google.GoogleAccountAuth
import com.edith.app.icloud.calendar.DavResult
import com.edith.app.icloud.calendar.ICloudCalendarSource
import com.edith.app.icloud.calendar.KtorDavClient
import com.edith.app.core.error.Outcome
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone

/**
 * iCloud on the iPhone (K4.8): an iCloud address and an app-specific password, which the owner types into Edith and Edith keeps in the Keychain
 * (`account/<id>/password`), with the **calendar** over CalDAV ([KtorDavClient]). Mail needs an IMAP client the iPhone does not have yet, so an iCloud account
 * here is calendar-only. The password reaches all of the account's calendars and mail: the add screen says to use an app-specific one.
 */
class IosICloudAccounts(
    private val secrets: SecretStore,
    private val http: Lazy<HttpClient>,
    private val dispatchers: AppDispatchers,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val calDavBaseUrl: String = KtorDavClient.BASE_URL,
) {
    /** What the add-account check found. */
    sealed interface Check {
        data object Ok : Check
        data class Failed(val message: String) : Check
    }

    /** The add-account check (a 20 s limit): CalDAV discovery with the address and password, which also proves the password. */
    suspend fun check(address: String, password: String): Check = withContext(dispatchers.io) {
        withTimeoutOrNull(CHECK_TIMEOUT_MS) {
            if ('@' !in address.trim()) return@withTimeoutOrNull Check.Failed(NOT_AN_ADDRESS)
            when (val r = KtorDavClient(http, dispatchers, address.trim(), password.trim(), calDavBaseUrl).discover()) {
                is DavResult.Ok -> if (r.value.calendars.isEmpty()) Check.Failed(NO_CALENDARS) else Check.Ok
                DavResult.Unauthorized -> Check.Failed(WRONG_PASSWORD)
                is DavResult.Failed -> Check.Failed(r.why)
                DavResult.Changed, DavResult.NotFound -> Check.Failed("iCloud Calendar didn't answer properly. Try again.")
            }
        } ?: Check.Failed(TIMED_OUT)
    }

    /** Keeps the password for [accountId] (after the check). */
    suspend fun keep(accountId: String, password: String) = secrets.put(AccountSecrets.password(accountId), password.trim())

    fun connect(accountOf: () -> Account): AccountConnection = IosICloudConnection(accountOf, secrets, http, dispatchers, zone, calDavBaseUrl)

    companion object {
        const val CHECK_TIMEOUT_MS = 20_000L
        const val WRONG_PASSWORD = "Apple didn't accept that password. Use an app-specific password, not your Apple Account password."
        const val NOT_AN_ADDRESS = "Enter your iCloud email address."
        const val TIMED_OUT = "iCloud didn't answer within 20 seconds. Check the connection and try again."
        const val NO_CALENDARS = "That iCloud account has no calendar Edith can use."
        const val NEEDS_NEW_PASSWORD = "needs a new app-specific password"
        const val NOT_SET_UP = "iCloud isn't available in this build."
    }
}

/** One iCloud account on the iPhone: its calendar, with a state from asking iCloud. */
private class IosICloudConnection(
    private val accountOf: () -> Account,
    private val secrets: SecretStore,
    private val http: Lazy<HttpClient>,
    private val dispatchers: AppDispatchers,
    zone: () -> TimeZone,
    private val calDavBaseUrl: String,
) : AccountConnection {
    private val _state = MutableStateFlow<AccountState>(AccountState.Checking)
    override val state: StateFlow<AccountState> = _state.asStateFlow()
    override val capabilities = Capabilities(calendar = true, mail = false)

    private val id get() = accountOf().id
    @kotlin.concurrent.Volatile private var dav: KtorDavClient? = null
    @kotlin.concurrent.Volatile private var cachedPassword: String? = null

    private val calendarSource = ICloudCalendarSource(
        accountOf,
        { dav ?: KtorDavClient(http, dispatchers, accountOf().address, cachedPassword.orEmpty(), calDavBaseUrl).also { dav = it } },
        zone,
        onUnauthorized = ::passwordRejected,
    )
    override val calendar: CalendarSource get() = calendarSource
    override val mail: MailSource? get() = null

    /** At startup and after adding: the calendar's discovery, which also proves the password. */
    override suspend fun start() {
        _state.value = AccountState.Checking
        val pw = secrets.get(AccountSecrets.password(id))
        if (pw == null) { _state.value = AccountState.NeedsSignIn(IosICloudAccounts.NEEDS_NEW_PASSWORD); return }
        if (pw != cachedPassword) { cachedPassword = pw; dav = null }
        when (val r = calendarSource.home()) {
            is Outcome.Success -> _state.value = AccountState.Connected
            is Outcome.Failure -> if (_state.value !is AccountState.NeedsSignIn) _state.value = AccountState.Error(com.edith.app.accounts.sources.Reads.why(r.error))
        }
        Log.d(LogTag.Accounts, "Account $id: ${GoogleAccountAuth.stateName(_state.value)}")
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
