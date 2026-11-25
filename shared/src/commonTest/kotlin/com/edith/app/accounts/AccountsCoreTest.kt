package com.edith.app.accounts

import com.edith.app.accounts.sources.CalendarSource
import com.edith.app.accounts.sources.MailSource
import com.edith.app.testing.assertEquals
import com.edith.app.testing.assertFalse
import com.edith.app.testing.assertNull
import com.edith.app.testing.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** K2.6a: the account list, the prompt and the rename rules, with no provider at all, on the desktop and the iPhone simulator too. */
class AccountsCoreTest {
    private class MemoryStore : AccountStore {
        private val state = MutableStateFlow<List<Account>>(emptyList())
        override val accounts: Flow<List<Account>> = state
        override suspend fun update(transform: (List<Account>) -> List<Account>) { state.value = transform(state.value) }
    }

    private class FakeConnection(startState: AccountState = AccountState.Connected) : AccountConnection {
        override val state: StateFlow<AccountState> = MutableStateFlow(startState)
        override val capabilities = Capabilities(calendar = true, mail = true)
        override val calendar: CalendarSource? = null
        override val mail: MailSource? = null
        var started = 0
        var disconnected = 0
        override suspend fun start() { started++ }
        override suspend fun grant(activity: PlatformActivity, show: (PlatformPendingIntent) -> Unit) = Unit
        override suspend fun onConsentResult(data: PlatformIntent?) = Unit
        override suspend fun disconnect() { disconnected++ }
    }

    private class TestAccounts(store: AccountStore, scope: CoroutineScope, val made: MutableMap<String, FakeConnection>) : AccountsCore(store, scope) {
        override fun connectionFor(account: Account, accountOf: () -> Account): AccountConnection? =
            if (account.provider == Provider.Jarvis) null else FakeConnection().also { made[account.id] = it }

        suspend fun add(provider: Provider, address: String) = upsert(provider, address, null).also { startConnection(it.id) }
        suspend fun rename(id: String, label: String) = renameAccount(id, label)
    }

    private fun accounts(store: AccountStore = MemoryStore()): Pair<TestAccounts, MutableMap<String, FakeConnection>> {
        val made = mutableMapOf<String, FakeConnection>()
        return TestAccounts(store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), made) to made
    }

    @Test fun withNoAccountsThePromptSaysHowToAddOne() {
        val (a, _) = accounts()
        assertTrue(a.notConnectedNote.contains("Settings → Accounts"))
        assertTrue(a.entries().isEmpty())
    }

    @Test fun anAddedAccountIsConnectedAndNamedInThePrompt() = runTest {
        val (a, made) = accounts()
        val added = a.add(Provider.Google, "sam@example.com")
        assertEquals(1, a.entries().size)
        assertEquals(AccountState.Connected, a.entries().single().state)
        assertEquals(1, made.getValue(added.id).started)
        assertTrue(a.connectedNote(emptyList()).contains("sam@example.com (Google"))
    }

    @Test fun addingTheSameAddressAgainSignsInAgainInsteadOfDuplicating() = runTest {
        val (a, _) = accounts()
        val first = a.add(Provider.Google, "sam@example.com")
        val second = a.add(Provider.Google, "SAM@example.com")
        assertEquals(first.id, second.id)
        assertEquals(1, a.entries().size)
    }

    @Test fun aProviderThisBuildCannotUseIsListedAsUnsupported() = runTest {
        val (a, _) = accounts()
        a.add(Provider.Jarvis, "pc")
        assertEquals(AccountState.Error(AccountsCore.UNSUPPORTED), a.entries().single().state)
    }

    @Test fun renamingRefusesBlankAndTakenLabels() = runTest {
        val (a, _) = accounts()
        val one = a.add(Provider.Google, "sam@example.com")
        val two = a.add(Provider.Microsoft, "sam@contoso.com")
        assertEquals(AccountsCore.RenameOutcome.Blank, a.rename(one.id, "  "))
        assertEquals(AccountsCore.RenameOutcome.Taken, a.rename(one.id, "SAM@contoso.com"))
        assertEquals(AccountsCore.RenameOutcome.Done, a.rename(two.id, "Work"))
        assertEquals(AccountsCore.RenameOutcome.Taken, a.rename(one.id, "work"))
    }

    @Test fun disconnectingRevokesAndRemovesOnlyThatAccount() = runTest {
        val (a, made) = accounts()
        val one = a.add(Provider.Google, "sam@example.com")
        val two = a.add(Provider.Microsoft, "sam@contoso.com")
        a.disconnect(one.id)
        assertEquals(1, made.getValue(one.id).disconnected)
        assertEquals(listOf(two.id), a.entries().map { it.account.id })
        assertNull(a.connection(one.id))
        assertFalse(a.connection(two.id) == null)
    }

    @Test fun savedAccountsAreConnectedAtLoad() = runTest {
        val store = MemoryStore()
        store.update { listOf(Account("a1", Provider.Google, "sam@example.com", "sam@example.com")) }
        val (a, made) = accounts(store)
        assertFalse(a.loaded.value)
        a.load()
        assertTrue(a.loaded.value)
        assertEquals(setOf("a1"), made.keys)
    }
}
