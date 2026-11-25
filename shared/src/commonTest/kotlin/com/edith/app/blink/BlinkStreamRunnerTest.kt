package com.edith.app.blink

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** K4.17: the shared Blink stream runner over a fake connection. */
class BlinkStreamRunnerTest {
    private val immis = BlinkLive.Immis("10.0.0.1", 443, "CONN", 7)

    private fun message(type: Int, payload: List<Int>): ByteArray {
        val h = ByteArray(9)
        h[0] = type.toByte()
        h[8] = payload.size.toByte()
        return h + ByteArray(payload.size) { payload[it].toByte() }
    }

    private class Fake : StreamTransport {
        val incoming = Channel<ByteArray?>(Channel.UNLIMITED)
        val sent = mutableListOf<Byte>()
        var closed = false
        override suspend fun read(max: Int): ByteArray? = incoming.receive()
        override suspend fun write(bytes: ByteArray) { sent += bytes.toList() }
        override fun close() { closed = true }
    }

    @Test fun sendsTheHandshakePassesVideoOnAndStopsWhenCancelled() = runBlocking {
        val fake = Fake()
        fake.incoming.send(message(0, listOf(0x47, 1)) + message(0x0a, emptyList()) + message(0, listOf(0x47, 2)))
        val video = mutableListOf<List<Int>>()
        val job = async { BlinkStreamRunner({ _, _ -> fake }, tickMs = 20).run(immis, "SERIAL") { video += it.map { b -> b.toInt() } } }
        withTimeout(5_000) { while (video.size < 2 || fake.sent.size < 122 + 33) delay(10) }
        assertEquals(listOf(listOf(0x47, 1), listOf(0x47, 2)), video)
        val sent = fake.sent.toByteArray()
        assertContentEquals(BlinkLive.handshake("SERIAL", immis), sent.copyOfRange(0, 122))
        assertEquals(0x12, sent[122].toInt())
        job.cancel(); job.join()
        assertTrue(fake.closed)
    }

    @Test fun theServerClosingBeforeVideoGivesAReason() = runBlocking {
        val fake = Fake()
        fake.incoming.send(null)
        val reason = withTimeout(5_000) { BlinkStreamRunner({ _, _ -> fake }).run(immis, "S") { } }
        assertEquals("Blink's stream server closed the connection before any video arrived.", reason)
        assertTrue(fake.closed)
    }

    @Test fun silenceEndsTheStream() = runBlocking {
        val fake = Fake()
        val reason = withTimeout(5_000) { BlinkStreamRunner({ _, _ -> fake }, quietMs = 50).run(immis, "S") { } }
        assertEquals("The camera stopped sending video.", reason)
    }

    @Test fun aFailedConnectionAndABrokenOneAreReported() = runBlocking {
        val failed = BlinkStreamRunner({ _, _ -> throw RuntimeException("no route") }).run(immis, "S") { }
        assertTrue(failed.startsWith("The live view connection failed (couldn't connect"))
        val broken = object : StreamTransport {
            override suspend fun read(max: Int): ByteArray? = throw RuntimeException("reset")
            override suspend fun write(bytes: ByteArray) {}
            override fun close() {}
        }
        val reason = BlinkStreamRunner({ _, _ -> broken }).run(immis, "S") { }
        assertTrue(reason.startsWith("The live view connection failed (RuntimeException: reset"))
    }

    @Test fun closedEarlyOnlyForAnEarlyServerClose() {
        val r = BlinkStreamRunner({ _, _ -> Fake() })
        assertTrue(r.closedEarly("Blink's stream server closed the connection before any video arrived.", 3))
        assertTrue(!r.closedEarly("Blink's stream server closed the connection before any video arrived.", 30))
        assertTrue(!r.closedEarly("The camera stopped sending video.", 3))
    }
}
