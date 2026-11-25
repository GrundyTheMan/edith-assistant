package com.edith.app.google

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome

/** What Edith may ask Google for (C17 §2). Each tool asks only for what it needs. */
enum class GoogleScope(val uri: String) {
    CalendarEvents("https://www.googleapis.com/auth/calendar.events"),
    /** C18: which calendars the owner has checked (the owner chose all of them, not only the primary). */
    CalendarList("https://www.googleapis.com/auth/calendar.calendarlist.readonly"),
    GmailReadonly("https://www.googleapis.com/auth/gmail.readonly"),
    GmailCompose("https://www.googleapis.com/auth/gmail.compose"),
    /** C35: Google Tasks, read and write. Asked for only when a Tasks call needs it (trap 2), never at startup. */
    Tasks("https://www.googleapis.com/auth/tasks"),
    ;

    companion object {
        /** What calendar and email need: every scope but [Tasks], so an account signed in before C35 stays Connected. */
        val CORE: Set<GoogleScope> = entries.toSet() - Tasks
    }
}

/** One Google API call made with an access token (C18, C19): its result, or "the token was refused". */
sealed interface GoogleCall<out T> {
    data class Ok<T>(val value: T) : GoogleCall<T>
    /** HTTP 401: the token was refused. */
    data object Unauthorized : GoogleCall<Nothing>
    /** HTTP 403 for a missing scope (`insufficientPermissions`): the owner must approve more access. */
    data object MissingScope : GoogleCall<Nothing>
    data class Failed(val error: AppError) : GoogleCall<Nothing>
}

/**
 * What the Google clients need from an account's sign-in (K2.6b): a token for each call. Android's `GoogleAccountAuth` implements it (Google
 * Play services' tokens, or the browser path's refresh token in the secret store); the iPhone's will when sign-in moves.
 */
interface GoogleAuth {
    /**
     * Runs [call] with a fresh access token for [scopes]. A 401 clears the token and tries once more; a second 401 means access was withdrawn,
     * so the account's state becomes "sign in again"; a missing scope does the same.
     */
    suspend fun <T> authorized(scopes: Set<GoogleScope>, call: suspend (token: String) -> GoogleCall<T>): Outcome<T>

    /**
     * The same for Google Tasks (C35 §2): a missing Tasks permission only turns the account's to-dos off, and calendar and email keep working.
     */
    suspend fun <T> tasksAuthorized(call: suspend (token: String) -> GoogleCall<T>): Outcome<T>
}
