package com.edith.app.meross

import com.edith.app.icloud.mail.MailTransport
import com.edith.app.icloud.mail.MailTransportFactory
import com.edith.app.testing.FakeClock
import com.edith.app.testing.assertEquals
import com.edith.app.testing.assertTrue
import com.edith.app.testing.testDispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.time.Instant

/** K4.14: Meross's MQTT on the iPhone, against a scripted MQTT 3.1.1 broker that checks what Meross checks and answers like a device. */
class MqttMerossBusTest {
    private val key = "key-1"
    private val creds = MerossCreds("tok", key, "4242", "a@b.c", "http://x", "mqtt.example.test", "I")
    private val clock = FakeClock(Instant.parse("2026-11-14T15:00:00Z"))

    /** A broker on the other end of a [MailTransport]: what the client writes is parsed as MQTT packets, and the answers are queued for it to read. */
    private class FakeBroker(private val connack: Int = 0, private val answer: (JsonObject) -> JsonObject?) : MailTransport {
        private val incoming = Channel<Byte>(Channel.UNLIMITED)
        val connects = ArrayList<List<String>>()   // client id, user, password
        val subscribed = ArrayList<String>()
        val published = ArrayList<Pair<String, JsonObject>>()
        var disconnected = false
        private var replyTopic = ""

        override suspend fun readLine(): String? = null
        override suspend fun readBytes(count: Int): ByteArray = ByteArray(count) { incoming.receive() }
        override suspend fun startTls() = Unit
        override fun close() { incoming.close() }

        private fun send(vararg bytes: Int) = bytes.forEach { incoming.trySend(it.toByte()) }
        private fun send(bytes: ByteArray) = bytes.forEach { incoming.trySend(it) }

        override suspend fun write(bytes: ByteArray) {
            val type = (bytes[0].toInt() and 0xff) shr 4
            var at = 1
            var length = 0
            var shift = 0
            while (true) {
                val b = bytes[at++].toInt() and 0xff
                length = length or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            val body = bytes.copyOfRange(at, at + length)
            fun str(from: Int): Pair<String, Int> {
                val n = ((body[from].toInt() and 0xff) shl 8) or (body[from + 1].toInt() and 0xff)
                return body.copyOfRange(from + 2, from + 2 + n).decodeToString() to from + 2 + n
            }
            when (type) {
                1 -> {   // CONNECT
                    val (name, a) = str(0)
                    assertEquals("MQTT", name)
                    assertEquals(4, body[a].toInt())   // 3.1.1
                    val (client, b) = str(a + 1 + 1 + 2)
                    val (user, c) = str(b)
                    val (password, _) = str(c)
                    connects += listOf(client, user, password)
                    send(0x20, 2, 0, connack)
                }
                8 -> {   // SUBSCRIBE
                    val (topic, _) = str(2)
                    subscribed += topic
                    replyTopic = topic
                    send(0x90, 3, body[0].toInt(), body[1].toInt(), 1)
                }
                3 -> {   // PUBLISH (QoS 0)
                    val (topic, p) = str(0)
                    val message = Json.parseToJsonElement(body.copyOfRange(p, body.size).decodeToString()) as JsonObject
                    published += topic to message
                    answer(message)?.let { reply ->
                        val topicBytes = replyTopic.encodeToByteArray()
                        val payload = reply.toString().encodeToByteArray()
                        val rest = byteArrayOf((topicBytes.size shr 8).toByte(), topicBytes.size.toByte()) + topicBytes + payload
                        send(byteArrayOf(0x30) + MqttConnection.remainingLength(rest.size) + rest)
                    }
                }
                14 -> disconnected = true
            }
        }
    }

    private fun idOf(m: JsonObject) = ((m["header"] as JsonObject)["messageId"] as JsonPrimitive).content

    private fun signed(method: String, id: String, payload: JsonObject, signKey: String = key, ts: Long = 1000): JsonObject = buildJsonObject {
        put("header", buildJsonObject {
            put("messageId", id)
            put("method", method)
            put("timestamp", ts)
            put("sign", MerossProtocol.md5("$id$signKey$ts"))
            put("namespace", "x")
        })
        put("payload", payload)
    }

    private fun TestScope.bus(broker: FakeBroker, timeoutMs: Long = 5_000) =
        MqttMerossBus(MailTransportFactory { _, _, _ -> broker }, testDispatchers(UnconfinedTestDispatcher(testScheduler)), clock, requestTimeoutMs = timeoutMs)

    private val echo: (JsonObject) -> JsonObject? = { m -> signed("GETACK", idOf(m), buildJsonObject { put("answer", "42") }) }

    @Test fun aSignedAnswerComesBackAndTheBrokerSawWhatMerossChecks() = runTest {
        val broker = FakeBroker(answer = echo)
        val reply = bus(broker).withSession(creds) { s -> s.request("DEV1", "GET", MerossProtocol.SYSTEM_ALL, buildJsonObject {}) }
        val answer = ((reply as MerossReply.Ok).value as MerossReply.Ok).value
        assertEquals("42", (answer["answer"] as JsonPrimitive).content)
        val (client, user, password) = broker.connects.single()
        assertTrue(client.startsWith("app:"))
        assertEquals("4242", user)
        assertEquals(MerossProtocol.mqttPassword("4242", key), password)
        assertTrue(broker.subscribed.single().startsWith("/app/4242-"))
        assertEquals("/appliance/DEV1/subscribe", broker.published.single().first)
        assertTrue("it said goodbye", broker.disconnected)
    }

    @Test fun aRefusedLoginMeansSignInAgain() = runTest {
        val r = bus(FakeBroker(connack = 4, answer = echo)).withSession(creds) { }
        assertEquals(MerossReply.Rejected, r)
    }

    @Test fun anAnswerWithABadSignatureIsIgnored() = runTest {
        val broker = FakeBroker(answer = { m -> signed("GETACK", idOf(m), buildJsonObject {}, signKey = "someone-else") })
        val r = bus(broker, timeoutMs = 200).withSession(creds) { s -> s.request("DEV1", "GET", MerossProtocol.SYSTEM_ALL, buildJsonObject {}) }
        val inner = (r as MerossReply.Ok).value
        assertTrue("silence, then the timeout", inner is MerossReply.Failed && inner.why.contains("didn't answer"))
    }

    @Test fun aDeviceErrorIsReportedWithItsCode() = runTest {
        val broker = FakeBroker(answer = { m -> signed("ERROR", idOf(m), buildJsonObject { put("error", buildJsonObject { put("code", 5001) }) }) })
        val r = bus(broker).withSession(creds) { s -> s.request("DEV1", "SET", MerossProtocol.GARAGE_STATE, buildJsonObject {}) }
        val inner = (r as MerossReply.Ok).value as MerossReply.Failed
        assertTrue(inner.why, inner.why.contains("5001"))
    }

    @Test fun remainingLengthIsMqttsVariableEncoding() {
        fun enc(n: Int) = MqttConnection.remainingLength(n).map { it.toInt() and 0xff }
        assertEquals(listOf(0), enc(0))
        assertEquals(listOf(0x7f), enc(127))
        assertEquals(listOf(0x80, 0x01), enc(128))
        assertEquals(listOf(0xff, 0x7f), enc(16383))
        assertEquals(listOf(0x80, 0x80, 0x01), enc(16384))
    }
}
