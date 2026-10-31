package com.edith.app.testing

import com.edith.app.core.secrets.SecretStore
import java.util.concurrent.ConcurrentHashMap

/** [SecretStore] for JVM tests: no Keystore, values held in memory (C02 §8). */
class InMemorySecretStore : SecretStore {
    private val values = ConcurrentHashMap<String, String>()
    override suspend fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
    override suspend fun get(key: String): String? = values[key]
}
