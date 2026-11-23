package com.edith.app.core.secrets

/** Interface for secret storage (encrypted at rest). */
interface SecretStore {
    suspend fun put(key: String, value: String?)
    suspend fun get(key: String): String?
}
