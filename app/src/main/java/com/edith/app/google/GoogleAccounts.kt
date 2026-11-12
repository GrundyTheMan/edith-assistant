package com.edith.app.google

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import com.edith.app.accounts.Account
import com.edith.app.accounts.AccountConnection
import com.edith.app.accounts.AccountState
import com.edith.app.accounts.Capabilities
import com.edith.app.accounts.sources.CalendarSource
import com.edith.app.accounts.sources.MailSource
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.time.Clock
import com.edith.app.core.time.SystemClock
import com.edith.app.google.browser.BrowserGoogle
import com.edith.app.google.browser.BrowserGrant
import com.edith.app.google.calendar.CalendarClient
import com.edith.app.google.gmail.GmailClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import java.time.ZoneId

/**
 * Google as an account provider (C17, C17b; any number of accounts since C22). Each account reaches Google through
 * Play services (C17) or the browser (C17b, D16), chosen per account; signing in, the app-wide browser flow, and each
 * account's [GoogleConnection] start here.
 */
class GoogleAccounts(
    private val play: GoogleSignIn,
    /** Play services' access for one account (`AuthorizationRequest.setAccount`, so each account can use it). */
    private val playAuthorizer: (email: String) -> GoogleAuthorizer,
    private val browser: BrowserGoogle,
    private val webClientId: String,
    /** `GoogleApiAvailability.isGooglePlayServicesAvailable(context) == SUCCESS`; a lambda so tests can choose. */
    private val playServicesAvailable: () -> Boolean,
    private val http: Lazy<OkHttpClient>,
    private val dispatchers: AppDispatchers,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val calendarBaseUrl: String = CalendarClient.BASE_URL,
    private val gmailBaseUrl: String = GmailClient.BASE_URL,
    /** "Today" for email searches (C19's exact local days). */
    private val clock: Clock = SystemClock,
) {
    private val playConfigured = webClientId.isNotBlank()

    /** Neither a Web nor a Desktop client ID in this build (docs/google-setup.md): Google can't be added. */
    val notSetUp: Boolean get() = !playConfigured && !browser.isConfigured

    /** **Use a browser instead** can be offered (this build has the Desktop client ID). */
    val browserAvailable: Boolean get() = browser.isConfigured

    /** True while Edith waits for the browser (the app-wide "Finish signing in" dialog). */
    val waiting: StateFlow<Boolean> get() = browser.waiting

    fun paste(address: String) = browser.paste(address)
    fun cancel() = browser.cancel()

    /** Add account → Google: Play services' account sheet, or the browser with Google's account chooser. */
    suspend fun signInNew(activity: Activity, useBrowser: Boolean): GoogleSignIn.Result {
        if (notSetUp) return GoogleSignIn.Result.Failed(NOT_SET_UP)
        val viaBrowser = useBrowser || chooseBrowser(browser.isConfigured, playConfigured, playServicesAvailable(), false, false)
        return if (viaBrowser) browser.signIn(activity) else play.signIn(activity, webClientId)
    }

    /** Sign in again (the browser path's Grant access), suggesting [account] on Google's page. */
    suspend fun signInAgain(activity: Activity, account: Account): GoogleSignIn.Result =
        browser.signIn(activity, loginHint = account.address, accountId = account.id)

    /** A browser sign-in an earlier run left waiting: the account it was for (null: new) and its result. */
    suspend fun resumePending(): Pair<String?, GoogleSignIn.Result>? = if (browser.isConfigured) browser.resumePending() else null

    /** Keeps a browser sign-in's tokens for [accountId]. */
    suspend fun keep(accountId: String, grant: BrowserGrant) = browser.keep(accountId, grant)

    /** The live connection for one Google account. [signInAgain] runs the browser sign-in for it and files the result. */
    fun connect(accountOf: () -> Account, signInAgain: suspend (Activity) -> Unit): GoogleConnection {
        val usesBrowser: suspend () -> Boolean = {
            val a = accountOf()
            chooseBrowser(
                browserConfigured = browser.isConfigured,
                playConfigured = playConfigured,
                playAvailable = playServicesAvailable(),
                browserTokenStored = browser.hasRefreshToken(a.id),
                accountUsesBrowser = a.signIn == Account.SIGN_IN_BROWSER,
            )
        }
        val authorizer = ChoosingAuthorizer(accountOf, { playAuthorizer(accountOf().address) }, browser.authorizer(accountOf().id), usesBrowser)
        val auth = GoogleAccountAuth(accountOf, authorizer, usesBrowser, signInAgain)
        val id = accountOf().id
        return GoogleConnection(
            accountOf = accountOf,
            auth = auth,
            calendar = GoogleCalendarSource(accountOf, CalendarClient(http, auth, dispatchers, zone, calendarBaseUrl, id), zone),
            mail = GmailSource(accountOf, GmailClient(http, auth, dispatchers, zone, gmailBaseUrl, id), zone, clock::now),
            forget = { browser.forget(id) },
            signOutPlay = { if (playServicesAvailable()) play.signOut() },
        )
    }

    companion object {
        const val NOT_SET_UP = "Google isn't set up in this build: the Google project's Web and Desktop client IDs are missing (docs/google-setup.md)."

        /**
         * The choice rule in one place (C17b §1, per account since C22): the browser only when it's set up, and then
         * when any reason says so.
         */
        fun chooseBrowser(
            browserConfigured: Boolean,
            playConfigured: Boolean,
            playAvailable: Boolean,
            browserTokenStored: Boolean,
            accountUsesBrowser: Boolean,
        ): Boolean = browserConfigured && (browserTokenStored || accountUsesBrowser || !playAvailable || !playConfigured)
    }
}

/** One account's access through Play services or the browser, whichever [usesBrowser] says now. */
private class ChoosingAuthorizer(
    private val accountOf: () -> Account,
    private val play: () -> GoogleAuthorizer,
    private val browser: GoogleAuthorizer,
    private val usesBrowser: suspend () -> Boolean,
) : GoogleAuthorizer {
    private suspend fun current(): GoogleAuthorizer = if (usesBrowser()) browser else play()

    override suspend fun authorize(scopes: Set<GoogleScope>) = current().authorize(scopes)
    /** Only Play services hands out consent screens. */
    override fun fromConsent(data: Intent?) = play().fromConsent(data)
    override suspend fun clearToken(token: String) = current().clearToken(token)
    override suspend fun revoke(email: String) = current().revoke(accountOf().address)
}

/** One Google account: its access ([auth]) and its calendar and Gmail (C22 §2–3). */
class GoogleConnection(
    private val accountOf: () -> Account,
    val auth: GoogleAccountAuth,
    override val calendar: CalendarSource,
    override val mail: MailSource,
    private val forget: suspend () -> Unit,
    private val signOutPlay: suspend () -> Unit,
) : AccountConnection {
    override val state: StateFlow<AccountState> get() = auth.state
    override val capabilities = Capabilities(calendar = true, mail = true)

    override suspend fun start() = auth.check()

    override suspend fun grant(activity: Activity, show: (PendingIntent) -> Unit) = auth.grant(activity, show)

    override suspend fun onConsentResult(data: Intent?) = auth.onConsentResult(data)

    /** Revokes this account's token only (C22 §2), then forgets it on the device. */
    override suspend fun disconnect() {
        auth.revoke()
        forget()
        if (accountOf().signIn != Account.SIGN_IN_BROWSER) {
            try {
                signOutPlay()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LogTag.Google, "Couldn't clear Play services' sign-in", e)
            }
        }
    }
}
