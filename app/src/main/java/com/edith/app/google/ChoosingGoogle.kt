package com.edith.app.google

import android.app.Activity
import android.content.Intent
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.google.browser.BrowserGoogle
import com.edith.app.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/**
 * Chooses how Edith reaches Google (C17b §1): Play services (C17) where it exists, or the browser (D16) where it
 * doesn't, when the owner picked **Use a browser instead**, or once a browser sign-in has happened. The choice
 * survives restarts through the DataStore value `google_sign_in_mode`.
 */
class ChoosingGoogle(
    private val play: GoogleSignIn,
    private val playAuthorizer: GoogleAuthorizer,
    private val browser: BrowserGoogle,
    private val settings: SettingsRepository,
    /** Whether this build has the Web client ID Play services sign-in needs. */
    private val playConfigured: Boolean,
    /** `GoogleApiAvailability.isGooglePlayServicesAvailable(context) == SUCCESS`; a lambda so tests can choose. */
    private val playServicesAvailable: () -> Boolean,
) : GoogleSignIn, GoogleAuthorizer, GoogleBrowserFlow {

    override val browserConfigured: Boolean get() = browser.isConfigured
    override val waiting: StateFlow<Boolean> get() = browser.waiting

    override suspend fun usesBrowser(): Boolean = chooseBrowser(
        browserConfigured = browser.isConfigured,
        playConfigured = playConfigured,
        playAvailable = playServicesAvailable(),
        browserTokenStored = browser.hasRefreshToken(),
        lastSignInWasBrowser = settings.current().googleSignInMode == MODE_BROWSER,
    )

    override suspend fun signIn(activity: Activity, webClientId: String): GoogleSignIn.Result =
        if (usesBrowser()) signInWithBrowser(activity) else remember(MODE_PLAY, play.signIn(activity, webClientId))

    override suspend fun signInWithBrowser(activity: Activity): GoogleSignIn.Result =
        remember(MODE_BROWSER, browser.signIn(activity, ""))

    override suspend fun resumePending(): GoogleSignIn.Result? =
        browser.resumePending()?.let { remember(MODE_BROWSER, it) }

    override fun paste(address: String) = browser.paste(address)
    override fun cancel() = browser.cancel()

    override suspend fun signOut() {
        browser.signOut()
        if (playServicesAvailable()) {
            try {
                play.signOut()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LogTag.Google, "Couldn't clear Play services' sign-in", e)
            }
        }
    }

    override suspend fun authorize(scopes: Set<GoogleScope>): GoogleAuthorizer.Result =
        if (usesBrowser()) browser.authorize(scopes) else playAuthorizer.authorize(scopes)

    /** Only Play services hands out consent screens. */
    override fun fromConsent(data: Intent?): GoogleAuthorizer.Result = playAuthorizer.fromConsent(data)

    override suspend fun clearToken(token: String) =
        if (usesBrowser()) browser.clearToken(token) else playAuthorizer.clearToken(token)

    override suspend fun revoke(email: String) =
        if (usesBrowser()) browser.revoke(email) else playAuthorizer.revoke(email)

    private suspend fun remember(mode: String, result: GoogleSignIn.Result): GoogleSignIn.Result {
        if (result is GoogleSignIn.Result.SignedIn) settings.update { it.copy(googleSignInMode = mode) }
        return result
    }

    companion object {
        const val MODE_PLAY = "play"
        const val MODE_BROWSER = "browser"

        /** The choice rule in one place (C17b §1): Browser only when it's set up, and then when any reason says so. */
        fun chooseBrowser(
            browserConfigured: Boolean,
            playConfigured: Boolean,
            playAvailable: Boolean,
            browserTokenStored: Boolean,
            lastSignInWasBrowser: Boolean,
        ): Boolean = browserConfigured && (browserTokenStored || lastSignInWasBrowser || !playAvailable || !playConfigured)
    }
}
