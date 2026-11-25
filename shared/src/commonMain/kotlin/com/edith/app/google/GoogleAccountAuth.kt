package com.edith.app.google

import com.edith.app.accounts.PlatformActivity
import com.edith.app.accounts.PlatformPendingIntent
import com.edith.app.accounts.PlatformIntent
import com.edith.app.accounts.Account
import com.edith.app.accounts.AccountState
import com.edith.app.accounts.tasks.TasksAccess
import com.edith.app.accounts.sources.AccountErrors
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.google.browser.BrowserGrant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Signing in (who): Android's Credential Manager with "Sign in with Google". A fake in tests. */
interface GoogleSignIn {
    sealed interface Result {
        /** [grant]: the browser path's tokens (C17b), for the account [email] turns out to be (C22). */
        data class SignedIn(val email: String, val name: String?, val grant: BrowserGrant? = null) : Result
        data object Cancelled : Result
        data class Failed(val message: String) : Result
    }
    /** Shows Google's sign-in sheet over [activity]. */
    suspend fun signIn(activity: PlatformActivity, webClientId: String): Result
    suspend fun signOut()
}

/**
 * Access (what) for one account: Google's `AuthorizationClient`, which keeps the tokens itself, or, without Play
 * services, the browser path (C17b), which keeps a refresh token encrypted (D16). A fake in tests.
 */
interface GoogleAuthorizer {
    sealed interface Result {
        data class Granted(val accessToken: String) : Result
        /** The owner must approve; [intent] shows Google's consent screen (null on the browser path and in tests). */
        data class NeedsConsent(val intent: PlatformPendingIntent?) : Result
        data class Failed(val message: String) : Result
    }
    suspend fun authorize(scopes: Set<GoogleScope>): Result
    /** What the consent screen returned. */
    fun fromConsent(data: PlatformIntent?): Result
    /** Forgets a token Google refused, so the next [authorize] gets a fresh one. */
    suspend fun clearToken(token: String)
    /** Removes Edith's access to [email]'s account (all scopes). */
    suspend fun revoke(email: String)
}

/**
 * One Google account's access (C17, C17b; per account since C22). With Google Play services, tokens come from Play
 * services for this account on every call and the app stores none. Without it (D16) the account signs in through the
 * browser and keeps a refresh token, encrypted in `SecretStore`; access tokens stay in memory. [state] says whether
 * Edith can use it; tools use only Connected accounts (L6).
 */
class GoogleAccountAuth(
    private val account: () -> Account,
    private val authorizer: GoogleAuthorizer,
    /** Whether this account reaches Google through the browser now (C17b), so Grant access means signing in again. */
    private val usesBrowser: suspend () -> Boolean,
    /** Signs this account in again through the browser (Grant access on the browser path). */
    private val signInAgain: suspend (PlatformActivity) -> Unit,
) : GoogleAuth {
    private val _state = MutableStateFlow<AccountState>(AccountState.Checking)
    val state: StateFlow<AccountState> = _state.asStateFlow()

    private val _tasks = MutableStateFlow(TasksAccess.Unknown)
    /** C35 §2: whether Edith may use this account's to-dos, apart from [state], so calendar and email keep working. */
    val tasks: StateFlow<TasksAccess> = _tasks.asStateFlow()

    /** The consent screen for the Tasks scope alone (Play services), while [tasks] is NeedsPermission. */
    @kotlin.concurrent.Volatile private var tasksConsentIntent: PlatformPendingIntent? = null
    /** What the consent screen now showing is for: calendar and email, every scope (Tasks too), or Tasks alone. */
    @kotlin.concurrent.Volatile private var consentFor: ConsentFor = ConsentFor.Core
    private enum class ConsentFor { Core, All, Tasks }

    /** The consent screen to show, while [state] is NeedsSignIn on the Play services path. */
    @kotlin.concurrent.Volatile var consentIntent: PlatformPendingIntent? = null
        private set

    private val lock = Mutex()

    private val id: String get() = account().id

    /**
     * Asks for calendar's and email's scopes, quietly (no screens), and sets [state] from the answer. Tasks isn't
     * checked here (C35 trap 2); [tasks] goes back to Unknown, so the next Tasks call finds out again (after a new
     * sign-in, say). When the owner must approve anyway, the consent screen covers Tasks too (C35 §2: new sign-ins
     * ask for it along with the others).
     */
    suspend fun check() = lock.withLock {
        _state.value = AccountState.Checking
        _tasks.value = TasksAccess.Unknown
        tasksConsentIntent = null
        _state.value = when (val r = authorizer.authorize(GoogleScope.CORE)) {
            is GoogleAuthorizer.Result.Granted -> AccountState.Connected
            is GoogleAuthorizer.Result.NeedsConsent -> { consentIntent = consentForAll(r.intent); needsSignIn() }
            is GoogleAuthorizer.Result.Failed -> AccountState.Error(r.message)
        }
        Log.d(LogTag.Google, "Account $id: ${stateName(_state.value)}")   // not the class name: R8 renames it in release
    }

    /**
     * Grant access / Sign in again / Try again. Through the browser (C17b): check quietly first (Try again after no
     * network), and if Google still wants the owner, sign in through the browser again. With Play services: show
     * Google's consent screen; if Edith has none in hand (a cancelled consent used it up, or an error), she asks Google
     * again first and shows the screen it returns, so one tap is enough.
     */
    suspend fun grant(activity: PlatformActivity, show: (PlatformPendingIntent) -> Unit) {
        if (consentIntent == null && usesBrowser()) {
            check()
            if (_state.value is AccountState.NeedsSignIn) signInAgain(activity)
            return
        }
        val intent = consentIntent ?: run { check(); consentIntent }
        intent?.let(show)
    }

    /**
     * **To-dos: Grant access** (C35 §2). Through the browser: sign in again, which asks for every scope with
     * `include_granted_scopes`. With Play services: Google's consent screen for the Tasks scope alone; calendar and
     * email aren't touched.
     */
    suspend fun grantTasks(activity: PlatformActivity, show: (PlatformPendingIntent) -> Unit) {
        if (tasksConsentIntent == null && usesBrowser()) {
            signInAgain(activity)
            return
        }
        val intent = tasksConsentIntent ?: when (val r = authorizer.authorize(setOf(GoogleScope.Tasks))) {
            is GoogleAuthorizer.Result.Granted -> { _tasks.value = TasksAccess.Granted; return }
            is GoogleAuthorizer.Result.NeedsConsent -> r.intent
            is GoogleAuthorizer.Result.Failed -> { Log.w(LogTag.Google, "Account $id: couldn't ask for Tasks (${r.message})"); null }
        } ?: return
        consentFor = ConsentFor.Tasks
        show(intent)
    }

    /** Google's consent screen returned. */
    suspend fun onConsentResult(data: PlatformIntent?) = lock.withLock {
        if (consentFor == ConsentFor.Tasks) {
            consentFor = ConsentFor.Core
            tasksConsentIntent = null
            _tasks.value = when (val r = authorizer.fromConsent(data)) {
                is GoogleAuthorizer.Result.Granted -> TasksAccess.Granted.also { Log.i(LogTag.Google, "Account $id: Tasks access granted") }
                is GoogleAuthorizer.Result.NeedsConsent -> { tasksConsentIntent = r.intent; TasksAccess.NeedsPermission }
                is GoogleAuthorizer.Result.Failed -> TasksAccess.NeedsPermission.also { Log.w(LogTag.Google, "Account $id: Tasks consent not given (${r.message})") }
            }
            return@withLock
        }
        val coversTasks = consentFor == ConsentFor.All
        consentFor = ConsentFor.Core
        consentIntent = null
        _state.value = when (val r = authorizer.fromConsent(data)) {
            is GoogleAuthorizer.Result.Granted -> AccountState.Connected.also {
                if (coversTasks) _tasks.value = TasksAccess.Granted
                Log.i(LogTag.Google, "Account $id: access granted")
            }
            is GoogleAuthorizer.Result.NeedsConsent -> { consentIntent = r.intent; needsSignIn() }
            is GoogleAuthorizer.Result.Failed -> needsSignIn().also { Log.w(LogTag.Google, "Account $id: consent not given (${r.message})") }
        }
    }

    /** Disconnect: removes Edith's access to this account at Google (a failure is only logged). */
    suspend fun revoke() = lock.withLock {
        try {
            authorizer.revoke(account().address)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LogTag.Google, "Account $id: couldn't revoke access", e)
        }
        consentIntent = null
    }

    /**
     * Runs [call] with a fresh access token for [scopes] (C18, C19). A 401 clears the token and tries once more; a
     * second 401 means access was withdrawn, so [state] becomes NeedsSignIn.
     */
    override suspend fun <T> authorized(scopes: Set<GoogleScope>, call: suspend (token: String) -> GoogleCall<T>): Outcome<T> {
        repeat(2) { attempt ->
            val token = when (val t = accessToken(scopes)) {
                is Outcome.Success -> t.value
                is Outcome.Failure -> return t
            }
            when (val r = call(token)) {
                is GoogleCall.Ok -> return Outcome.Success(r.value)
                is GoogleCall.Failed -> return Outcome.Failure(r.error)
                GoogleCall.MissingScope -> {
                    Log.w(LogTag.Google, "Account $id: Google says Edith lacks a scope for this call")
                    _state.value = needsSignIn()
                    return Outcome.Failure(AppError.GoogleAuth(AccountErrors.NEEDS_PERMISSION))
                }
                GoogleCall.Unauthorized -> {
                    Log.w(LogTag.Google, "Account $id: Google refused the token (attempt ${attempt + 1})")
                    authorizer.clearToken(token)
                }
            }
        }
        _state.value = needsSignIn()
        return Outcome.Failure(AppError.GoogleAuth(AccountErrors.NEEDS_PERMISSION))
    }

    /**
     * C35: runs a Google Tasks [call] with a token for the Tasks scope. Like [authorized], but a missing Tasks scope
     * only makes [tasks] NeedsPermission: [state] (calendar and email) stays as it is. On the browser path the refresh
     * response's `scope` already says whether Tasks was granted, so no Tasks request is made without it.
     */
    override suspend fun <T> tasksAuthorized(call: suspend (token: String) -> GoogleCall<T>): Outcome<T> {
        val scopes = setOf(GoogleScope.Tasks)
        repeat(2) { attempt ->
            val token = when (val r = authorizer.authorize(scopes)) {
                is GoogleAuthorizer.Result.Granted -> r.accessToken
                is GoogleAuthorizer.Result.NeedsConsent -> return tasksNeedPermission(r.intent)
                is GoogleAuthorizer.Result.Failed -> return Outcome.Failure(AppError.GoogleAuth(r.message))
            }
            when (val r = call(token)) {
                is GoogleCall.Ok -> {
                    if (_tasks.value != TasksAccess.Granted) _tasks.value = TasksAccess.Granted
                    return Outcome.Success(r.value)
                }
                is GoogleCall.Failed -> return Outcome.Failure(r.error)
                GoogleCall.MissingScope -> return tasksNeedPermission(null)
                GoogleCall.Unauthorized -> {
                    Log.w(LogTag.Google, "Account $id: Google refused the Tasks token (attempt ${attempt + 1})")
                    authorizer.clearToken(token)
                }
            }
        }
        _state.value = needsSignIn()
        return Outcome.Failure(AppError.GoogleAuth(AccountErrors.NEEDS_PERMISSION))
    }

    private fun tasksNeedPermission(intent: PlatformPendingIntent?): Outcome<Nothing> {
        if (_tasks.value != TasksAccess.NeedsPermission) Log.i(LogTag.Google, "Account $id: to-dos need permission")
        tasksConsentIntent = intent
        _tasks.value = TasksAccess.NeedsPermission
        return Outcome.Failure(AppError.GoogleAuth(AccountErrors.TASKS_NEED_PERMISSION))
    }

    /**
     * The consent screen to keep when calendar and email need approval anyway: one for every scope, Tasks included,
     * so the owner approves once (C35 §2). Falls back to [coreIntent] if Google gives none.
     */
    private suspend fun consentForAll(coreIntent: PlatformPendingIntent?): PlatformPendingIntent? {
        if (coreIntent == null) return null   // the browser path: Sign in again asks for every scope already
        val all = (authorizer.authorize(GoogleScope.entries.toSet()) as? GoogleAuthorizer.Result.NeedsConsent)?.intent
        consentFor = if (all != null) ConsentFor.All else ConsentFor.Core
        return all ?: coreIntent
    }

    /** A token for [scopes], silently if access is granted. Never logged; only its length is. */
    suspend fun accessToken(scopes: Set<GoogleScope>): Outcome<String> = when (val r = authorizer.authorize(scopes)) {
        is GoogleAuthorizer.Result.Granted -> {
            if (_state.value !is AccountState.Connected) _state.value = AccountState.Connected
            Log.d(LogTag.Google, "Account $id: access token for ${scopes.size} scope(s): ${r.accessToken.length} characters")
            Outcome.Success(r.accessToken)
        }
        is GoogleAuthorizer.Result.NeedsConsent -> {
            consentIntent = r.intent
            _state.value = needsSignIn()
            Outcome.Failure(AppError.GoogleAuth(AccountErrors.NEEDS_PERMISSION))
        }
        is GoogleAuthorizer.Result.Failed -> Outcome.Failure(AppError.GoogleAuth(r.message))
    }

    private suspend fun needsSignIn(): AccountState.NeedsSignIn =
        if (consentIntent == null && usesBrowser()) AccountState.NeedsSignIn(SIGN_IN_AGAIN, viaBrowser = true)
        else AccountState.NeedsSignIn(NEEDS_PERMISSION_REASON)

    companion object {
        fun stateName(s: AccountState): String = when (s) {
            AccountState.Checking -> "checking"
            AccountState.Connected -> "connected"
            is AccountState.NeedsSignIn -> "needs signing in"
            is AccountState.Error -> "error"
        }

        const val SIGN_IN_AGAIN = "needs signing in again (about once a week while the Google project is in Testing)"
        const val NEEDS_PERMISSION_REASON = "needs your permission"
    }
}
