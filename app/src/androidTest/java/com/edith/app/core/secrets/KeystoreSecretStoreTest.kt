package com.edith.app.core.secrets

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.edith.app.EdithApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Keystore isn't available on the JVM, so this runs on the device (C02 tests). */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun roundTripOverwriteDeleteAndEncryptedOnDisk() = runBlocking {
        // The app's own instance: DataStore allows only one per file in a process, and the app's screens (Settings →
        // Web search, C10) open it during the other tests. The key name is unique, so the owner's secrets are untouched.
        val store = (context.applicationContext as EdithApp).container.secretStore
        assertTrue(store is KeystoreSecretStore)
        val key = "test_secret_${System.nanoTime()}"
        val secret = "sk-test-very-secret-value"

        store.put(key, secret)
        assertEquals(secret, store.get(key))

        store.put(key, "second value")
        assertEquals("second value", store.get(key))

        // What's on disk must not contain the value, in plain text or base64.
        val onDisk = File(context.filesDir, "datastore/secrets.preferences_pb").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(onDisk.contains("second value"))
        assertFalse(onDisk.contains(java.util.Base64.getEncoder().encodeToString("second value".toByteArray())))

        store.put(key, null)
        assertNull(store.get(key))
    }
}
