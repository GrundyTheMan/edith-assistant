package com.edith.app.meross

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.time.Clock
import com.edith.app.core.util.PlatformLock
import com.edith.app.core.util.synchronized
import com.edith.app.icloud.mail.MailTransport
import com.edith.app.icloud.mail.MailTransportFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A small MQTT 3.1.1 client (OASIS spec) for what Meross needs, over a [MailTransport] that is already TLS (the name is the mail client's: it is only a
 * byte connection): connect with a user name and password, subscribe at QoS 1, publish at QoS 0, receive publishes (answering QoS 1 ones), disconnect.
 * No reconnects and no keep-alive pings: a connection lasts for one operation of a few seconds ([MqttMerossBus]).
 */
class MqttConnection(private val t: MailTransport) {
    /** The broker's answer to CONNECT: 0 accepted, 4 bad user name or password, 5 not authorised (spec 3.2.2.3). */
    suspend fun connect(clientId: String, user: String, password: String, keepAliveSeconds: Int = 60): Int {
        val flags = 0xC2   // user name, password, clean session
        val body = string("MQTT") + byteArrayOf(4, flags.toByte(), (keepAliveSeconds shr 8).toByte(), keepAliveSeconds.toByte()) +
            string(clientId) + string(user) + string(password)
        write(0x10, body)
        val ack = readPacket()
        if (ack.type != CONNACK || ack.body.size < 2) throw IOException("The broker didn't answer the connection.")
        return ack.body[1].toInt() and 0xff
    }

    /** Subscribes to [topic] at QoS 1 and waits for the broker's SUBACK; false when it refused. */
    suspend fun subscribe(topic: String, packetId: Int = 1): Boolean {
        write(0x82, byteArrayOf((packetId shr 8).toByte(), packetId.toByte()) + string(topic) + byteArrayOf(1))
        while (true) {
            val p = readPacket()
            if (p.type == SUBACK) return p.body.size >= 3 && (p.body[2].toInt() and 0xff) != 0x80
            if (p.type == PUBLISH) handle(p)   // a message that arrived first is not lost
        }
    }

    /** Publishes [payload] to [topic] at QoS 0. */
    suspend fun publish(topic: String, payload: ByteArray) {
        write(0x30, string(topic) + payload)
    }

    /** Reads packets until the connection ends, handing each publish to [onMessage]. Returns when the broker or the transport closes. */
    suspend fun listen(onMessage: (topic: String, payload: ByteArray) -> Unit) {
        this.onMessage = onMessage
        while (true) {
            val p = try { readPacket() } catch (e: IOException) { return }
            if (p.type == PUBLISH) handle(p)
        }
    }

    suspend fun disconnect() {
        runCatching { write(0xE0, ByteArray(0)) }
        t.close()
    }

    // ---- Inside ----

    private var onMessage: ((String, ByteArray) -> Unit)? = null

    private class Packet(val type: Int, val flags: Int, val body: ByteArray)

    private suspend fun handle(p: Packet) {
        if (p.body.size < 2) return
        val topicLength = ((p.body[0].toInt() and 0xff) shl 8) or (p.body[1].toInt() and 0xff)
        if (p.body.size < 2 + topicLength) return
        val topic = p.body.copyOfRange(2, 2 + topicLength).decodeToString()
        val qos = (p.flags shr 1) and 3
        var at = 2 + topicLength
        if (qos > 0) {
            if (p.body.size < at + 2) return
            write(0x40, p.body.copyOfRange(at, at + 2))   // PUBACK for the same packet id
            at += 2
        }
        onMessage?.invoke(topic, p.body.copyOfRange(at, p.body.size))
    }

    private suspend fun readPacket(): Packet {
        val first = t.readBytes(1)[0].toInt() and 0xff
        var length = 0
        var shift = 0
        while (true) {
            val b = t.readBytes(1)[0].toInt() and 0xff
            length = length or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 21) throw IOException("The broker sent a packet that is not MQTT.")
        }
        return Packet(first shr 4, first and 0x0f, if (length == 0) ByteArray(0) else t.readBytes(length))
    }

    private suspend fun write(header: Int, body: ByteArray) {
        t.write(byteArrayOf(header.toByte()) + remainingLength(body.size) + body)
    }

    private fun string(s: String): ByteArray {
        val bytes = s.encodeToByteArray()
        return byteArrayOf((bytes.size shr 8).toByte(), bytes.size.toByte()) + bytes
    }

    companion object {
        private const val CONNACK = 2
        private const val PUBLISH = 3
        private const val SUBACK = 9

        /** MQTT's variable-length "remaining length" (spec 2.2.3). */
        fun remainingLength(size: Int): ByteArray {
            val out = ArrayList<Byte>(4)
            var n = size
            do {
                var digit = n % 128
                n /= 128
                if (n > 0) digit = digit or 0x80
                out += digit.toByte()
            } while (n > 0)
            return out.toByteArray()
        }
    }
}

/**
 * Meross's MQTT over TLS (C26 §3) on the iPhone (K4.14): the same conversation as Android's Paho bus, over [MqttConnection] and Apple's TLS (which checks
 * the broker's certificate and host name). User name = the user id, password = `md5(userId + key)`, client id `app:<appId>`; commands go to
 * `/appliance/<uuid>/subscribe`, answers come back on `/app/<userId>-<appId>/subscribe` with a `sign` that must verify. The connection lasts for one
 * operation. Payloads, the key and the password are never logged.
 */
@OptIn(ExperimentalUuidApi::class)
class MqttMerossBus(
    private val transports: MailTransportFactory,
    private val dispatchers: AppDispatchers,
    private val clock: Clock,
    private val requestTimeoutMs: Long = REQUEST_TIMEOUT_MS,
    /** Overrides the host and port from the credentials (tests). */
    private val endpoint: Pair<String, Int>? = null,
) : MerossBus {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun <T> withSession(creds: MerossCreds, block: suspend (MerossSession) -> T): MerossReply<T> = withContext(dispatchers.io) {
        val appId = MerossProtocol.appId(Uuid.random().toString())
        val replyTopic = MerossProtocol.responseTopic(creds.userId, appId)
        val (host, port) = endpoint ?: MerossProtocol.hostAndPort(creds.mqttDomain)
        val pending = HashMap<String, CompletableDeferred<JsonObject>>()
        val lock = PlatformLock()
        val transport = try {
            transports.open(host, port, tls = true)
        } catch (e: IOException) {
            Log.w(LogTag.Home, "Meross: couldn't reach the MQTT server")
            return@withContext MerossReply.Failed("Meross can't be reached right now")
        }
        val mqtt = MqttConnection(transport)
        val readerScope = CoroutineScope(SupervisorJob() + dispatchers.io)
        var reader: Job? = null
        try {
            val code = try {
                mqtt.connect("app:$appId", creds.userId, MerossProtocol.mqttPassword(creds.userId, creds.key))
            } catch (e: IOException) {
                return@withContext MerossReply.Failed("Meross can't be reached right now")
            }
            if (code == 4 || code == 5) {
                Log.w(LogTag.Home, "Meross: the MQTT connection was refused (code $code)")
                return@withContext MerossReply.Rejected
            }
            if (code != 0) return@withContext MerossReply.Failed("Meross can't be reached right now")
            val subscribed = try { mqtt.subscribe(replyTopic) } catch (e: IOException) { false }
            if (!subscribed) return@withContext MerossReply.Failed("Meross can't be reached right now")
            reader = readerScope.launch {
                mqtt.listen { topic, payload ->
                    val whole = try { json.parseToJsonElement(payload.decodeToString()) as? JsonObject } catch (e: SerializationException) { null } ?: return@listen
                    val header = whole["header"].obj() ?: return@listen
                    // Only a message signed with this account's key is believed (meross-iot does the same).
                    if (!MerossProtocol.verify(header, creds.key)) { Log.w(LogTag.Home, "Meross: a message with a bad signature was ignored"); return@listen }
                    val method = header.text("method")
                    if (topic != replyTopic || method !in ACKS) return@listen
                    val wait = lock.synchronized { pending[header.text("messageId")] } ?: return@listen
                    if (method == "ERROR") wait.completeExceptionally(MerossDeviceError(whole["payload"].obj()?.get("error").obj()?.int("code")))
                    else wait.complete(whole["payload"].obj() ?: JsonObject(emptyMap()))
                }
                // The connection ended under a waiting request: it is told, not left to time out.
                lock.synchronized { pending.values.toList() }.forEach { it.completeExceptionally(IOException("the connection to Meross dropped")) }
            }
            val session = object : MerossSession {
                override suspend fun request(deviceUuid: String, method: String, namespace: String, payload: JsonObject): MerossReply<JsonObject> {
                    val (text, id) = MerossProtocol.message(method, namespace, payload, deviceUuid, creds.key, replyTopic, clock.now().epochSeconds)
                    val wait = CompletableDeferred<JsonObject>()
                    lock.synchronized { pending[id] = wait }
                    return try {
                        mqtt.publish(MerossProtocol.requestTopic(deviceUuid), text.encodeToByteArray())
                        MerossReply.Ok(withTimeout(requestTimeoutMs) { wait.await() })
                    } catch (e: TimeoutCancellationException) {
                        MerossReply.Failed("the device didn't answer")
                    } catch (e: MerossDeviceError) {
                        MerossReply.Failed("the device refused it${e.code?.let { " (code $it)" } ?: ""}")
                    } catch (e: IOException) {
                        MerossReply.Failed("the connection to Meross dropped")
                    } finally {
                        lock.synchronized { pending.remove(id) }
                    }
                }
            }
            MerossReply.Ok(block(session))
        } finally {
            withContext(NonCancellable) {
                reader?.cancel()
                readerScope.coroutineContext[Job]?.cancel()
                mqtt.disconnect()
            }
        }
    }

    private class MerossDeviceError(val code: Int?) : Exception()

    companion object {
        const val REQUEST_TIMEOUT_MS = 5_000L
        private val ACKS = setOf("GETACK", "SETACK", "ERROR")
    }
}
