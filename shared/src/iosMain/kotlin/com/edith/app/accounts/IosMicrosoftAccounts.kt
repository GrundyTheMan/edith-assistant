package com.edith.app.accounts

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.secrets.SecretStore
import com.edith.app.core.time.Clock
import com.edith.app.core.util.PlatformLock
import com.edith.app.core.util.synchronized
import com.edith.app.google.browser.AppSignIn
import com.edith.app.google.browser.AuthSession
import com.edith.app.google.browser.BrowserOAuth
import com.edith.app.microsoft.GraphClient
import com.edith.app.microsoft.MicrosoftAccountAuth
import com.edith.app.microsoft.MicrosoftGrant
import com.edith.app.microsoft.MicrosoftOAuth
import com.edith.app.microsoft.OutlookCalendarSource
import com.edith.app.microsoft.OutlookMailSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/**
 * Microsoft on the iPhone (K4.7): Outlook.com and Microsoft 365 accounts through the system's sign-in sheet ([AppSignIn]), the iPhone's counterpart of
 * Android's `MicrosoftAccounts`. Microsoft's redirect for an iOS app is `msauth.<bundle id>://auth` (the app registration's iOS platform, with
 * the bundle id; docs/microsoft-setup.md). Not set up (no client ID in the build) the sign-in says so. Graph's calendar needs a Windows name for the
 * phone's time zone, which the iPhone does not give yet: the calendar then works in UTC, as Android does when it cannot map a zone.
 */
class IosMicrosoftAccounts(
    clientId: String,
    bundleId: String,
    session: AuthSession,
    private val secrets: SecretStore,
    private val http: Lazy<HttpClient>,
    private val dispatchers: AppDispatchers,
    private val clock: Clock,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val graphBaseUrl: String = GraphClient.BASE_URL,
) {
    sealed interface Result {
        data class SignedIn(val address: String, val grant: MicrosoftGrant) : Result
        data object Cancelled : Result
        data class Failed(val message: String) : Result
    }

    val oauth = BrowserOAuth(http, dispatchers, MicrosoftOAuth.provider(clientId))
    private val signIn = AppSignIn(oauth, session, redirectFor(bundleId))
    private val auths = HashMap<String, MicrosoftAccountAuth>()
    private val lock = PlatformLock()

    val isConfigured: Boolean get() = signIn.isConfigured

    suspend fun signInNew(): Result = result(signIn.signIn())

    suspend fun signInAgain(account: Account): Result = result(signIn.signIn(loginHint = account.address))

    /** The sign-in's answer in Microsoft's words. Without an address in the id_token, Graph's `/me` says. */
    private suspend fun result(r: AppSignIn.Result): Result = when (r) {
        AppSignIn.Result.Cancelled -> Result.Cancelled
        is AppSignIn.Result.Failed -> Result.Failed(message(r.why))
        is AppSignIn.Result.Tokens -> {
            val t = r.tokens
            val refreshToken = t.refreshToken
            val address = t.email ?: me(t.accessToken)
            when {
                refreshToken == null -> Result.Failed(NO_REFRESH_TOKEN)
                address == null -> Result.Failed(NO_ADDRESS)
                else -> {
                    Log.i(LogTag.Accounts, "Microsoft sign-in: signed in")
                    Result.SignedIn(address, MicrosoftGrant(refreshToken, t.accessToken, clock.now() + t.expiresInSeconds.seconds))
                }
            }
        }
    }

    /** Keeps a sign-in's tokens for [accountId] (after its connection exists). */
    suspend fun keep(accountId: String, grant: MicrosoftGrant) {
        val auth = lock.synchronized { auths[accountId] }
        auth?.keep(grant) ?: Log.w(LogTag.Accounts, "Microsoft sign-in: no connection for $accountId")
    }

    /** The live connection for one Microsoft account. [signInAgain] runs the sign-in sheet for it and files the result. */
    fun connect(accountOf: () -> Account, signInAgain: suspend () -> Unit): AccountConnection {
        val id = accountOf().id
        val auth = MicrosoftAccountAuth(accountOf, oauth, secrets, clock) { signInAgain() }
        lock.synchronized { auths[id] = auth }
        val calendar = OutlookCalendarSource(
            accountOf,
            GraphClient(http, auth, dispatchers, "calendar", "Outlook Calendar", "That event isn't in your calendar any more.", graphBaseUrl),
            zone,
        )
        val mail = OutlookMailSource(
            accountOf,
            GraphClient(http, auth, dispatchers, "email", "Outlook", "That email isn't there any more.", graphBaseUrl),
            zone,
        )
        return IosMicrosoftConnection(auth, calendar, mail) { lock.synchronized { auths.remove(id) } }
    }

    /** The address from Graph's `/me`: `mail`, else `userPrincipalName`. Null if it can't be read. */
    private suspend fun me(accessToken: String): String? = withContext(dispatchers.io) {
        val url = URLBuilder(graphBaseUrl.trimEnd('/')).apply {
            appendPathSegments("me")
            parameters.append("\$select", "mail,userPrincipalName")
        }.build()
        try {
            val response = http.value.get(url) { header(HttpHeaders.Authorization, "Bearer $accessToken") }
            if (!response.status.isSuccess()) {
                Log.w(LogTag.Accounts, "Microsoft sign-in: /me answered HTTP ${response.status.value}")
                return@withContext null
            }
            val me = json.decodeFromString<MeDto>(response.bodyAsText())
            me.mail?.takeIf { it.isNotBlank() } ?: me.userPrincipalName?.takeIf { it.isNotBlank() }
        } catch (e: IOException) {
            Log.w(LogTag.Accounts, "Microsoft sign-in: /me didn't answer (${e::class.simpleName})")
            null
        } catch (e: SerializationException) {
            null
        }
    }

    private fun message(why: AppSignIn.Why): String = when (why) {
        AppSignIn.Why.NotSetUp -> NOT_SET_UP
        AppSignIn.Why.Declined -> "You didn't allow access, so the account isn't connected."
        AppSignIn.Why.AdminApproval -> ADMIN_APPROVAL
        AppSignIn.Why.WrongAddress -> "That answer isn't from this sign-in. Sign in again."
        is AppSignIn.Why.RedirectError -> "Microsoft didn't sign you in (${why.error}). Try again."
        AppSignIn.Why.CodeExpired -> "Microsoft says that sign-in expired or was already used. Sign in again."
        AppSignIn.Why.BadClient -> MicrosoftAccountAuth.BAD_CLIENT
        AppSignIn.Why.NoNetwork -> MicrosoftAccountAuth.NO_NETWORK
        is AppSignIn.Why.ExchangeFailed -> "Microsoft refused the sign-in (${why.reason}). Try again."
        is AppSignIn.Why.SheetFailed -> "Edith couldn't show Microsoft's sign-in page. Try again."
    }

    @Serializable private class MeDto(val mail: String? = null, val userPrincipalName: String? = null)

    companion object {
        const val NOT_SET_UP = "Microsoft isn't set up in this build: the client ID is missing (docs/microsoft-setup.md)."
        const val ADMIN_APPROVAL = "Your organisation's IT admin has to allow Edith before you can add this account."
        const val NO_REFRESH_TOKEN = "Microsoft didn't let Edith stay signed in. Sign in again."
        const val NO_ADDRESS = "Microsoft didn't say which account signed in. Sign in again."
        private val json = Json { ignoreUnknownKeys = true }

        /** Microsoft's redirect for an iOS app: `msauth.<bundle id>://auth`. Empty when the bundle id is unknown. */
        fun redirectFor(bundleId: String): String = if (bundleId.isBlank()) "" else "msauth.$bundleId://auth"
    }
}

/** One Microsoft account on the iPhone: its access and its Outlook calendar and mail. */
private class IosMicrosoftConnection(
    private val auth: MicrosoftAccountAuth,
    override val calendar: com.edith.app.accounts.sources.CalendarSource,
    override val mail: com.edith.app.accounts.sources.MailSource,
    private val dropped: () -> Unit,
) : AccountConnection {
    override val state: StateFlow<AccountState> get() = auth.state
    override val capabilities = Capabilities(calendar = true, mail = true)

    override suspend fun start() = auth.check()

    /** Sign in again / Try again; Microsoft has no consent screen to show. */
    override suspend fun grant(activity: PlatformActivity, show: (PlatformPendingIntent) -> Unit) = auth.grant(activity)

    override suspend fun onConsentResult(data: PlatformIntent?) = Unit

    /** Deletes the token on the phone; Microsoft can't be told, so Remove Edith at Microsoft's own page. */
    override suspend fun disconnect() {
        auth.forget()
        dropped()
    }
}
