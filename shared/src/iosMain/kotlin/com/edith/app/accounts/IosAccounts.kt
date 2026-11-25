package com.edith.app.accounts

import com.edith.app.accounts.sources.CalendarSource
import com.edith.app.accounts.sources.MailSource
import com.edith.app.accounts.tasks.TaskSource
import com.edith.app.accounts.tasks.TasksAccess
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.secrets.SecretStore
import com.edith.app.core.time.Clock
import com.edith.app.core.util.PlatformLock
import com.edith.app.core.util.synchronized
import com.edith.app.google.GoogleAccountAuth
import com.edith.app.google.GoogleCalendarSource
import com.edith.app.google.GmailSource
import com.edith.app.google.GoogleScope
import com.edith.app.google.GoogleSignIn
import com.edith.app.google.browser.AppSignIn
import com.edith.app.google.browser.AuthSession
import com.edith.app.google.browser.BrowserGrant
import com.edith.app.google.browser.BrowserOAuth
import com.edith.app.google.browser.OAuthProvider
import com.edith.app.google.browser.RefreshTokenAuthorizer
import com.edith.app.google.calendar.CalendarClient
import com.edith.app.google.gmail.GmailClient
import com.edith.app.google.tasks.GoogleTaskSource
import com.edith.app.google.tasks.TasksClient
import com.edith.app.jarvis.JarvisAccounts
import com.edith.app.jarvis.JarvisConnection
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.seconds

/**
 * The iPhone's accounts (K4.6): the shared [AccountsCore] with Google (through the system's web sign-in sheet, [AppSignIn]) and Jarvis (the work account
 * read through the PC). Microsoft and iCloud follow when their sign-in seams do; an account of those kinds already saved reads "can't use this kind
 * of account" instead of failing.
 */
class IosAccounts(
    store: AccountStore,
    private val scope: CoroutineScope,
    val google: IosGoogleAccounts? = null,
    val jarvis: JarvisAccounts? = null,
    val microsoft: IosMicrosoftAccounts? = null,
    val icloud: IosICloudAccounts? = null,
) : AccountsCore(store, scope) {

    override fun connectionFor(account: Account, accountOf: () -> Account): AccountConnection? = when (account.provider) {
        Provider.Google -> google?.connect(accountOf) { onGoogleSignIn(google.signInAgain(accountOf())) }
        Provider.Jarvis -> jarvis?.connect(accountOf)
        Provider.Microsoft -> microsoft?.connect(accountOf) { onMicrosoftSignIn(microsoft.signInAgain(accountOf())) }
        Provider.ICloud -> icloud?.connect(accountOf)
    }

    /** The paired Jarvis, if there is one. */
    fun jarvisConnection(): JarvisConnection? = connectionOf(Provider.Jarvis) as? JarvisConnection

    /** Add account → Google: the sign-in sheet with Google's account chooser. */
    suspend fun addGoogle() {
        say(null)
        val g = google ?: return say(IosGoogleAccounts.NOT_SET_UP)
        onGoogleSignIn(g.signInNew())
    }

    /** Add account → Microsoft: the sign-in sheet with Microsoft's account picker. */
    suspend fun addMicrosoft() {
        say(null)
        val m = microsoft ?: return say(IosMicrosoftAccounts.NOT_SET_UP)
        onMicrosoftSignIn(m.signInNew())
    }

    /**
     * Add account → iCloud: checks the address and app-specific password (the IMAP login, then the calendar's discovery), then adds the account, or signs
     * it in again with the new password. Returns why not, or a note when only mail works; null when all is well.
     */
    suspend fun addICloud(address: String, password: String): String? {
        say(null)
        val ic = icloud ?: return IosICloudAccounts.NOT_SET_UP
        val check = ic.check(address, password)
        if (check is IosICloudAccounts.Check.Failed) return check.message
        check as IosICloudAccounts.Check.Ok
        val account = upsert(Provider.ICloud, address.trim(), null)
        setLogin(account.id, check.login)
        ic.keep(account.id, password)
        startConnection(account.id)
        return if (check.calendar) null else IosICloudAccounts.CALENDAR_UNAVAILABLE
    }


    /** Add account → Work (via Jarvis), step 1: finds the PC and its certificate, for the owner to compare. */
    suspend fun probeJarvis(address: String): JarvisAccounts.Probe = jarvis?.probe(address) ?: JarvisAccounts.Probe.Failed(NOT_SET_UP_JARVIS)

    /** Add account → Work (via Jarvis), step 2: pairs with the code Jarvis shows, then adds the account. Returns why not, or null when all is well. */
    suspend fun addJarvis(base: String, fingerprint: String, code: String, deviceName: String): String? {
        say(null)
        val j = jarvis ?: return NOT_SET_UP_JARVIS
        val paired = when (val r = j.pair(base, fingerprint, code, deviceName)) {
            is JarvisAccounts.Paired.Ok -> r
            is JarvisAccounts.Paired.Failed -> return r.message
        }
        val isNew = isNew(Provider.Jarvis, paired.pcName)
        val account = upsert(Provider.Jarvis, paired.pcName, null)
        j.keep(account.id, paired)
        if (isNew) renameAccount(account.id, "Work")   // a first pairing is called "Work" (the owner can rename it)
        startConnection(account.id)
        return null
    }

    suspend fun rename(id: String, label: String): RenameOutcome = renameAccount(id, label)

    /** A Google sign-in finished: the account it names is added, or, if it is already here, signed in again. */
    private suspend fun onGoogleSignIn(r: GoogleSignIn.Result) {
        when (r) {
            is GoogleSignIn.Result.SignedIn -> {
                val account = upsert(Provider.Google, r.email, Account.SIGN_IN_BROWSER)
                r.grant?.let { google?.keep(account.id, it) }
                startConnection(account.id)
            }
            GoogleSignIn.Result.Cancelled -> Unit
            is GoogleSignIn.Result.Failed -> say(r.message)
        }
    }

    /** A Microsoft sign-in finished: the account it names is added, or signed in again. */
    private suspend fun onMicrosoftSignIn(r: IosMicrosoftAccounts.Result) {
        when (r) {
            is IosMicrosoftAccounts.Result.SignedIn -> {
                val account = upsert(Provider.Microsoft, r.address, null)
                microsoft?.keep(account.id, r.grant)
                startConnection(account.id)
            }
            IosMicrosoftAccounts.Result.Cancelled -> Unit
            is IosMicrosoftAccounts.Result.Failed -> say(r.message)
        }
    }

    companion object {
        const val NOT_SET_UP_JARVIS = "Work through Jarvis isn't available in this build."
    }
}

/**
 * Google on the iPhone (K4.6): the sign-in sheet ([AppSignIn]) for the Desktop-less "iOS" client, whose redirect is the client ID reversed
 * (`com.googleusercontent.apps.<id>:/oauth2redirect`), a refresh token per account in the Keychain ([RefreshTokenAuthorizer]), and the calendar,
 * Gmail and Tasks clients that moved to shared code. Not set up (no client ID in the build) the sign-in says so.
 */
class IosGoogleAccounts(
    clientId: String,
    session: AuthSession,
    private val secrets: SecretStore,
    private val clock: Clock,
    private val http: Lazy<HttpClient>,
    private val dispatchers: AppDispatchers,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    val oauth = BrowserOAuth(http, dispatchers, OAuthProvider.google(clientId))
    private val signIn = AppSignIn(oauth, session, redirectFor(clientId))
    private val authorizers = HashMap<String, RefreshTokenAuthorizer>()
    private val lock = PlatformLock()

    val isConfigured: Boolean get() = signIn.isConfigured

    private fun authorizer(accountId: String): RefreshTokenAuthorizer =
        lock.synchronized { authorizers.getOrPut(accountId) { RefreshTokenAuthorizer(accountId, oauth, secrets, clock) } }

    suspend fun signInNew(): GoogleSignIn.Result = result(signIn.signIn(scope = ALL_SCOPES))

    suspend fun signInAgain(account: Account): GoogleSignIn.Result = result(signIn.signIn(loginHint = account.address, scope = ALL_SCOPES))

    /** Keeps a sign-in's tokens for [accountId]. */
    suspend fun keep(accountId: String, grant: BrowserGrant) = authorizer(accountId).keep(grant)

    /** The sign-in's answer in Google's words. */
    internal fun result(r: AppSignIn.Result): GoogleSignIn.Result = when (r) {
        AppSignIn.Result.Cancelled -> GoogleSignIn.Result.Cancelled
        is AppSignIn.Result.Failed -> GoogleSignIn.Result.Failed(message(r.why))
        is AppSignIn.Result.Tokens -> {
            val t = r.tokens
            val refreshToken = t.refreshToken
            val email = t.email
            when {
                refreshToken == null -> GoogleSignIn.Result.Failed(NO_REFRESH_TOKEN)
                email == null -> GoogleSignIn.Result.Failed(NO_EMAIL)
                else -> GoogleSignIn.Result.SignedIn(
                    email, null,
                    BrowserGrant(refreshToken, t.accessToken, clock.now() + t.expiresInSeconds.seconds, t.scopes ?: GoogleScope.entries.map { it.uri }.toSet()),
                )
            }
        }
    }

    private fun message(why: AppSignIn.Why): String = when (why) {
        AppSignIn.Why.NotSetUp -> NOT_SET_UP
        AppSignIn.Why.Declined, AppSignIn.Why.AdminApproval -> "You didn't allow access, so the account isn't connected."
        AppSignIn.Why.WrongAddress -> "That answer isn't from this sign-in. Sign in again."
        is AppSignIn.Why.RedirectError -> "Google didn't sign you in (${why.why()}). Try again."
        AppSignIn.Why.CodeExpired -> "Google says that sign-in expired or was already used. Sign in again."
        AppSignIn.Why.BadClient -> RefreshTokenAuthorizer.BAD_CLIENT
        AppSignIn.Why.NoNetwork -> RefreshTokenAuthorizer.NO_NETWORK
        is AppSignIn.Why.ExchangeFailed -> "Google refused the sign-in (${why.reason}). Try again."
        is AppSignIn.Why.SheetFailed -> "Edith couldn't show Google's sign-in page. Try again."
    }

    private fun AppSignIn.Why.RedirectError.why() = error

    /** The live connection for one Google account. [signInAgain] runs the sign-in sheet for it and files the result. */
    fun connect(accountOf: () -> Account, signInAgain: suspend () -> Unit): AccountConnection {
        val id = accountOf().id
        val authorizer = authorizer(id)
        val auth = GoogleAccountAuth(accountOf, authorizer, usesBrowser = { true }, signInAgain = { signInAgain() })
        return IosGoogleConnection(
            auth, authorizer,
            GoogleCalendarSource(accountOf, CalendarClient(http, auth, dispatchers, zone, accountId = id), zone),
            GmailSource(accountOf, GmailClient(http, auth, dispatchers, zone, accountId = id), zone),
            GoogleTaskSource(accountOf, TasksClient(http, auth, dispatchers, accountId = id)),
        )
    }

    companion object {
        const val NOT_SET_UP = "Google isn't set up in this build: the iPhone client ID is missing (docs/google-setup.md)."
        const val NO_REFRESH_TOKEN = "Google didn't let Edith stay signed in. Remove Edith at myaccount.google.com/permissions and sign in again."
        const val NO_EMAIL = "Google didn't say which account signed in. Sign in again."
        private val ALL_SCOPES = BrowserOAuth.scopeString(GoogleScope.entries.toSet())

        /** Google's redirect for an iOS client: its ID reversed, `com.googleusercontent.apps.<id>:/oauth2redirect`. Empty when there is no client ID. */
        fun redirectFor(clientId: String): String {
            val suffix = ".apps.googleusercontent.com"
            if (!clientId.endsWith(suffix)) return ""
            return "com.googleusercontent.apps.${clientId.removeSuffix(suffix)}:/oauth2redirect"
        }
    }
}

/** One Google account on the iPhone: its access and its calendar, Gmail and to-dos. */
private class IosGoogleConnection(
    private val auth: GoogleAccountAuth,
    private val authorizer: RefreshTokenAuthorizer,
    override val calendar: CalendarSource,
    override val mail: MailSource,
    override val tasks: TaskSource,
) : AccountConnection {
    override val state: StateFlow<AccountState> get() = auth.state
    override val capabilities = Capabilities(calendar = true, mail = true, tasks = true)
    override val tasksAccess: StateFlow<TasksAccess> get() = auth.tasks

    override suspend fun start() = auth.check()
    override suspend fun grant(activity: PlatformActivity, show: (PlatformPendingIntent) -> Unit) = auth.grant(activity, show)
    override suspend fun grantTasks(activity: PlatformActivity, show: (PlatformPendingIntent) -> Unit) = auth.grantTasks(activity, show)
    override suspend fun onConsentResult(data: PlatformIntent?) = auth.onConsentResult(data)

    /** Revokes this account's token only, then forgets it on the device. */
    override suspend fun disconnect() {
        auth.revoke()
        authorizer.forget()
    }
}
