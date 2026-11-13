package com.edith.app.testing

import android.app.Activity
import android.content.Intent
import com.edith.app.accounts.Account
import com.edith.app.accounts.AccountStore
import com.edith.app.accounts.Accounts
import com.edith.app.google.GoogleAccounts
import com.edith.app.google.GoogleAuthorizer
import com.edith.app.google.GoogleScope
import com.edith.app.google.GoogleSignIn
import com.edith.app.google.browser.BrowserGoogle
import com.edith.app.google.browser.BrowserOAuth
import com.edith.app.google.browser.BrowserSignIn
import com.edith.app.microsoft.MicrosoftAccounts
import com.edith.app.microsoft.MicrosoftOAuth
import com.edith.app.core.secrets.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.time.ZoneId

/** [AccountStore] for JVM tests. */
class InMemoryAccountStore(initial: List<Account> = emptyList()) : AccountStore {
    private val state = MutableStateFlow(initial)
    override val accounts: StateFlow<List<Account>> = state
    override suspend fun update(transform: (List<Account>) -> List<Account>) {
        state.value = transform(state.value)
    }
}

/** Play services' "Sign in with Google" sheet: answers [result], and counts. */
class FakeGoogleSignIn(var result: GoogleSignIn.Result = GoogleSignIn.Result.SignedIn("owner@gmail.com", "Owner")) : GoogleSignIn {
    var signIns = 0
    var signOuts = 0
    override suspend fun signIn(activity: Activity, webClientId: String): GoogleSignIn.Result { signIns++; return result }
    override suspend fun signOut() { signOuts++ }
}

/**
 * Play services' access, for every account: [answers] are used first (in order), then [default]. Records which
 * account asked (C22: `setAccount`), what was cleared and revoked.
 */
class FakePlay {
    val answers = ArrayDeque<GoogleAuthorizer.Result>()
    var default: GoogleAuthorizer.Result = GoogleAuthorizer.Result.Granted("ya29.secret-token")
    /** An account's own answer once [answers] are used up (so tests can tell accounts' requests apart). */
    val perAccount = mutableMapOf<String, GoogleAuthorizer.Result>()
    var consent: GoogleAuthorizer.Result = GoogleAuthorizer.Result.Granted("ya29.after-consent")
    val asked = mutableListOf<Pair<String, Set<GoogleScope>>>()
    val cleared = mutableListOf<String>()
    val revoked = mutableListOf<String>()

    fun authorizer(email: String): GoogleAuthorizer = object : GoogleAuthorizer {
        override suspend fun authorize(scopes: Set<GoogleScope>): GoogleAuthorizer.Result {
            asked += email to scopes
            return answers.removeFirstOrNull() ?: perAccount[email] ?: default
        }
        override fun fromConsent(data: Intent?) = consent
        override suspend fun clearToken(token: String) { cleared += token }
        override suspend fun revoke(email: String) { revoked += email }
    }
}

/**
 * [Accounts] with Google wired as in the app, but Play services faked and the browser path, Calendar and Gmail
 * pointed at [baseUrl] (a MockWebServer: `/token`, `/revoke`, `/calendar/v3`, `/gmail/v1/users/me`). Microsoft (C23) signs in
 * at `/ms/common/oauth2/v2.0/…` and reads Graph at `/graph/v1.0`.
 */
class AccountsHarness(
    val baseUrl: String = "http://127.0.0.1:9/",
    val secrets: SecretStore = InMemorySecretStore(),
    val store: InMemoryAccountStore = InMemoryAccountStore(),
    web: String = "web.apps.googleusercontent.com",
    desktop: String = "desktop.apps.googleusercontent.com",
    var playAvailable: Boolean = true,
    microsoftClientId: String = "ms-client-id",
    zone: ZoneId = ZoneId.of("America/Detroit"),
    clock: com.edith.app.core.time.Clock = FakeClock(java.time.Instant.parse("2026-11-02T18:00:00Z"), zone),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val signIn = FakeGoogleSignIn()
    val play = FakePlay()
    val opened = mutableListOf<String>()
    private val dispatchers = testDispatchers().copy(io = Dispatchers.IO)
    private fun url(path: String) = baseUrl.trimEnd('/') + path

    /** The app-wide browser sign-in, shared by Google and Microsoft as in the app. */
    val signInFlow = BrowserSignIn(
        secrets, FakeClock(), dispatchers, "com.edith.app.debug://signed-in",
        openBrowser = { _, u -> synchronized(opened) { opened += u } },
    )

    val browser = BrowserGoogle(
        BrowserOAuth(lazy { OkHttpClient() }, dispatchers, desktop, tokenUrl = url("/token"), revokeUrl = url("/revoke")),
        secrets, FakeClock(), dispatchers, "com.edith.app.debug://signed-in",
        signInFlow = signInFlow,
    )

    val google = GoogleAccounts(
        play = signIn,
        playAuthorizer = play::authorizer,
        browser = browser,
        webClientId = web,
        playServicesAvailable = { playAvailable },
        http = lazy { OkHttpClient() },
        dispatchers = AppDispatchersForTests.unconfined,
        zone = { zone },
        calendarBaseUrl = url("/calendar/v3"),
        gmailBaseUrl = url("/gmail/v1/users/me"),
        clock = clock,
    )

    val microsoft = MicrosoftAccounts(
        oauth = BrowserOAuth(lazy { OkHttpClient() }, dispatchers, MicrosoftOAuth.provider(microsoftClientId, loginBase = url("/ms"))),
        signInFlow = signInFlow,
        secrets = secrets,
        // The app reads for up to 180 s (AppContainer), so its 10 s per-account timeout always wins; OkHttp's default
        // 10 s would race it.
        http = lazy { OkHttpClient.Builder().readTimeout(180, java.util.concurrent.TimeUnit.SECONDS).build() },
        dispatchers = AppDispatchersForTests.unconfined,
        clock = clock,
        zone = { zone },
        windowsZone = { z -> if (z.id == "America/Detroit") "Eastern Standard Time" else null },
        graphBaseUrl = url("/graph/v1.0"),
    )

    val accounts = Accounts(store, google, scope, microsoft)
}

/** Dispatchers that run everything in place (MockWebServer calls block the test thread, which is fine in JVM tests). */
object AppDispatchersForTests {
    val unconfined = com.edith.app.core.dispatchers.AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)
}

/**
 * A Preferences DataStore in memory. On Windows a JVM test can't replace a DataStore file a second time (Java's rename
 * won't overwrite there, see SettingsRepositoryTest), so tests that write more than once use this.
 */
class MemoryPreferences : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
    private val state = MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())
    private val lock = kotlinx.coroutines.sync.Mutex()
    override val data: kotlinx.coroutines.flow.Flow<androidx.datastore.preferences.core.Preferences> = state
    override suspend fun updateData(
        transform: suspend (t: androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences,
    ): androidx.datastore.preferences.core.Preferences = lock.withLock { transform(state.value).also { state.value = it } }
}
