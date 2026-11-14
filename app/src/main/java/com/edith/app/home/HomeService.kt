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
)

/** What the owner types to sign in to a service: the words and the boxes (the dialog is the same for every service). */
data class SignInForm(val title: String, val intro: String, val fields: List<SignInField>)

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
    /** Where the owner can also remove Edith at the service, or null. */
    val removeHint: String? get() = null
}
