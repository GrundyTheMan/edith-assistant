package com.edith.app.meross

import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.testing.FakeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.Collections
import kotlin.concurrent.thread

/**
 * C26 §3: the real Paho client against a scripted MQTT 3.1.1 broker on a local port (plain TCP; Meross is TLS on 443).
 * The broker checks what Meross checks (user name, password, client id) and answers the way a Meross device does:
 * a signed GETACK/SETACK/ERROR on the reply topic named in the message's `from`.
 */
class PahoMerossBusTest {
    private val key = "key-1"
    private val creds = MerossCreds("tok", key, "4242", "a@b.c", "http://x", "mqtt.example.test", "I")
    private val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
    private val clock = FakeClock(Instant.parse("2026-11-14T15:00:00Z"))
    private val dispatchers = AppDispatchers(Dispatchers.Default, Dispatchers.IO, Dispatchers.Default)

    /** What the broker saw. */
    private val connects = Collections.synchronizedList(mutableListOf<List<String?>>())   // clientId, user, password
    private val subscribed = Collections.synchronizedList(mutableListOf<String>())
    private val published = Collections.synchronizedList(mutableListOf<Pair<String, JsonObject>>())
    private var connectCode = 0
    /** How the "device" answers a message: null = silence. */
    private var answer: (JsonObject) -> JsonObject? = { null }

    @After fun tearDown() = server.close()

    private fun bus(timeoutMs: Long = 3_000) =
        PahoMerossBus(dispatchers, clock, scheme = "tcp", connectTimeoutS = 3, requestTimeoutMs = timeoutMs, endpoint = "127.0.0.1" to server.localPort)

    private fun serve() = thread(isDaemon = true) {
        while (!server.isClosed) {
            val s = try { server.accept() } catch (e: Exception) { return@thread }
            thread(isDaemon = true) { try { s.use { handle(it) } } catch (e: Exception) { /* the client left */ } }
        }
    }

    private fun handle(s: Socket) {
        val input = DataInputStream(s.getInputStream())
        val out = s.getOutputStream()
        while (true) {
            val first = input.read().takeIf { it >= 0 } ?: return
            var length = 0; var shift = 0
            while (true) { val b = input.read(); length += (b and 0x7F) shl shift; if (b and 0x80 == 0) break; shift += 7 }
            val body = ByteArray(length).also { input.readFully(it) }
            when (first shr 4) {
                1 -> { // CONNECT
                    var p = 2 + (body[0].toInt() shl 8 or body[1].toInt()) + 1   // protocol name, level
                    val flags = body[p].toInt(); p += 1 + 2                       // flags, keep alive
                    fun str(): String { val n = (body[p].toInt() and 255 shl 8) or (body[p + 1].toInt() and 255); p += 2; return String(body, p, n).also { p += n } }
                    val id = str(); val user = if (flags and 0x80 != 0) str() else null; val pass = if (flags and 0x40 != 0) str() else null
                    connects += listOf(id, user, pass)
                    out.write(byteArrayOf(0x20, 2, 0, connectCode.toByte())); out.flush()
                    if (connectCode != 0) return
                }
                8 -> { // SUBSCRIBE
                    val id = body.copyOfRange(0, 2); val n = (body[2].toInt() and 255 shl 8) or (body[3].toInt() and 255)
                    subscribed += String(body, 4, n)
                    out.write(byteArrayOf(0x90.toByte(), 3) + id + byteArrayOf(1)); out.flush()
                }
                3 -> { // PUBLISH (QoS 0)
                    val n = (body[0].toInt() and 255 shl 8) or (body[1].toInt() and 255)
                    val topic = String(body, 2, n); val whole = Json.parseToJsonElement(String(body, 2 + n, body.size - 2 - n)).jsonObject
                    published += topic to whole
                    answer(whole)?.let { reply -> deliver(out, whole["header"]!!.jsonObject.text("from")!!, reply.toString()) }
                }
                12 -> { out.write(byteArrayOf(0xD0.toByte(), 0)); out.flush() }
                14 -> return
            }
        }
    }

    private fun deliver(out: OutputStream, topic: String, text: String) {
        val t = topic.toByteArray(); val p = text.toByteArray()
        val remaining = 2 + t.size + p.size
        val len = mutableListOf<Byte>().also { var r = remaining; do { var b = r % 128; r /= 128; if (r > 0) b = b or 0x80; it += b.toByte() } while (r > 0) }
        out.write(byteArrayOf(0x30) + len.toByteArray() + byteArrayOf((t.size shr 8).toByte(), t.size.toByte()) + t + p); out.flush()
    }

    /** A device's reply to [request]: its header signed with [key] (or not), and the payload. */
    private fun reply(request: JsonObject, method: String, payload: JsonObject, signedWith: String = key): JsonObject {
        val id = request["header"]!!.jsonObject.text("messageId")!!
        val (text, _) = MerossProtocol.message(method, MerossProtocol.SYSTEM_ALL, payload, "u1", signedWith, "/appliance/u1/subscribe", 1_794_000_000)
        val header = Json.parseToJsonElement(text).jsonObject["header"]!!.jsonObject
        return buildJsonObject { put("header", JsonObject(header + ("messageId" to kotlinx.serialization.json.JsonPrimitive(id)) + ("sign" to kotlinx.serialization.json.JsonPrimitive(MerossProtocol.md5("$id$signedWith${header.text("timestamp")}"))))); put("payload", payload) }
    }

    private val doors = systemAll(Triple(0, 1, 1_794_000_000))

    @Test fun `a request goes to the device's topic and its signed answer comes back on ours`() = runBlocking {
        serve()
        answer = { reply(it, "GETACK", doors) }
        val result = bus().withSession(creds) { s -> s.request("u1", "GET", MerossProtocol.SYSTEM_ALL, JsonObject(emptyMap())) }
        assertEquals(doors, ((result as MerossReply.Ok).value as MerossReply.Ok).value)
        val (client, user, password) = connects.single()
        assertTrue(client!!.startsWith("app:") && client.length == 36)
        assertEquals(listOf("4242", MerossProtocol.mqttPassword("4242", key)), listOf(user, password))
        assertEquals("/appliance/u1/subscribe", published.single().first)
        val header = published.single().second["header"]!!.jsonObject
        assertEquals("/app/4242-${client.removePrefix("app:")}/subscribe", subscribed.single())
        assertEquals(subscribed.single(), header.text("from"))
        assertTrue("the message is signed with the account's key", MerossProtocol.verify(header, key))
    }

    @Test fun `an answer signed with another key is ignored`() = runBlocking {
        serve()
        answer = { reply(it, "GETACK", doors, signedWith = "someone-elses-key") }
        val result = bus(timeoutMs = 600).withSession(creds) { s -> s.request("u1", "GET", MerossProtocol.SYSTEM_ALL, JsonObject(emptyMap())) }
        assertEquals(MerossReply.Failed("the device didn't answer"), (result as MerossReply.Ok).value)
    }

    @Test fun `a device that refuses says so with its code`() = runBlocking {
        serve()
        answer = { reply(it, "ERROR", Json.parseToJsonElement("""{"error":{"code":5001,"detail":"busy"}}""").jsonObject) }
        val result = bus().withSession(creds) { s -> s.request("u1", "SET", MerossProtocol.GARAGE_STATE, JsonObject(emptyMap())) }
        assertEquals(MerossReply.Failed("the device refused it (code 5001)"), (result as MerossReply.Ok).value)
    }

    @Test fun `a silent device times out`() = runBlocking {
        serve()
        val result = bus(timeoutMs = 400).withSession(creds) { s -> s.request("u1", "GET", MerossProtocol.SYSTEM_ALL, JsonObject(emptyMap())) }
        assertEquals(MerossReply.Failed("the device didn't answer"), (result as MerossReply.Ok).value)
    }

    @Test fun `the broker refusing the user name or password means sign in again`() = runBlocking {
        serve()
        for (code in listOf(4, 5)) {
            connectCode = code
            assertEquals("connack $code", MerossReply.Rejected, bus().withSession(creds) { })
        }
    }

    @Test fun `a broker that isn't there is a plain failure, not a crash`() = runBlocking {
        server.close()
        val result = bus().withSession(creds) { }
        assertTrue(result is MerossReply.Failed)
    }
}
