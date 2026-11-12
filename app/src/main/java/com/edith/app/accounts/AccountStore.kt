package com.edith.app.accounts

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.secrets.SecretStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException

/** The connected accounts, in the order the owner added them (C22 §1). */
interface AccountStore {
    val accounts: Flow<List<Account>>
    suspend fun current(): List<Account> = accounts.first()
    /** Read, change and write in one transaction. */
    suspend fun update(transform: (List<Account>) -> List<Account>)
}

/** The list as JSON under the DataStore key `accounts`, in the `settings` file (01-architecture §5.3). */
class DataStoreAccountStore(private val store: DataStore<Preferences>) : AccountStore {
    override val accounts: Flow<List<Account>> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { decode(it[ACCOUNTS]) ?: emptyList() }

    override suspend fun update(transform: (List<Account>) -> List<Account>) {
        store.edit { prefs -> prefs[ACCOUNTS] = encode(transform(decode(prefs[ACCOUNTS]) ?: emptyList())) }
    }

    companion object {
        val ACCOUNTS = stringPreferencesKey("accounts")
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(Account.serializer())

        fun encode(list: List<Account>): String = json.encodeToString(serializer, list)

        /** Null when there's no list, or it can't be read (logged without its content). */
        fun decode(text: String?): List<Account>? = text?.let {
            try {
                json.decodeFromString(serializer, it)
            } catch (e: SerializationException) {
                Log.w(LogTag.Accounts, "The saved accounts couldn't be read", e)
                null
            } catch (e: IllegalArgumentException) {
                Log.w(LogTag.Accounts, "The saved accounts couldn't be read", e)
                null
            }
        }
    }
}

/**
 * Carries C17/C17b's one Google account over to the account list, once, at startup (C22 §7), so the owner stays
 * signed in. Safe to run any number of times and to be killed half-way: the new keys are written first, the old ones
 * deleted last, and each step checks what's already done.
 */
class AccountMigration(
    private val store: DataStore<Preferences>,
    private val secrets: SecretStore,
    private val newId: (taken: Collection<String>) -> String = Account::newId,
) {
    suspend fun run() {
        val prefs = store.data.first()
        val email = prefs[LEGACY_EMAIL]
        val legacyToken = secrets.get(LEGACY_REFRESH_TOKEN)
        if (email == null && legacyToken == null && prefs[LEGACY_MODE] == null && secrets.get(LEGACY_PENDING) == null) return

        // 1. The account itself, unless an earlier run (or the owner) already made the list.
        if (prefs[DataStoreAccountStore.ACCOUNTS] == null && email != null) {
            val mode = prefs[LEGACY_MODE] ?: if (legacyToken != null) Account.SIGN_IN_BROWSER else Account.SIGN_IN_PLAY
            val account = Account(newId(emptyList()), Provider.Google, email, email, mode)
            store.edit { if (it[DataStoreAccountStore.ACCOUNTS] == null) it[DataStoreAccountStore.ACCOUNTS] = DataStoreAccountStore.encode(listOf(account)) }
            Log.i(LogTag.Accounts, "Accounts: carried the Google account over as ${account.id}")
        }

        // 2. Its refresh token (C17b's browser sign-in), copied before the old one is deleted.
        if (legacyToken != null) {
            val list = DataStoreAccountStore.decode(store.data.first()[DataStoreAccountStore.ACCOUNTS]).orEmpty().filter { it.provider == Provider.Google }
            val target = list.firstOrNull { email != null && it.address.equals(email, ignoreCase = true) } ?: list.singleOrNull()
            if (target != null && secrets.get(AccountSecrets.refreshToken(target.id)) == null) {
                secrets.put(AccountSecrets.refreshToken(target.id), legacyToken)
                Log.i(LogTag.Accounts, "Accounts: moved the browser sign-in to ${target.id}")
            }
        }

        // 3. The old keys, last. A sign-in that was pending across the update is dropped (it lasts 10 minutes).
        secrets.put(LEGACY_REFRESH_TOKEN, null)
        secrets.put(LEGACY_PENDING, null)
        store.edit {
            it.remove(LEGACY_MODE)
            it.remove(LEGACY_EMAIL)
        }
    }

    companion object {
        val LEGACY_EMAIL = stringPreferencesKey("google_account_email")
        val LEGACY_MODE = stringPreferencesKey("google_sign_in_mode")
        const val LEGACY_REFRESH_TOKEN = "google_refresh_token"
        const val LEGACY_PENDING = "google_pending_sign_in"
    }
}
