package com.edith.app.accounts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.SecureRandom

/** Who runs an account's mail and calendar (D17). Microsoft is C23, iCloud C24. */
@Serializable
enum class Provider(val displayName: String) {
    @SerialName("google") Google("Google"),
    @SerialName("microsoft") Microsoft("Microsoft"),
    @SerialName("icloud") ICloud("iCloud"),
}

/**
 * One connected email-and-calendar account (C22 §1). [id] is random and stable: it goes in logs and secret keys, so
 * it's never the address. [label] is the owner's name for it ("work"); it starts as the address.
 */
@Serializable
data class Account(
    val id: String,
    val provider: Provider,
    val address: String,
    val label: String,
    /** Google only: how it signs in, [SIGN_IN_PLAY] or [SIGN_IN_BROWSER] (C17b's mode, now per account). */
    val signIn: String? = null,
) {
    /** "work (sam@contoso.com)", or just the address while the label is the address. */
    val display: String get() = if (label.equals(address, ignoreCase = true)) address else "$label ($address)"

    companion object {
        const val SIGN_IN_PLAY = "play"
        const val SIGN_IN_BROWSER = "browser"

        private val random = SecureRandom()

        /** A new random id ("a1b2c3") that isn't one of [taken]. */
        fun newId(taken: Collection<String> = emptyList()): String {
            while (true) {
                val id = (0 until 6).joinToString("") { "0123456789abcdef"[random.nextInt(16)].toString() }
                if (id !in taken) return id
            }
        }
    }
}

/** Where an account stands, as Settings → Accounts shows it. Tools use only Connected accounts' data. */
sealed interface AccountState {
    data object Checking : AccountState
    data object Connected : AccountState
    /**
     * The owner must sign in again or approve access. [reason] says why, for Settings and the prompt. [viaBrowser]:
     * the fix is a browser sign-in (**Sign in again**), not Google's consent screen (**Grant access**).
     */
    data class NeedsSignIn(val reason: String, val viaBrowser: Boolean = false) : AccountState
    data class Error(val message: String) : AccountState
}

/** What a provider's accounts can do (C22 §4: "only one connected account can do it"). */
data class Capabilities(val calendar: Boolean, val mail: Boolean) {
    /** "calendar and email", for the prompt line. */
    val text: String get() = listOfNotNull("calendar".takeIf { calendar }, "email".takeIf { mail }).joinToString(" and ")
}

/** Secret-store keys for one account (C22 §1): never the address. */
object AccountSecrets {
    fun refreshToken(id: String) = "account/$id/refresh_token"
    /** C24: iCloud's app-specific password. */
    fun password(id: String) = "account/$id/password"
}
