package com.edith.app.meross

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One open conversation with the Meross cloud: ask a device something and wait for its signed answer. */
interface MerossSession {
    suspend fun request(deviceUuid: String, method: String, namespace: String, payload: JsonObject): MerossReply<JsonObject>
}

/**
 * The MQTT connect options for a Meross sign-in (C26, C37). The server's host name is **checked against its
 * certificate**: Paho 1.2.5 does this by default, but it's switched on here explicitly, so a changed default can't
 * quietly let anyone with any valid certificate pose as Meross to a connection that carries the user id and a
 * password derived from the key. `MerossBusOptionsTest` pins it.
 */
internal fun connectOptions(creds: MerossCreds, connectTimeoutS: Int): MqttConnectOptions = MqttConnectOptions().apply {
    userName = creds.userId
    password = MerossProtocol.mqttPassword(creds.userId, creds.key).toCharArray()
    isCleanSession = true
    connectionTimeout = connectTimeoutS
    keepAliveInterval = 30
    mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
    isAutomaticReconnect = false
    isHttpsHostnameVerificationEnabled = true
}

/** How Edith talks to Meross devices (a fake in tests). */
interface MerossBus {
    /** Connects, runs [block] with the session, disconnects. A connection that can't be made is a [MerossReply.Failed]. */
    suspend fun <T> withSession(creds: MerossCreds, block: suspend (MerossSession) -> T): MerossReply<T>
}

/**
 * Meross's MQTT over TLS (C26 §3), with Eclipse Paho's Java client, as `meross-iot` does it: user name = the user id,
 * password = `md5(userId + key)`, client id `app:<appId>`, TLS to the region's MQTT host on port 443; commands go to
 * `/appliance/<uuid>/subscribe`, answers come back on `/app/<userId>-<appId>/subscribe` with a `sign` that must verify.
 *
 * The connection lasts for one operation (a read of the doors, a command): nothing stays open between them, so there
 * is no reconnect logic and no battery cost. Payloads, the key and the password are never logged.
 */
class PahoMerossBus(
    private val dispatchers: AppDispatchers,
    private val clock: Clock,
    /** `ssl` for Meross; `tcp` in tests against a local broker. */
    private val scheme: String = "ssl",
    private val connectTimeoutS: Int = 5,
    private val requestTimeoutMs: Long = REQUEST_TIMEOUT_MS,
    /** Overrides the host and port from the credentials (tests). */
    private val endpoint: Pair<String, Int>? = null,
) : MerossBus {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun <T> withSession(creds: MerossCreds, block: suspend (MerossSession) -> T): MerossReply<T> =
        withContext(dispatchers.io) {
            val appId = MerossProtocol.appId(UUID.randomUUID().toString())
            val replyTopic = MerossProtocol.responseTopic(creds.userId, appId)
            val (host, port) = endpoint ?: MerossProtocol.hostAndPort(creds.mqttDomain)
            val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
            val client = try {
                MqttClient("$scheme://$host:$port", "app:$appId", MemoryPersistence())
            } catch (e: MqttException) {
                return@withContext MerossReply.Failed("Edith couldn't set up the connection to Meross")
            }
            try {
                client.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        pending.values.forEach { it.completeExceptionally(MqttException(MqttException.REASON_CODE_CONNECTION_LOST.toInt())) }
                    }

                    override fun messageArrived(topic: String, message: MqttMessage) {
                        val whole = try { json.parseToJsonElement(String(message.payload, Charsets.UTF_8)) as? JsonObject } catch (e: SerializationException) { null } ?: return
                        val header = whole["header"].obj() ?: return
                        // Only a message signed with this account's key is believed (meross-iot does the same).
                        if (!MerossProtocol.verify(header, creds.key)) { Log.w(LogTag.Home, "Meross: a message with a bad signature was ignored"); return }
                        val method = header.text("method")
                        if (topic != replyTopic || method !in ACKS) return
                        pending[header.text("messageId")]?.let { wait ->
                            if (method == "ERROR") wait.completeExceptionally(MerossDeviceError(whole["payload"].obj()?.get("error").obj()?.int("code")))
                            else wait.complete(whole["payload"].obj() ?: JsonObject(emptyMap()))
                        }
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
                })
                val options = connectOptions(creds, connectTimeoutS)
                try {
                    client.connect(options)
                    client.subscribe(replyTopic, 1)
                } catch (e: MqttException) {
                    return@withContext refused(e)
                }
                val session = object : MerossSession {
                    override suspend fun request(deviceUuid: String, method: String, namespace: String, payload: JsonObject): MerossReply<JsonObject> {
                        val (text, id) = MerossProtocol.message(method, namespace, payload, deviceUuid, creds.key, replyTopic, clock.now().epochSeconds)
                        val wait = CompletableDeferred<JsonObject>()
                        pending[id] = wait
                        return try {
                            withContext(dispatchers.io) { client.publish(MerossProtocol.requestTopic(deviceUuid), text.toByteArray(Charsets.UTF_8), 0, false) }
                            MerossReply.Ok(withTimeout(requestTimeoutMs) { wait.await() })
                        } catch (e: TimeoutCancellationException) {
                            MerossReply.Failed("the device didn't answer")
                        } catch (e: MerossDeviceError) {
                            MerossReply.Failed("the device refused it${e.code?.let { " (code $it)" } ?: ""}")
                        } catch (e: MqttException) {
                            MerossReply.Failed("the connection to Meross dropped")
                        } finally {
                            pending.remove(id)
                        }
                    }
                }
                MerossReply.Ok(block(session))
            } finally {
                withContext(NonCancellable) {
                    try { if (client.isConnected) client.disconnect(1_000) } catch (e: MqttException) { /* gone anyway */ }
                    try { client.close() } catch (e: MqttException) { /* gone anyway */ }
                }
            }
        }

    /** A refused connect: bad user or password (the token was revoked) means "sign in again"; the rest is just trouble. */
    private fun <T> refused(e: MqttException): MerossReply<T> {
        val auth = e.reasonCode == MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt() || e.reasonCode == MqttException.REASON_CODE_NOT_AUTHORIZED.toInt()
        Log.w(LogTag.Home, "Meross: the MQTT connection failed (reason ${e.reasonCode})")
        return if (auth) MerossReply.Rejected else MerossReply.Failed("Meross can't be reached right now")
    }

    private class MerossDeviceError(val code: Int?) : Exception()

    companion object {
        const val REQUEST_TIMEOUT_MS = 5_000L
        private val ACKS = setOf("GETACK", "SETACK", "ERROR")
    }
}
