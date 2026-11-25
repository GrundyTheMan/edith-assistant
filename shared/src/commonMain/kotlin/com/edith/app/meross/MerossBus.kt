package com.edith.app.meross

import kotlinx.serialization.json.JsonObject

/** One open conversation with the Meross cloud: ask a device something and wait for its signed answer. */
interface MerossSession {
    suspend fun request(deviceUuid: String, method: String, namespace: String, payload: JsonObject): MerossReply<JsonObject>
}

/** How Edith talks to Meross devices (a fake in tests). */
interface MerossBus {
    /** Connects, runs [block] with the session, disconnects. A connection that can't be made is a [MerossReply.Failed]. */
    suspend fun <T> withSession(creds: MerossCreds, block: suspend (MerossSession) -> T): MerossReply<T>
}
