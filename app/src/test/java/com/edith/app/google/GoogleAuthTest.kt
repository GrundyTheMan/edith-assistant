package com.edith.app.google

import android.app.Activity
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.edith.app.accounts.Account
import com.edith.app.accounts.AccountState
import com.edith.app.accounts.Accounts
import com.edith.app.accounts.DataStoreAccountStore
import com.edith.app.accounts.Provider
import com.edith.app.accounts.sources.AccountErrors
import com.edith.app.chat.engine.PromptContext
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.ollama.ModelInfo
import com.edith.app.testing.AccountsHarness
import com.edith.app.testing.InMemoryAccountStore
import com.edith.app.tools.framework.IntegrationStatus
import com.edith.app.tools.framework.Tool
import com.edith.app.tools.framework.ToolArgs
import com.edith.app.tools.framework.ToolContext
import com.edith.app.tools.framework.ToolGuidanceSection
import com.edith.app.tools.framework.ToolRegistry
import com.edith.app.tools.framework.ToolResult
import com.edith.app.tools.framework.ToolSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZonedDateTime

/**
 * C17 on the per-account API (C22): Google accounts' states with fake sign-in and authorization (Play services path),
 * several accounts, the tool offering and prompt line, and no token stored.
 */
class GoogleAuthTest {
    @get:Rule val temp = TemporaryFolder()

    private val h = AccountsHarness()
    private val accounts get() = h.accounts

    private fun only(): Pair<Account, AccountState> = accounts.accounts.value.single()
    private fun auth(id: String) = (accounts.connection(id) as GoogleConnection).auth

    @Test fun `without either client ID Google is not set up and nothing happens`() = runBlocking {
        val bare = AccountsHarness(web = "", desktop = "")
        assertTrue(bare.google.notSetUp)
        bare.accounts.addGoogle(Activity())
        assertEquals(GoogleAccounts.NOT_SET_UP, bare.accounts.message.value)
        assertTrue(bare.accounts.accounts.value.isEmpty())
        assertEquals(0, bare.signIn.signIns)
        assertEquals(IntegrationStatus.NotConnected, bare.accounts.status.value)
    }

    @Test fun `adding an account with access granted connects and keeps only the address`() = runBlocking {
        assertTrue(accounts.accounts.value.isEmpty())
        accounts.addGoogle(Activity())
        val (account, state) = only()
        assertEquals(AccountState.Connected, state)
        assertEquals(IntegrationStatus.Connected, accounts.status.value)
        assertEquals(Provider.Google, account.provider)
        assertEquals("owner@gmail.com", account.address)
        assertEquals("the label starts as the address", "owner@gmail.com", account.label)
        assertEquals(Account.SIGN_IN_PLAY, account.signIn)
        assertEquals("calendar's and email's scopes at once, for that account; not Tasks (C35 trap 2)", listOf("owner@gmail.com" to GoogleScope.CORE), h.play.asked)
    }

    @Test fun `needs consent, then granted, connects`() = runBlocking {
        h.play.answers += GoogleAuthorizer.Result.NeedsConsent(null)
        accounts.addGoogle(Activity())
        assertEquals(AccountState.NeedsSignIn(GoogleAccountAuth.NEEDS_PERMISSION_REASON), only().second)
        assertEquals(IntegrationStatus.NotConnected, accounts.status.value)
        accounts.grant(only().first.id, Activity()) { }
        accounts.onConsentResult(null)
        assertEquals(AccountState.Connected, only().second)
    }

    @Test fun `consent refused stays at needs sign-in`() = runBlocking {
        h.play.answers += GoogleAuthorizer.Result.NeedsConsent(null)
        h.play.consent = GoogleAuthorizer.Result.Failed("Access wasn't approved.")
        accounts.addGoogle(Activity())
        accounts.grant(only().first.id, Activity()) { }
        accounts.onConsentResult(null)
        assertTrue(only().second is AccountState.NeedsSignIn)
    }

    @Test fun `a cancelled sign-in changes nothing and a failed one says why`() = runBlocking {
        h.signIn.result = GoogleSignIn.Result.Cancelled
        accounts.addGoogle(Activity())
        assertTrue(accounts.accounts.value.isEmpty())
        h.signIn.result = GoogleSignIn.Result.Failed("No Google account is set up on this phone.")
        accounts.addGoogle(Activity())
        assertEquals("No Google account is set up on this phone.", accounts.message.value)
        assertTrue(accounts.accounts.value.isEmpty())
    }

    @Test fun `at startup saved accounts are checked quietly`() = runBlocking {
        val saved = listOf(Account("a1", Provider.Google, "owner@gmail.com", "personal", Account.SIGN_IN_PLAY))
        val g = AccountsHarness(store = InMemoryAccountStore(saved))
        g.accounts.load()
        assertEquals(listOf(saved[0] to AccountState.Connected), g.accounts.accounts.value)

        val again = AccountsHarness(store = InMemoryAccountStore(saved))
        again.play.answers += GoogleAuthorizer.Result.NeedsConsent(null)
        again.accounts.load()
        assertTrue(again.accounts.accounts.value.single().second is AccountState.NeedsSignIn)
    }

    @Test fun `no saved account stays empty at startup`() = runBlocking {
        accounts.load()
        assertTrue(accounts.loaded.value)
        assertTrue(accounts.accounts.value.isEmpty())
        assertTrue(h.play.asked.isEmpty())
    }

    @Test fun `a 401 clears the token and retries once`() = runBlocking {
        accounts.addGoogle(Activity())
        val id = only().first.id
        h.play.answers += GoogleAuthorizer.Result.Granted("old")
        h.play.answers += GoogleAuthorizer.Result.Granted("new")
        var calls = 0
        val r = auth(id).authorized(setOf(GoogleScope.CalendarEvents)) { token -> calls++; if (token == "old") GoogleCall.Unauthorized else GoogleCall.Ok("events") }
        assertEquals(Outcome.Success("events"), r)
        assertEquals(listOf("old"), h.play.cleared)
        assertEquals(2, calls)
        assertEquals(AccountState.Connected, only().second)
    }

    @Test fun `two 401s in a row mean access was withdrawn`() = runBlocking {
        accounts.addGoogle(Activity())
        val r = auth(only().first.id).authorized(setOf(GoogleScope.GmailReadonly)) { GoogleCall.Unauthorized }
        assertEquals(Outcome.Failure(AppError.GoogleAuth(AccountErrors.NEEDS_PERMISSION)), r)
        assertTrue(only().second is AccountState.NeedsSignIn)
        assertEquals(IntegrationStatus.NotConnected, accounts.status.value)
    }

    @Test fun `a second account is added beside the first, and the same one again isn't duplicated`() = runBlocking {
        accounts.addGoogle(Activity())
        h.signIn.result = GoogleSignIn.Result.SignedIn("work@contoso.com", null)
        accounts.addGoogle(Activity())
        assertEquals(listOf("owner@gmail.com", "work@contoso.com"), accounts.accounts.value.map { it.first.address })
        assertTrue(accounts.accounts.value.all { it.second == AccountState.Connected })
        assertEquals("each account asks Play services for itself", setOf("owner@gmail.com", "work@contoso.com"), h.play.asked.map { it.first }.toSet())

        h.signIn.result = GoogleSignIn.Result.SignedIn("Work@Contoso.com", null)
        accounts.addGoogle(Activity())
        assertEquals(2, accounts.accounts.value.size)
        assertEquals(2, accounts.accounts.value.map { it.first.id }.toSet().size)
    }

    @Test fun `disconnect revokes that account only, signs out of Play and removes it`() = runBlocking {
        accounts.addGoogle(Activity())
        h.signIn.result = GoogleSignIn.Result.SignedIn("work@contoso.com", null)
        accounts.addGoogle(Activity())
        val work = accounts.accounts.value.last().first
        accounts.disconnect(work.id)
        assertEquals(listOf("work@contoso.com"), h.play.revoked)
        assertEquals(1, h.signIn.signOuts)
        assertEquals(listOf("owner@gmail.com"), accounts.accounts.value.map { it.first.address })
        assertEquals("the other account still works", IntegrationStatus.Connected, accounts.status.value)

        accounts.disconnect(only().first.id)
        assertTrue(accounts.accounts.value.isEmpty())
        assertEquals(IntegrationStatus.NotConnected, accounts.status.value)
    }

    @Test fun `rename keeps labels unique, ignoring case, and never another account's address`() = runBlocking {
        accounts.addGoogle(Activity())
        h.signIn.result = GoogleSignIn.Result.SignedIn("work@contoso.com", null)
        accounts.addGoogle(Activity())
        val (first, second) = accounts.accounts.value.map { it.first.id }
        assertEquals(Accounts.RenameResult.Done, accounts.rename(first, " Personal "))
        assertEquals("Personal", accounts.accounts.value.first().first.label)
        assertEquals(Accounts.RenameResult.Taken, accounts.rename(second, "personal"))
        assertEquals(Accounts.RenameResult.Taken, accounts.rename(second, "OWNER@gmail.com"))
        assertEquals(Accounts.RenameResult.Blank, accounts.rename(second, "  "))
        assertEquals(Accounts.RenameResult.Done, accounts.rename(second, "work"))
        assertEquals(listOf("Personal", "work"), accounts.accounts.value.map { it.first.label })
    }

    // --- Tools and the prompt (C08 integration, L6) -----------------------------------------------------------

    private class AccountTool(name: String) : Tool {
        override val spec = ToolSpec(name, "an account tool")
        override val statusLabel = "Checking…"
        override val displayName = name
        override val integrationId = Accounts.ID
        override suspend fun execute(args: ToolArgs, ctx: ToolContext) = ToolResult.Ok("ok")
    }

    private val model = ModelInfo("gemma4:12b", setOf("tools"))
    private fun prompt(registry: ToolRegistry) = ToolGuidanceSection { registry }.text(PromptContext(ZonedDateTime.now(), model)).orEmpty()

    @Test fun `the tools are offered while at least one account is connected, and the prompt names every account`() = runBlocking {
        val withTools = ToolRegistry(listOf(AccountTool("list_calendar_events"), AccountTool("search_email")), listOf(accounts))
        assertTrue(withTools.available(model).isEmpty())
        assertTrue(prompt(withTools).contains("No email or calendar account is connected"))

        accounts.addGoogle(Activity())
        accounts.rename(only().first.id, "personal")
        assertEquals(listOf("list_calendar_events", "search_email"), withTools.available(model).map { it.spec.name })
        assertTrue(prompt(withTools), prompt(withTools).contains("Accounts: personal — owner@gmail.com (Google: calendar, email and to-dos)."))

        h.signIn.result = GoogleSignIn.Result.SignedIn("sam@contoso.com", null)
        h.play.answers += GoogleAuthorizer.Result.NeedsConsent(null)
        accounts.addGoogle(Activity())
        accounts.rename(accounts.accounts.value.last().first.id, "work")
        assertEquals("still offered: one account works", 2, withTools.available(model).size)
        assertTrue(
            prompt(withTools),
            prompt(withTools).contains("Accounts: personal — owner@gmail.com (Google: calendar, email and to-dos); work — sam@contoso.com (Google: needs your permission)."),
        )

        accounts.disconnect(accounts.accounts.value.first().first.id)
        assertTrue("only an account that needs signing in is left", withTools.available(model).isEmpty())
        assertTrue(prompt(withTools), prompt(withTools).contains("work — sam@contoso.com (Google: needs your permission). None can be used right now"))
    }

    @Test fun `no token is ever written to the settings file`() = runBlocking {
        val file = File(temp.root, "settings.preferences_pb")
        val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val real = AccountsHarness(store = InMemoryAccountStore())
        val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) { file }
        val accounts = Accounts(DataStoreAccountStore(dataStore), real.google, real.scope)
        accounts.addGoogle(Activity())
        val id = accounts.accounts.value.single().first.id
        assertEquals(Outcome.Success("ya29.secret-token"), (accounts.connection(id) as GoogleConnection).auth.accessToken(setOf(GoogleScope.CalendarEvents)))
        storeScope.coroutineContext.job.cancelAndJoin()
        val bytes = String(file.readBytes(), Charsets.ISO_8859_1)
        assertTrue("the address is kept", bytes.contains("owner@gmail.com"))
        assertFalse("no token", bytes.contains("ya29"))
    }
}
