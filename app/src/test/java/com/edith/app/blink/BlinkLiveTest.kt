package com.edith.app.blink

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket

/** C32: Blink's live view protocol, against made-up captures (as Jarvis's `blinkLive.test.ts`). */
class BlinkLiveTest {
    private fun message(type: Int, payload: List<Int>): ByteArray {
        val h = ByteArray(9)
        h[0] = type.toByte()
        h[8] = payload.size.toByte()
        return h + ByteArray(payload.size) { payload[it].toByte() }
    }

    @Test fun `the address gives host, port, connection and client`() {
        assertEquals(BlinkLive.Immis("10.1.2.3", 443, "ABCD1234EFGH", 4567), BlinkLive.parseImmis("immis://10.1.2.3:443/ABCD1234EFGH__IMDS_xyz?client_id=4567"))
        val bare = BlinkLive.parseImmis("immis://lv.example.com/conn")!!
        assertEquals(443, bare.port); assertEquals("conn", bare.connectionId); assertEquals(0L, bare.clientId)
        assertNull(BlinkLive.parseImmis("immis://"))
    }

    @Test fun `the handshake is 122 bytes laid out as blinkpy sends it`() {
        val h = BlinkLive.handshake("G8T1SERIAL", BlinkLive.Immis("h", 443, "CONN", 258))
        assertEquals(122, h.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0x28), h.copyOfRange(0, 4))
        assertEquals("G8T1SERIAL", String(h.copyOfRange(8, 24)).trimEnd(Char(0)))
        assertArrayEquals(byteArrayOf(0, 0, 1, 2), h.copyOfRange(24, 28))
        assertArrayEquals(byteArrayOf(1, 8), h.copyOfRange(28, 30))
        assertTrue(h.copyOfRange(34, 98).all { it.toInt() == 0 })
        assertEquals("CONN", String(h.copyOfRange(102, 118)).trimEnd(Char(0)))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), h.copyOfRange(118, 122))
        assertEquals(122, BlinkLive.handshake("X".repeat(30), BlinkLive.Immis("h", 443, "", 0)).size)
    }

    @Test fun `the keep-alive and latency packets`() {
        assertArrayEquals(byteArrayOf(0x0a, 0, 0, 0, 3, 0, 0, 0, 0), BlinkLive.keepAlive(3))
        val stats = BlinkLive.latencyStats()
        assertEquals(33, stats.size)
        assertArrayEquals(byteArrayOf(0x12, 0, 0, 0x03, 0xe8.toByte(), 0, 0, 0, 24), stats.copyOfRange(0, 9))
    }

    @Test fun `the reader returns only video payloads, across split reads`() {
        val r = BlinkLive.FrameReader()
        val all = message(0, listOf(0x47, 1, 2)) + message(0x12, listOf(0x47, 9)) + message(0, listOf(0x11, 1)) + message(0, emptyList()) + message(0, listOf(0x47, 3))
        val out = r.push(all.copyOfRange(0, 10)) + r.push(all.copyOfRange(10, 30)) + r.push(all.copyOfRange(30, all.size))
        assertEquals(listOf(listOf(0x47, 1, 2), listOf(0x47, 3)), out.map { c -> c.map { it.toInt() } })
    }

    @Test fun `a stream of something else is dropped instead of waiting for gigabytes`() {
        val r = BlinkLive.FrameReader()
        assertTrue(r.push(byteArrayOf(0, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f, 0x7f)).isEmpty())
        assertEquals(1, r.push(message(0, listOf(0x47, 1))).size)
    }

    @Test fun `a stream sends the handshake, passes on the video, and stops when cancelled`() = runBlocking {
        val server = ServerSocket(0)
        val received = ByteArrayOutputStream()
        val peer = Thread {
            val s = server.accept()
            Thread { runCatching { s.getInputStream().copyTo(received) } }.apply { isDaemon = true }.start()
            s.getOutputStream().apply { write(message(0, listOf(0x47, 1)) + message(0x0a, emptyList()) + message(0, listOf(0x47, 2))); flush() }
        }.apply { isDaemon = true; start() }
        val immis = BlinkLive.Immis("127.0.0.1", server.localPort, "CONN", 7)
        val video = mutableListOf<List<Int>>()
        val job = async { BlinkLiveStream(immis, "SERIAL") { Socket(it.host, it.port) }.run { video += it.map { b -> b.toInt() } } }
        withTimeout(5_000) {
            while (video.size < 2 || received.size() < 122 + 42) delay(50)
        }
        assertEquals(listOf(listOf(0x47, 1), listOf(0x47, 2)), video)
        val sent = received.toByteArray()
        assertArrayEquals(BlinkLive.handshake("SERIAL", immis), sent.copyOfRange(0, 122))
        assertEquals(0x12, sent[122].toInt())
        assertArrayEquals(BlinkLive.keepAlive(1), sent.copyOfRange(122 + 33, 122 + 42))
        job.cancel()
        job.join()
        server.close(); peer.join(1000)
    }

    @Test fun `the server closing ends the stream with a reason`() = runBlocking {
        val server = ServerSocket(0)
        // Read the handshake first: closing a socket with unread data resets the connection instead of ending it cleanly.
        Thread { server.accept().apply { getInputStream().readNBytes(122); close() } }.apply { isDaemon = true; start() }
        val reason = withTimeout(5_000) { BlinkLiveStream(BlinkLive.Immis("127.0.0.1", server.localPort, "C", 1), "S") { Socket(it.host, it.port) }.run { } }
        assertEquals("Blink's stream server closed the connection before any video arrived.", reason)
        server.close()
    }

    @Test fun `live view paths and answers`() {
        val c = BlinkCamera("7", "5", "X", BlinkKind.Camera, null, null, null, null)
        assertEquals("/api/v6/accounts/9/networks/5/cameras/7/liveview", BlinkParse.liveviewPath("9", c))
        assertEquals("/api/v2/accounts/9/networks/5/owls/7/liveview", BlinkParse.liveviewPath("9", c.copy(kind = BlinkKind.Mini)))
        assertEquals("/api/v2/accounts/9/networks/5/doorbells/7/liveview", BlinkParse.liveviewPath("9", c.copy(kind = BlinkKind.Doorbell)))
        val a = BlinkParse.liveAnswer(Json.parseToJsonElement("""{"server":"immis://h/c","command_id":12,"polling_interval":1}"""))
        assertEquals("immis://h/c", a.server); assertEquals("12", a.commandId); assertEquals(2, a.pollSeconds)
    }

    @Test fun `a command that is no longer running ends the view`() {
        fun running(s: String) = BlinkParse.commandRunning(Json.parseToJsonElement(s))
        assertTrue(running("""{"commands":[{"state_condition":"running"}]}"""))
        assertTrue(running("""{"status_code":908,"state_condition":"new"}"""))
        assertEquals(false, running("""{"commands":[{"state_condition":"done"}]}"""))
        assertEquals(false, running("""{"status_code":901}"""))
    }

    @Test fun `a stopped command is described by its codes, and a running one by null`() {
        fun stopped(s: String) = BlinkParse.commandStopped(Json.parseToJsonElement(s))
        assertNull(stopped("""{"commands":[{"state_condition":"running"}]}"""))
        assertEquals("status_code 901, state_condition done", stopped("""{"status_code":901,"commands":[{"state_condition":"done"}]}"""))
        assertEquals("status_code 999", stopped("""{"status_code":999}"""))
    }

    @Test fun `a connection that cannot be made says what failed`() = runBlocking {
        val reason = withTimeout(5_000) {
            BlinkLiveStream(BlinkLive.Immis("h", 443, "C", 1), "S") { throw java.net.ConnectException("Connection refused") }.run { }
        }
        assertEquals("The live view connection failed (couldn't connect: ConnectException: Connection refused)", reason)
    }

    @Test fun `a stream that breaks says what failed, how long it ran and how much arrived`() = runBlocking {
        // A socket whose reads fail the way a reset connection does.
        val broken = object : Socket() {
            override fun getInputStream() = object : java.io.InputStream() {
                override fun read(): Int = throw java.net.SocketException("Connection reset")
                override fun read(b: ByteArray, off: Int, len: Int): Int = throw java.net.SocketException("Connection reset")
            }
            override fun getOutputStream() = java.io.OutputStream.nullOutputStream()
        }
        val reason = withTimeout(5_000) { BlinkLiveStream(BlinkLive.Immis("h", 443, "C", 1), "S") { broken }.run { } }
        assertEquals("The live view connection failed (SocketException: Connection reset, after 0 s and 0 KB of video)", reason)
    }

    @Test fun `a stream that closes after video says how much arrived`() = runBlocking {
        val server = ServerSocket(0)
        Thread { server.accept().apply { getInputStream().readNBytes(122); repeat(11) { getOutputStream().write(message(0, List(190) { 0x47 })) }; getOutputStream().flush(); close() } }.apply { isDaemon = true; start() }
        val reason = withTimeout(5_000) { BlinkLiveStream(BlinkLive.Immis("127.0.0.1", server.localPort, "C", 1), "S") { Socket(it.host, it.port) }.run { } }
        assertEquals("Blink's stream server closed the connection after 2 KB of video.", reason)
        server.close()
    }
}
