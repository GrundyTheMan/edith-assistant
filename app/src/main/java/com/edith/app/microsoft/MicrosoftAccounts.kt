package com.edith.app.microsoft

import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.TimeZone
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
import com.edith.app.core.secrets.SecretStore
import com.edith.app.core.time.Clock
import com.edith.app.google.browser.BrowserOAuth
import com.edith.app.google.browser.BrowserSignIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Microsoft as an account provider (C23, D17, D18): Outlook.com and Microsoft 365 accounts, signed in through the
 * browser with the app-wide [BrowserSignIn], each with its own [MicrosoftConnection].
 */
class MicrosoftAccounts(
    val oauth: BrowserOAuth,
    private val signInFlow: BrowserSignIn,
    private val secrets: SecretStore,
    private val http: Lazy<OkHttpClient>,
    private val dispatchers: AppDispatchers,
    private val clock: Clock,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    /** The Windows name of a zone (Android's ICU); Graph knows only some IANA names (§2). */
    private val windowsZone: (TimeZone) -> String? = { null },
    private val graphBaseUrl: String = GraphClient.BASE_URL,
) {
    /** How a Microsoft sign-in ended. */
    sealed interface Result {
        data class SignedIn(val address: String, val grant: MicrosoftGrant) : Result
        data object Cancelled : Result
        data class Failed(val message: String) : Result
    }

    private val auths = ConcurrentHashMap<String, MicrosoftAccountAuth>()

    /** This build has Edith's Microsoft client ID (`edith.microsoftClientId`, docs/microsoft-setup.md). */
    val isConfigured: Boolean get() = oauth.isConfigured

    /** Add account → Microsoft: Microsoft's page with its account picker. */
    suspend fun signInNew(activity: Activity): Result =
        if (!isConfigured) Result.Failed(NOT_SET_UP) else result(signInFlow.signIn(activity, oauth))

    /** Sign in again, suggesting [account]'s address. */
    suspend fun signInAgain(activity: Activity, account: Account): Result =
        if (!isConfigured) Result.Failed(NOT_SET_UP) else result(signInFlow.signIn(activity, oauth, loginHint = account.address, accountId = account.id))

    /** The shared flow's answer in Microsoft's words. Without an address in the id_token, Graph's `/me` says. */
    suspend fun result(r: BrowserSignIn.Result): Result = when (r) {
        BrowserSignIn.Result.Cancelled -> Result.Cancelled
        is BrowserSignIn.Result.Failed -> Result.Failed(message(r.why))
        is BrowserSignIn.Result.Tokens -> {
            val t = r.tokens
            val refreshToken = t.refreshToken
            val address = t.email ?: me(t.accessToken)
            when {
                refreshToken == null -> Result.Failed(NO_REFRESH_TOKEN)
                address == null -> Result.Failed(NO_ADDRESS)
                else -> {
                    Log.i(LogTag.Accounts, "Microsoft sign-in: signed in")
                    Result.SignedIn(address, MicrosoftGrant(refreshToken, t.accessToken, (clock.now() + t.expiresInSeconds.seconds)))
                }
            }
        }
    }

    /** Keeps a sign-in's tokens for [accountId] (after its connection exists). */
    suspend fun keep(accountId: String, grant: MicrosoftGrant) {
        auths[accountId]?.keep(grant) ?: Log.w(LogTag.Accounts, "Microsoft sign-in: no connection for $accountId")
    }

    /** The live connection for one Microsoft account. [signInAgain] runs the browser sign-in for it and files the result. */
    fun connect(accountOf: () -> Account, signInAgain: suspend (Activity) -> Unit): MicrosoftConnection {
        val id = accountOf().id
        val auth = MicrosoftAccountAuth(accountOf, oauth, secrets, clock, signInAgain)
        auths[id] = auth
        val calendar = OutlookCalendarSource(
            accountOf,
            GraphClient(http, auth, dispatchers, "calendar", "Outlook Calendar", "That event isn't in your calendar any more.", graphBaseUrl),
            zone, windowsZone,
        )
        val mail = OutlookMailSource(
            accountOf,
            GraphClient(http, auth, dispatchers, "email", "Outlook", "That email isn't there any more.", graphBaseUrl),
            zone,
        )
        return MicrosoftConnection(auth, calendar, mail) { auths.remove(id) }
    }

    /** The address from Graph's `/me` (§1): `mail`, else `userPrincipalName`. Null if it can't be read. */
    private suspend fun me(accessToken: String): String? = withContext(dispatchers.io) {
        val request = Request.Builder()
            .url(graphBaseUrl.trimEnd('/').toHttpUrl().newBuilder().addPathSegment("me").addQueryParameter("\$select", "mail,userPrincipalName").build())
            .header("Authorization", "Bearer $accessToken")
            .build()
        try {
            http.value.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(LogTag.Accounts, "Microsoft sign-in: /me answered HTTP ${response.code}")
                    return@withContext null
                }
                val me = json.decodeFromString<MeDto>(response.body.string())
                me.mail?.takeIf { it.isNotBlank() } ?: me.userPrincipalName?.takeIf { it.isNotBlank() }
            }
        } catch (e: IOException) {
            Log.w(LogTag.Accounts, "Microsoft sign-in: /me didn't answer (${e::class.simpleName})")
            null
        } catch (e: SerializationException) {
            null
        }
    }

    private fun message(why: BrowserSignIn.Why): String = when (why) {
        BrowserSignIn.Why.NotSetUp -> NOT_SET_UP
        BrowserSignIn.Why.NoListener -> "Edith couldn't get ready for Microsoft's answer. Try again."
        BrowserSignIn.Why.NoBrowser -> "There's no web browser on this device to sign in with."
        BrowserSignIn.Why.Declined -> "You didn't allow access, so the account isn't connected."
        BrowserSignIn.Why.AdminApproval -> ADMIN_APPROVAL
        BrowserSignIn.Why.WrongAddress -> "That address isn't from this sign-in (it may be from an older one). Sign in again."
        BrowserSignIn.Why.TimedOut -> "Edith stopped waiting for the browser after a few minutes. Sign in again."
        is BrowserSignIn.Why.RedirectError -> "Microsoft didn't sign you in (${why.error}). Try again."
        BrowserSignIn.Why.CodeExpired -> "Microsoft says that sign-in expired or was already used. Sign in again."
        BrowserSignIn.Why.BadClient -> MicrosoftAccountAuth.BAD_CLIENT
        BrowserSignIn.Why.NoNetwork -> MicrosoftAccountAuth.NO_NETWORK
        is BrowserSignIn.Why.ExchangeFailed -> "Microsoft refused the sign-in (${why.reason}). Try again."
    }

    @Serializable private class MeDto(val mail: String? = null, val userPrincipalName: String? = null)

    companion object {
        const val NOT_SET_UP = "Not set up (see docs/microsoft-setup.md)."
        const val ADMIN_APPROVAL = "Your organisation's IT admin has to allow Edith before you can add this account."
        const val NO_REFRESH_TOKEN = "Microsoft didn't let Edith stay signed in. Sign in again."
        const val NO_ADDRESS = "Microsoft didn't say which account signed in. Sign in again."
        /** Where to remove Edith at Microsoft (§1): Microsoft has no way for an app to revoke itself. */
        const val PERSONAL_PERMISSIONS = "https://account.live.com/consent/Manage"
        const val WORK_PERMISSIONS = "https://myapplications.microsoft.com"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** One Microsoft account: its access and its Outlook calendar and mail (C23 §1–3). */
class MicrosoftConnection(
    val auth: MicrosoftAccountAuth,
    override val calendar: CalendarSource,
    override val mail: MailSource,
    private val dropped: () -> Unit,
) : AccountConnection {
    override val state: StateFlow<AccountState> get() = auth.state
    override val capabilities = Capabilities(calendar = true, mail = true)

    override suspend fun start() = auth.check()

    /** Sign in again / Try again; Microsoft has no consent screen to [show]. */
    override suspend fun grant(activity: Activity, show: (PendingIntent) -> Unit) = auth.grant(activity)

    override suspend fun onConsentResult(data: Intent?) = Unit

    /** Deletes the token on the phone; Microsoft can't be told (§1), so the dialog says where to remove Edith. */
    override suspend fun disconnect() {
        auth.forget()
        dropped()
    }
}
