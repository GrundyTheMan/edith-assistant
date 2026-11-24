package com.edith.app.accounts.sources

import com.edith.app.accounts.Account
import com.edith.app.accounts.AccountState
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** One account's source to read, with the account's state (an account that needs signing in isn't asked). */
data class Readable<S>(val account: Account, val state: AccountState, val source: S)

/** An account that couldn't be read, and why in plain words ("it needs signing in again"). */
data class ReadFailure(val account: Account, val why: String) {
    /** "Couldn't read work (sam@contoso.com): it needs signing in again — Settings → Accounts." */
    val line: String get() = "Couldn't read ${account.display}: $why${if (why.endsWith(".")) "" else "."}"
}

/** Every account's answer, in the accounts' order, and the ones that failed. */
data class ReadAll<T>(val results: List<Pair<Account, T>>, val failures: List<ReadFailure>) {
    /** The failure lines, for the end of a tool result: Edith must say so (L6). */
    fun failureText(): String = failures.joinToString("\n") { it.line }
}

/**
 * Reads across accounts (C22 §4, note 5): one coroutine per account, each with its own timeout, so one slow or
 * signed-out account never hides the others' results.
 */
/** A source that is slow by nature (Outlook on the PC, through Jarvis) and is given longer than [Reads.TIMEOUT_MS]. */
interface Patient { val patienceMs: Long }

object Reads {
    const val TIMEOUT_MS = 10_000L
    /** The most a tool waits when a [Patient] source is involved: the source's own limit plus a little. */
    const val PATIENT_TOOL_MS = 55_000L
    const val NEEDS_SIGN_IN = com.edith.app.core.error.SignInWords.NEEDS_SIGN_IN

    suspend fun <S, T> readAll(targets: List<Readable<S>>, timeoutMs: Long = TIMEOUT_MS, read: suspend (S) -> Outcome<T>): ReadAll<T> = coroutineScope {
        val answers = targets.map { t ->
            async {
                when (t.state) {
                    is AccountState.NeedsSignIn -> t.account to Outcome.Failure(AppError.GoogleAuth(NEEDS_SIGN_IN))
                    else -> {
                        val patience = (t.source as? Patient)?.patienceMs
                        val limit = patience ?: timeoutMs
                        t.account to (withTimeoutOrNull(limit) { read(t.source) }
                            ?: Outcome.Failure(if (patience != null) AppError.ToolFailed("Jarvis", "it didn't answer within ${patience / 1000} seconds. Is Outlook busy on the PC?") else AppError.Timeout))
                    }
                }
            }
        }.awaitAll()
        val ok = mutableListOf<Pair<Account, T>>()
        val failed = mutableListOf<ReadFailure>()
        for ((account, outcome) in answers) {
            when (outcome) {
                is Outcome.Success -> ok += account to outcome.value
                is Outcome.Failure -> {
                    Log.w(LogTag.Accounts, "Couldn't read account ${account.id}: ${why(outcome.error)}")   // app-written words; class names are renamed by R8
                    failed += ReadFailure(account, why(outcome.error))
                }
            }
        }
        ReadAll(ok, failed)
    }

    /** Plain words for a failed read, for the model and the Calendar tab. */
    fun why(e: AppError): String = when (e) {
        AppError.Timeout -> "it didn't answer within ${TIMEOUT_MS / 1000} seconds"
        AppError.GoogleNotConnected -> "it isn't connected — Settings → Accounts"
        is AppError.GoogleAuth -> if (e.detail == NEEDS_SIGN_IN || e.detail == AccountErrors.NEEDS_PERMISSION) NEEDS_SIGN_IN else e.detail
        is AppError.ToolFailed -> e.detail
        else -> e.userMessage
    }
}

/** One event in a merged list: the copy Edith acts on, and the same invite in other accounts (C22 §4). */
data class MergedEvent(val event: CalendarEvent, val account: Account, val alsoIn: List<Pair<Account, CalendarEvent>> = emptyList())

/** Merges several accounts' calendar events into one list (C22). */
object CalendarMerge {
    /**
     * Every account's events in one list by start time. The same event in two accounts (an invite: the same
     * `iCalUID` and start) is listed once, under the first account, with the other noted.
     */
    fun merge(results: List<Pair<Account, List<CalendarEvent>>>): List<MergedEvent> {
        val out = mutableListOf<MergedEvent>()
        val byInvite = HashMap<Pair<String, kotlin.time.Instant>, Int>()
        for ((account, events) in results) {
            for (e in events) {
                val key = e.iCalUid?.let { it to e.start }
                val at = key?.let { byInvite[it] }
                if (at != null && out[at].account.id != account.id) {
                    out[at] = out[at].copy(alsoIn = out[at].alsoIn + (account to e))
                } else {
                    if (key != null) byInvite[key] = out.size
                    out += MergedEvent(e, account)
                }
            }
        }
        return out.sortedWith(compareBy({ it.event.start }, { it.event.title }))   // stable: accounts' order otherwise
    }
}

/** The error texts every account's tools share. */
object AccountErrors {
    /** Google refused access twice, or wants approval: the owner must act in Settings → Accounts. */
    const val NEEDS_PERMISSION = "Edith needs your permission again. Open Settings → Accounts and tap the account."
    /**
     * C35 §2: the account hasn't given Edith its to-dos (signed in before C35, or Tasks unticked). Calendar and email
     * still work; the fix is **To-dos: Grant access**.
     */
    const val TASKS_NEED_PERMISSION = "its to-dos need your permission — tell the user to tap To-dos: Grant access in Settings → Accounts (or in the Calendar tab's To-do view)"
}
