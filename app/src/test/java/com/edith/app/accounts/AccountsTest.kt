package com.edith.app.accounts

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.edith.app.core.secrets.SecretStore
import com.edith.app.settings.DataStoreSettingsRepository
import com.edith.app.testing.InMemorySecretStore
import com.edith.app.testing.MemoryPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** C22 §1, §7: the account list in DataStore, names, and carrying C17/C17b's Google account over. */
class AccountsTest {
    @get:Rule val temp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private val file by lazy { File(temp.root, "settings.preferences_pb") }

    /** The settings file (one write per file on Windows, see [MemoryPreferences]); a fresh DataStore after a restart. */
    private fun fileStore(): DataStore<Preferences> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    private suspend fun restart() = scopes.removeAt(scopes.size - 1).coroutineContext.job.cancelAndJoin()

    private val memory = MemoryPreferences()
    /** Everything that writes more than once: the same store stands for the file across a "restart". */
    private fun dataStore(): DataStore<Preferences> = memory

    @After fun tearDown() = runBlocking { scopes.forEach { it.coroutineContext.job.cancelAndJoin() } }

    private val personal = Account("a1", Provider.Google, "a@gmail.com", "personal", Account.SIGN_IN_PLAY)
    private val work = Account("b2", Provider.Google, "sam@contoso.com", "Work (Contoso)", Account.SIGN_IN_BROWSER)

    // --- The store ----------------------------------------------------------------------------------------------

    @Test fun `the list survives a restart, in the order the owner added them`() = runBlocking {
        DataStoreAccountStore(fileStore()).update { it + personal + work }
        restart()
        assertEquals(listOf(personal, work), DataStoreAccountStore(fileStore()).current())
    }

    @Test fun `settings writes leave the accounts alone`() = runBlocking {
        val store = dataStore()
        DataStoreAccountStore(store).update { listOf(personal) }
        DataStoreSettingsRepository(store).update { it.copy(model = "gemma4:12b") }
        assertEquals(listOf(personal), DataStoreAccountStore(store).current())
    }

    @Test fun `an unreadable list reads as none, without crashing`() = runBlocking {
        val store = dataStore()
        store.edit { it[DataStoreAccountStore.ACCOUNTS] = "{not json" }
        assertEquals(emptyList<Account>(), DataStoreAccountStore(store).current())
    }

    // --- Names (trap 3) -----------------------------------------------------------------------------------------

    @Test fun `byName matches a label or address, ignoring case, or a prefix of either`() {
        val all = listOf(personal, work, Account("c3", Provider.Google, "sam@example.com", "sam@example.com"))
        assertEquals("label", listOf(personal), Accounts.match(all, "Personal"))
        assertEquals("address", listOf(work), Accounts.match(all, " SAM@contoso.com "))
        assertEquals("prefix of a label", listOf(work), Accounts.match(all, "work"))
        assertEquals("prefix of an address", listOf(personal), Accounts.match(all, "a@"))
        assertEquals("ambiguous", listOf(work, all[2]), Accounts.match(all, "sam"))
        assertEquals("an exact match beats prefixes", listOf(all[2]), Accounts.match(all, "sam@example.com"))
        assertTrue("nothing fuzzier", Accounts.match(all, "my gmail").isEmpty())
        assertTrue(Accounts.match(all, "  ").isEmpty())
    }

    @Test fun `a new account's label is its address, made unique if needed`() {
        assertEquals("x@y.z", Accounts.uniqueLabel("x@y.z", listOf(personal)))
        assertEquals("X@y.z (2)", Accounts.uniqueLabel("X@y.z", listOf(personal.copy(label = "x@y.z"))))
        assertEquals("work (sam@contoso.com)", work.copy(label = "work").display)
        assertEquals("a@gmail.com", personal.copy(label = "A@gmail.com").display)
    }

    @Test fun `ids are random, short, and never the address`() {
        val ids = (1..50).map { Account.newId() }
        assertTrue(ids.all { it.matches(Regex("[0-9a-f]{6}")) })
        assertEquals("account/a1/refresh_token", AccountSecrets.refreshToken("a1"))
    }

    // --- Migration (C22 §7, trap 1) -----------------------------------------------------------------------------

    private suspend fun legacy(store: DataStore<Preferences>, secrets: SecretStore, email: String?, mode: String?, token: String?) {
        store.edit { p ->
            email?.let { p[AccountMigration.LEGACY_EMAIL] = it }
            mode?.let { p[AccountMigration.LEGACY_MODE] = it }
        }
        token?.let { secrets.put(AccountMigration.LEGACY_REFRESH_TOKEN, it) }
    }

    private suspend fun assertNoLegacy(store: DataStore<Preferences>, secrets: SecretStore) {
        val p = store.data.first()
        assertNull(p[AccountMigration.LEGACY_EMAIL])
        assertNull(p[AccountMigration.LEGACY_MODE])
        assertNull(secrets.get(AccountMigration.LEGACY_REFRESH_TOKEN))
        assertNull(secrets.get(AccountMigration.LEGACY_PENDING))
    }

    @Test fun `C17's Play services account is carried over`() = runBlocking {
        val store = dataStore()
        val secrets = InMemorySecretStore()
        legacy(store, secrets, "owner@gmail.com", "play", token = null)
        DataStoreSettingsRepository(store).update { it.copy(model = "gemma4:12b") }
        AccountMigration(store, secrets) { "a1" }.run()
        assertEquals(listOf(Account("a1", Provider.Google, "owner@gmail.com", "owner@gmail.com", Account.SIGN_IN_PLAY)), DataStoreAccountStore(store).current())
        assertNoLegacy(store, secrets)
        assertEquals("other settings untouched", "gemma4:12b", DataStoreSettingsRepository(store).current().model)
    }

    @Test fun `C17 before the mode existed counts as Play services`() = runBlocking {
        val store = dataStore()
        val secrets = InMemorySecretStore()
        legacy(store, secrets, "owner@gmail.com", mode = null, token = null)
        AccountMigration(store, secrets) { "a1" }.run()
        assertEquals(Account.SIGN_IN_PLAY, DataStoreAccountStore(store).current().single().signIn)
    }

    @Test fun `C17b's browser account keeps its refresh token, so no sign-in is needed`() = runBlocking {
        val store = dataStore()
        val secrets = InMemorySecretStore()
        legacy(store, secrets, "owner@gmail.com", "browser", token = "1//the-token")
        secrets.put(AccountMigration.LEGACY_PENDING, "{}")
        AccountMigration(store, secrets) { "a1" }.run()
        val account = DataStoreAccountStore(store).current().single()
        assertEquals(Account.SIGN_IN_BROWSER, account.signIn)
        assertEquals("1//the-token", secrets.get(AccountSecrets.refreshToken("a1")))
        assertNoLegacy(store, secrets)
    }

    @Test fun `killed after the list was written, the next run still moves the token`() = runBlocking {
        val store = dataStore()
        val secrets = InMemorySecretStore()
        legacy(store, secrets, "owner@gmail.com", "browser", token = "1//the-token")
        // What a run that died after step 1 leaves: the list, and every old key.
        DataStoreAccountStore(store).update { listOf(Account("a1", Provider.Google, "owner@gmail.com", "owner@gmail.com", Account.SIGN_IN_BROWSER)) }

        val again = dataStore()
        AccountMigration(again, secrets) { error("no second account may be made") }.run()
        assertEquals(1, DataStoreAccountStore(again).current().size)
        assertEquals("1//the-token", secrets.get(AccountSecrets.refreshToken("a1")))
        assertNoLegacy(again, secrets)
    }

    @Test fun `killed after the token was copied, the next run only cleans up`() = runBlocking {
        val store = dataStore()
        val secrets = InMemorySecretStore()
        legacy(store, secrets, "owner@gmail.com", "browser", token = "1//the-token")
        DataStoreAccountStore(store).update { listOf(Account("a1", Provider.Google, "owner@gmail.com", "owner@gmail.com", Account.SIGN_IN_BROWSER)) }
        secrets.put(AccountSecrets.refreshToken("a1"), "1//newer")   // e.g. Google rotated it since
        AccountMigration(store, secrets) { error("no second account may be made") }.run()
        assertEquals("a newer token isn't overwritten", "1//newer", secrets.get(AccountSecrets.refreshToken("a1")))
        assertNoLegacy(store, secrets)
    }

    @Test fun `running it twice changes nothing the second time`() = runBlocking {
        val store = dataStore()
        val secrets = InMemorySecretStore()
        legacy(store, secrets, "owner@gmail.com", "browser", token = "1//the-token")
        var ids = 0
        AccountMigration(store, secrets) { ids++; "a1" }.run()
        val after = DataStoreAccountStore(store).current()
        AccountMigration(store, secrets) { ids++; "zz" }.run()
        assertEquals(after, DataStoreAccountStore(store).current())
        assertEquals(1, ids)
        assertEquals("1//the-token", secrets.get(AccountSecrets.refreshToken("a1")))
    }

    @Test fun `nothing to carry over leaves no list`() = runBlocking {
        val store = dataStore()
        AccountMigration(store, InMemorySecretStore()) { error("no account") }.run()
        assertNull(store.data.first()[DataStoreAccountStore.ACCOUNTS])
    }
}
