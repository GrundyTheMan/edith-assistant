package com.edith.app.microsoft

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome

/** One Graph call made with an access token: its result, or how the token was refused. */
sealed interface GraphCall<out T> {
    data class Ok<T>(val value: T) : GraphCall<T>
    /** HTTP 401: the token was refused. */
    data object Unauthorized : GraphCall<Nothing>
    /** HTTP 403: Edith lacks a permission for this (C23 §4). */
    data object Forbidden : GraphCall<Nothing>
    data class Failed(val error: AppError) : GraphCall<Nothing>
}

/**
 * What [GraphClient] needs from an account's sign-in (K2.6c): a token for each call. Android's `MicrosoftAccountAuth` implements it (refresh
 * token in the secret store, one refresh at a time, sign-in again through the browser); the iPhone's will when sign-in moves.
 */
interface GraphAuth {
    /**
     * Runs [call] with a valid token. A 401 gets one fresh token and one more try; a 403 or a second 401 asks the owner to sign in again (the
     * account's state says so) and gives an error.
     */
    suspend fun <T> authorized(call: suspend (token: String) -> GraphCall<T>): Outcome<T>
}
