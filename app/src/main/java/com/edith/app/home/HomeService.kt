package com.edith.app.home

import kotlinx.coroutines.flow.StateFlow

/** Where a smart-home service stands, for Settings → Home (C25 §7). */
enum class ServiceState {
    NotConnected,
    Connected,
    /** Signed in once, but the service no longer accepts Edith's saved sign-in: "Sign in again". */
    NeedsSignIn,
}

/** One box in a sign-in form. [onlyWhenAsked]: shown only after the service asked for it (a 2FA code). */
data class SignInField(
    val key: String,
    val label: String,
    val secret: Boolean = false,
    val email: Boolean = false,
    val onlyWhenAsked: Boolean = false,
    /** The owner may leave it empty (a printer's API key). */
    val optional: Boolean = false,
)

/** What the owner types to sign in to a service: the words and the boxes (the dialog is the same for every service). */
data class SignInForm(val title: String, val intro: String, val fields: List<SignInField>)

/** How a smart-home service's sign-in ended. */
sealed interface SignInResult {
    data object Done : SignInResult
    /** The service wants a code (2FA): the form shows its [SignInField.onlyWhenAsked] boxes, with this [message]. */
    data class NeedsCode(val message: String) : SignInResult
    data class Failed(val message: String) : SignInResult
}

/**
 * A smart-home service as Settings → Home shows it (C25 §7): its [backend], whether it's signed in, and how to sign in
 * and out. C26–C31 each provide one. Passwords are typed by the owner, used once and never kept (C20 §5).
 */
interface HomeService {
    val backend: HomeBackend
    val state: StateFlow<ServiceState>
    /** The boxes for Sign in / Sign in again. Null for a service that signs in some other way (the browser). */
    val form: SignInForm?
    /** Signs in with what the owner typed, keyed by [SignInField.key]. */
    suspend fun signIn(values: Map<String, String>): SignInResult
    /** Signs out at the service where it can, and forgets everything saved. */
    suspend fun disconnect()
    /** For a service that signs in on its own web page instead of a form (Alexa). Null for the others. */
    val pageSignIn: PageSignIn? get() = null
    /** Where the owner can also remove Edith at the service, or null. */
    val removeHint: String? get() = null
}

/**
 * The page a service signs in on, for a service with no form of its own (Alexa: Amazon's own page, C30). Edith opens
 * [url] in an in-app web view, which sets [cookies] first and watches for [isFinish]; or the owner uses the browser and
 * pastes the address it ends on. The password goes to the service's page, never through Edith's code.
 */
data class PageSignInStart(
    val url: String,
    /** The site whose cookies the web view hands back, e.g. `https://www.amazon.com`. */
    val cookieSite: String,
    /** Cookies the page expects before it loads, name to value. */
    val cookies: Map<String, String>,
    /** Whether an address is where the sign-in ends. */
    val isFinish: (String) -> Boolean,
)

interface PageSignIn {
    /** Whose page it is, as the owner knows it: "Amazon". */
    val siteName: String
    /** Starts a sign-in: a new one each time. */
    fun begin(): PageSignInStart
    /** Finishes with the address the page ended on and the web view's cookies for [PageSignInStart.cookieSite] (empty from a browser). */
    suspend fun finish(address: String, cookies: String): SignInResult
}
