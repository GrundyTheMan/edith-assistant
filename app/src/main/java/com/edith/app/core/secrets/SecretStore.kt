package com.edith.app.core.secrets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.edith.app.core.log.Log
import kotlinx.coroutines.flow.first
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Interface for secret storage (encrypted at rest). */
interface SecretStore {
    suspend fun put(key: String, value: String?)
    suspend fun get(key: String): String?
}

/**
 * AES/GCM 256 with a key that never leaves the Android Keystore (alias `edith_secrets`). Each value is stored as
 * base64(iv + ciphertext) in the private DataStore file `secrets` (C02 §7). Create one instance per process
 * (AppContainer does): DataStore allows only one instance per file.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val store: DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(FILE_NAME) }

    override suspend fun put(key: String, value: String?) {
        store.edit { prefs ->
            if (value == null) prefs.remove(stringPreferencesKey(key))
            else prefs[stringPreferencesKey(key)] = encrypt(value)
        }
    }

    override suspend fun get(key: String): String? {
        val stored = store.data.first()[stringPreferencesKey(key)] ?: return null
        return try {
            decrypt(stored)
        } catch (e: GeneralSecurityException) {
            // The key is gone (e.g. data restored to a new phone) or the value is damaged: it can't be recovered.
            Log.w(Log.LogTag.App, "Secret '$key' couldn't be decrypted; removing it", e)
            put(key, null)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(Log.LogTag.App, "Secret '$key' is damaged; removing it", e)
            put(key, null)
            null
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        require(sealed.size > IV_BYTES) { "Too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val FILE_NAME = "secrets"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "edith_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** For unit tests and previews. */
class InMemorySecretStore : SecretStore {
    private val values = java.util.concurrent.ConcurrentHashMap<String, String>()
    override suspend fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
    override suspend fun get(key: String): String? = values[key]
}
