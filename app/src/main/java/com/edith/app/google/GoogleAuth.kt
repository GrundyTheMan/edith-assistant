package com.edith.app.google

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.settings.SettingsRepository
import com.edith.app.tools.framework.Integration
import com.edith.app.tools.framework.IntegrationStatus
import com.edith.app.tools.framework.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What Edith may ask Google for (C17 §2). Each tool asks only for what it needs. */
enum class GoogleScope(val uri: String) {
    CalendarEvents("https://www.googleapis.com/auth/calendar.events"),
    GmailReadonly("https://www.googleapis.com/auth/gmail.readonly"),
    GmailCompose("https://www.googleapis.com/auth/gmail.compose"),
}

/** Signing in (who): Android's Credential Manager with "Sign in with Google". A fake in tests. */
interface GoogleSignIn {
    sealed interface Result {
        data class SignedIn(val email: String, val name: String?) : Result
        data object Cancelled : Result
        data class Failed(val message: String) : Result
    }
    /** Shows Google's sign-in sheet over [activity]. */
    suspend fun signIn(activity: Activity, webClientId: String): Result
    suspend fun signOut()
}

/** Access (what): Google's `AuthorizationClient`, which keeps the tokens (the app stores none). A fake in tests. */
interface GoogleAuthorizer {
    sealed interface Result {
        data class Granted(val accessToken: String) : Result
        /** The owner must approve; [intent] shows Google's consent screen (null only in tests). */
        data class NeedsConsent(val intent: PendingIntent?) : Result
        data class Failed(val message: String) : Result
    }
    suspend fun authorize(scopes: Set<GoogleScope>): Result
    /** What the consent screen returned. */
    fun fromConsent(data: Intent?): Result
    /** Forgets a token Google refused, so the next [authorize] gets a fresh one. */
    suspend fun clearToken(token: String)
    /** Removes Edith's access to [email]'s account (all scopes). */
    suspend fun revoke(email: String)
}

/** The account as Settings shows it (C17 §3). */
sealed interface GoogleState {
    /** No Web client ID in this build (docs/google-setup.md). */
    data object NotSetUp : GoogleState
    data object SignedOut : GoogleState
    data class Checking(val email: String) : GoogleState
    data class Connected(val email: String) : GoogleState
    /** Signed in, but Google wants the owner to approve access (first time, or again). */
    data class NeedsConsent(val email: String) : GoogleState
    data class Error(val email: String?, val message: String) : GoogleState
}

/** One Google API call made with an access token (C18, C19): its result, or "the token was refused". */
sealed interface GoogleCall<out T> {
    data class Ok<T>(val value: T) : GoogleCall<T>
    /** HTTP 401. */
    data object Unauthorized : GoogleCall<Nothing>
    data class Failed(val error: AppError) : GoogleCall<Nothing>
}

/**
 * The Google account (C17, decision D9). Sign-in keeps only the email (DataStore `google_account_email`); access
 * tokens come from Google Play services on every call and are never stored by the app. Google's tools (C18, C19)
 * are offered only while [status] is Connected (L6), and the model is told what she can actually do.
 */
class GoogleAuth(
    private val signIn: GoogleSignIn,
    private val authorizer: GoogleAuthorizer,
    private val settings: SettingsRepository,
    private val webClientId: String,
    scope: CoroutineScope,
) : Integration {
    override val id = ID

    private val _state = MutableStateFlow<GoogleState>(if (webClientId.isBlank()) GoogleState.NotSetUp else GoogleState.SignedOut)
    val state: StateFlow<GoogleState> = _state.asStateFlow()

    override val status: StateFlow<IntegrationStatus> = _state
        .map { if (it is GoogleState.Connected) IntegrationStatus.Connected else IntegrationStatus.NotConnected }
        .stateIn(scope, SharingStarted.Eagerly, IntegrationStatus.NotConnected)

    /** The consent screen to show, when [state] is NeedsConsent. */
    @Volatile var consentIntent: PendingIntent? = null
        private set

    private val lock = Mutex()

    override val notConnectedNote: String
        get() = "Google (calendar and email) isn't connected. If asked about the calendar or email, say the owner can connect it in Settings → Google account."

    override fun connectedNote(tools: List<Tool>): String {
        val email = (state.value as? GoogleState.Connected)?.email ?: ""
        val can = listOfNotNull(
            "calendar".takeIf { tools.any { it.spec.name.contains("calendar") } },
            "email".takeIf { tools.any { it.spec.name.contains("email") || it.spec.name.contains("gmail") } },
        )
        return if (can.isEmpty()) {
            "Google: connected as $email, but you have no calendar or email tools yet; if asked, say they're coming."
        } else {
            "Google: connected as $email (${can.joinToString(" and ")})."
        }
    }

    /** At startup: if an account is saved, check quietly whether access is still granted (no screens shown). */
    suspend fun start() {
        if (webClientId.isBlank()) return
        val email = settings.current().googleAccountEmail ?: return
        lock.withLock { check(email) }
    }

    /** Settings → Sign in with Google. Then asks for access; [state] says whether Google wants approval. */
    suspend fun signIn(activity: Activity) {
        if (webClientId.isBlank()) return
        when (val r = signIn.signIn(activity, webClientId)) {
            is GoogleSignIn.Result.SignedIn -> {
                settings.update { it.copy(googleAccountEmail = r.email) }
                Log.i(LogTag.Google, "Signed in to Google")
                lock.withLock { check(r.email) }
            }
            GoogleSignIn.Result.Cancelled -> Unit
            is GoogleSignIn.Result.Failed -> _state.value = GoogleState.Error(null, r.message)
        }
    }

    /** Google's consent screen returned. */
    suspend fun onConsentResult(data: Intent?) = lock.withLock {
        val email = currentEmail() ?: return@withLock
        consentIntent = null
        _state.value = when (val r = authorizer.fromConsent(data)) {
            is GoogleAuthorizer.Result.Granted -> GoogleState.Connected(email).also { Log.i(LogTag.Google, "Google access granted") }
            is GoogleAuthorizer.Result.NeedsConsent -> { consentIntent = r.intent; GoogleState.NeedsConsent(email) }
            is GoogleAuthorizer.Result.Failed -> GoogleState.NeedsConsent(email).also { Log.w(LogTag.Google, "Consent not given: ${r.message}") }
        }
    }

    /** Settings → Disconnect: removes Edith's access and forgets the account. */
    suspend fun disconnect() = lock.withLock {
        currentEmail()?.let { email -> runCatching { authorizer.revoke(email) }.onFailure { Log.w(LogTag.Google, "Couldn't revoke Google access", it) } }
        runCatching { signIn.signOut() }
        settings.update { it.copy(googleAccountEmail = null) }
        consentIntent = null
        _state.value = GoogleState.SignedOut
        Log.i(LogTag.Google, "Google disconnected")
    }

    /**
     * Runs [call] with a fresh access token for [scopes] (C18, C19). A 401 clears the token and tries once more;
     * a second 401 means access was withdrawn, so [state] becomes NeedsConsent.
     */
    suspend fun <T> authorized(scopes: Set<GoogleScope>, call: suspend (token: String) -> GoogleCall<T>): Outcome<T> {
        repeat(2) { attempt ->
            val token = when (val t = accessToken(scopes)) {
                is Outcome.Success -> t.value
                is Outcome.Failure -> return t
            }
            when (val r = call(token)) {
                is GoogleCall.Ok -> return Outcome.Success(r.value)
                is GoogleCall.Failed -> return Outcome.Failure(r.error)
                GoogleCall.Unauthorized -> {
                    Log.w(LogTag.Google, "Google refused the token (attempt ${attempt + 1})")
                    authorizer.clearToken(token)
                }
            }
        }
        val email = currentEmail()
        if (email != null) _state.value = GoogleState.NeedsConsent(email)
        return Outcome.Failure(AppError.GoogleAuth(NEEDS_PERMISSION))
    }

    /** A token for [scopes], silently if access is granted. Never logged; only its length is. */
    suspend fun accessToken(scopes: Set<GoogleScope>): Outcome<String> {
        val email = currentEmail() ?: return Outcome.Failure(AppError.GoogleNotConnected)
        return when (val r = authorizer.authorize(scopes)) {
            is GoogleAuthorizer.Result.Granted -> {
                if (_state.value !is GoogleState.Connected) _state.value = GoogleState.Connected(email)
                Log.d(LogTag.Google, "Access token for ${scopes.size} scope(s): ${r.accessToken.length} characters")
                Outcome.Success(r.accessToken)
            }
            is GoogleAuthorizer.Result.NeedsConsent -> {
                consentIntent = r.intent
                _state.value = GoogleState.NeedsConsent(email)
                Outcome.Failure(AppError.GoogleAuth(NEEDS_PERMISSION))
            }
            is GoogleAuthorizer.Result.Failed -> Outcome.Failure(AppError.GoogleAuth(r.message))
        }
    }

    /** Asks for every scope Edith uses, quietly, and sets [state] from the answer. */
    private suspend fun check(email: String) {
        _state.value = GoogleState.Checking(email)
        _state.value = when (val r = authorizer.authorize(GoogleScope.entries.toSet())) {
            is GoogleAuthorizer.Result.Granted -> GoogleState.Connected(email)
            is GoogleAuthorizer.Result.NeedsConsent -> { consentIntent = r.intent; GoogleState.NeedsConsent(email) }
            is GoogleAuthorizer.Result.Failed -> GoogleState.Error(email, r.message)
        }
        Log.d(LogTag.Google, "Google account: ${_state.value::class.simpleName}")
    }

    private suspend fun currentEmail(): String? = when (val s = _state.value) {
        is GoogleState.Connected -> s.email
        is GoogleState.NeedsConsent -> s.email
        is GoogleState.Checking -> s.email
        is GoogleState.Error -> s.email
        else -> settings.current().googleAccountEmail
    }

    companion object {
        const val ID = "google"
        const val NEEDS_PERMISSION = "Edith needs your permission again. Open Settings → Google account and tap Grant access."
    }
}
