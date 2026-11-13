package com.edith.app.google.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.time.Duration.Companion.seconds

/** C23 §1: a `localhost` redirect, which Chrome may try on `::1` before `127.0.0.1`, is heard on both. */
class LocalhostLoopbackTest {
    private val v6 = InetAddress.getByName("::1")
    private val v4 = InetAddress.getByName("127.0.0.1")

    private fun ipv6Loopback(): Boolean = try { ServerSocket(0, 1, v6).close(); true } catch (e: IOException) { false }

    private fun get(address: InetAddress, port: Int, target: String): String = Socket(address, port).use { s ->
        s.soTimeout = 5_000
        s.getOutputStream().write("GET $target HTTP/1.1\r\nHost: localhost:$port\r\n\r\n".toByteArray())
        s.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    @Test fun `listens on IPv6 and IPv4 loopback on the same port, and either can bring the code`() = runBlocking {
        assumeTrue("this machine has no IPv6 loopback", ipv6Loopback())
        val receiver = LoopbackReceiver.openLocalhost(Dispatchers.IO, "com.edith.app.debug://signed-in")
        val port = receiver.port
        assertEquals("http://localhost:$port", receiver.redirectUri)
        assertEquals(2, receiver.addressCount)
        val answer = async(Dispatchers.IO) { receiver.await(10.seconds) }

        assertTrue("127.0.0.1 answers too", get(v4, port, "/favicon.ico").startsWith("HTTP/1.1 404"))
        val page = get(v6, port, "/?code=M.C5_code&state=s1")
        assertTrue(page, page.startsWith("HTTP/1.1 200"))
        assertEquals("http://localhost:$port/?code=M.C5_code&state=s1", answer.await())

        listOf(v4, v6).forEach { a ->
            assertTrue("$a closed", runCatching { Socket(a, port).close() }.isFailure)
        }
    }

    @Test fun `on a given port, as when a pending sign-in is resumed`() = runBlocking {
        assumeTrue(ipv6Loopback())
        val first = LoopbackReceiver.openLocalhost(Dispatchers.IO, "x://y")
        val port = first.port
        first.close()
        val again = LoopbackReceiver.openLocalhost(Dispatchers.IO, "x://y", port)
        try {
            assertEquals(port, again.port)
            assertEquals(2, again.addressCount)
        } finally {
            again.close()
        }
    }

    @Test fun `Google's listener stays on 127_0_0_1 only`() {
        val receiver = LoopbackReceiver.open(Dispatchers.IO, "x://y")
        try {
            assertEquals("http://127.0.0.1:${receiver.port}", receiver.redirectUri)
            assertEquals(1, receiver.addressCount)
        } finally {
            receiver.close()
        }
    }
}
