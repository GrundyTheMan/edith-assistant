package com.edith.app.accounts

import android.app.Activity
import com.edith.app.accounts.tasks.TasksAccess
import com.edith.app.google.GoogleAccounts
import com.edith.app.google.GoogleSignIn
import com.edith.app.google.browser.BrowserOAuth
import com.edith.app.icloud.ICloudAccounts
import com.edith.app.jarvis.JarvisAccounts
import com.edith.app.jarvis.JarvisConnection
import com.edith.app.microsoft.MicrosoftAccounts
import com.edith.app.microsoft.MicrosoftOAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Android's accounts (C22, D17): the shared [AccountsCore] with Google, Microsoft, iCloud and Jarvis, and each way of adding an account (K2.6a:
 * the list, the prompt and everything provider-free moved to shared code; what is here needs Android's sign-in or a provider's own classes).
 */
class Accounts(
    store: AccountStore,
    val google: GoogleAccounts,
    private val scope: CoroutineScope,
    /** C23: Microsoft accounts (null in tests that don't need it). */
    val microsoft: MicrosoftAccounts? = null,
    /** C24: iCloud accounts (null in tests that don't need it). */
    val icloud: ICloudAccounts? = null,
    /** C38: the work account read through Jarvis (null in tests that don't need it). */
    val jarvis: JarvisAccounts? = null,
) : AccountsCore(store, scope) {

    override fun connectionFor(account: Account, accountOf: () -> Account): AccountConnection? = when (account.provider) {
        Provider.Google -> google.connect(accountOf) { activity -> onGoogleSignIn(google.signInAgain(activity, accountOf())) }
        Provider.Microsoft -> microsoft?.connect(accountOf) { activity -> microsoft.signInAgain(activity, accountOf()).let { onMicrosoftSignIn(it) } }
        Provider.ICloud -> icloud?.connect(accountOf)
        Provider.Jarvis -> jarvis?.connect(accountOf)
    }

    /** The paired Jarvis (C38), if there is one: the printer page (C39) talks to the PC through it. */
    fun jarvisConnection(): JarvisConnection? = connectionOf(Provider.Jarvis) as? JarvisConnection

    /**
     * At launch: a browser sign-in an earlier run left waiting (C17b note 1), for whichever provider it was (C23).
     */
    fun resumePending() {
        scope.launch {
            val oauthFor: (String) -> BrowserOAuth? = { p ->
                when (p) {
                    MicrosoftOAuth.PROVIDER -> microsoft?.oauth
                    else -> google.browserOAuth.takeIf { p == "google" }
                }
            }
            val r = google.signInFlow.resumePending(oauthFor) ?: return@launch
            when (r.provider) {
                MicrosoftOAuth.PROVIDER -> microsoft?.let { onMicrosoftSignIn(it.result(r.result)) }
                else -> onGoogleSignIn(google.browserResult(r.result))
            }
        }
    }

    /** Add account → Google. [useBrowser]: **Use a browser instead** (C17b). */
    suspend fun addGoogle(activity: Activity, useBrowser: Boolean = false) {
        say(null)
        onGoogleSignIn(google.signInNew(activity, useBrowser))
    }

    /**
     * Add account → iCloud (C24 §1): checks the address and app-specific password, then adds the account (or signs
     * it in again with the new password). Returns why not, or a note when only mail works; null when all is well.
     */
    suspend fun addICloud(address: String, password: String): String? {
        say(null)
        val ic = icloud ?: return NOT_SET_UP_ICLOUD
        val check = ic.check(address, password)
        if (check is ICloudAccounts.Check.Failed) return check.message
        check as ICloudAccounts.Check.Ok
        val account = upsert(Provider.ICloud, address.trim(), null)
        setLogin(account.id, check.login)
        ic.keep(account.id, password)
        startConnection(account.id)
        return if (check.calendar) null else ICloudAccounts.CALENDAR_UNAVAILABLE
    }

    /** Add account → Work (via Jarvis), step 1 (C38 §1): finds the PC and its certificate, for the owner to compare. */
    suspend fun probeJarvis(address: String): JarvisAccounts.Probe = jarvis?.probe(address) ?: JarvisAccounts.Probe.Failed(NOT_SET_UP_JARVIS)

    /**
     * Add account → Work (via Jarvis), step 2: pairs with the code Jarvis shows, then adds the account (or pairs the
     * same PC again). Returns why not, or null when all is well.
     */
    suspend fun addJarvis(base: String, fingerprint: String, code: String, deviceName: String): String? {
        say(null)
        val j = jarvis ?: return NOT_SET_UP_JARVIS
        val paired = when (val r = j.pair(base, fingerprint, code, deviceName)) {
            is JarvisAccounts.Paired.Ok -> r
            is JarvisAccounts.Paired.Failed -> return r.message
        }
        val isNew = isNew(Provider.Jarvis, paired.pcName)
        val account = upsert(Provider.Jarvis, paired.pcName, null)
        j.keep(account.id, paired)
        // A first pairing is called "Work" when nothing else is (the owner can rename it).
        if (isNew) rename(account.id, "Work")
        startConnection(account.id)
        return null
    }

    /** Add account → Microsoft (C23). */
    suspend fun addMicrosoft(activity: Activity) {
        say(null)
        val m = microsoft ?: return run { say(MicrosoftAccounts.NOT_SET_UP) }
        onMicrosoftSignIn(m.signInNew(activity))
    }

    enum class RenameResult { Done, Blank, Taken }

    suspend fun rename(id: String, label: String): RenameResult = when (renameAccount(id, label)) {
        RenameOutcome.Done -> RenameResult.Done
        RenameOutcome.Blank -> RenameResult.Blank
        RenameOutcome.Taken -> RenameResult.Taken
    }

    // --- C17b's browser sign-in, app-wide ------------------------------------------------------------------------

    val browserWaiting: StateFlow<Boolean> get() = google.waiting
    /** The provider being signed in to ("google", "microsoft"), for the waiting dialog. */
    val browserWaitingFor: StateFlow<String?> get() = google.signInFlow.waitingFor
    fun pasteBrowserAddress(address: String) = google.paste(address)
    fun cancelBrowserSignIn() = google.cancel()

    // --- Inside ---------------------------------------------------------------------------------------------------

    /** A Google sign-in finished: the account it names is added, or, if it's already here, signed in again (C22 §2). */
    private suspend fun onGoogleSignIn(r: GoogleSignIn.Result) {
        when (r) {
            is GoogleSignIn.Result.SignedIn -> {
                val mode = if (r.grant != null) Account.SIGN_IN_BROWSER else Account.SIGN_IN_PLAY
                val account = upsert(Provider.Google, r.email, mode)
                r.grant?.let { google.keep(account.id, it) }
                startConnection(account.id)
            }
            GoogleSignIn.Result.Cancelled -> Unit
            is GoogleSignIn.Result.Failed -> say(r.message)
        }
    }

    /** A Microsoft sign-in finished: the account it names is added, or signed in again (C23). */
    private suspend fun onMicrosoftSignIn(r: MicrosoftAccounts.Result) {
        when (r) {
            is MicrosoftAccounts.Result.SignedIn -> {
                val account = upsert(Provider.Microsoft, r.address, null)
                microsoft?.keep(account.id, r.grant)
                startConnection(account.id)
            }
            MicrosoftAccounts.Result.Cancelled -> Unit
            is MicrosoftAccounts.Result.Failed -> say(r.message)
        }
    }

    companion object {
        const val ID = ACCOUNTS_INTEGRATION_ID
        const val NOT_SET_UP_ICLOUD = "iCloud isn't available in this build."
        const val NOT_SET_UP_JARVIS = "Work through Jarvis isn't available in this build."
        const val UNSUPPORTED = AccountsCore.UNSUPPORTED
        fun uniqueLabel(wanted: String, others: List<Account>): String = AccountNames.uniqueLabel(wanted, others)

        fun match(accounts: List<Account>, text: String): List<Account> = AccountNames.match(accounts, text)
    }
}
