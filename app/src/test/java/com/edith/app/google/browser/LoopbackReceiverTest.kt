package com.edith.app.google.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** C17b: the loopback listener, with real sockets on 127.0.0.1. */
class LoopbackReceiverTest {
    private fun open() = LoopbackReceiver.open(Dispatchers.IO, "com.edith.app.debug://signed-in")

    /** Sends a GET for [target] and returns the whole response. */
    private fun get(port: Int, target: String): String = Socket(InetAddress.getByName("127.0.0.1"), port).use { s ->
        s.soTimeout = 5_000
        s.getOutputStream().write("GET $target HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUser-Agent: test\r\n\r\n".toByteArray())
        s.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    private fun assertClosed(port: Int) {
        try {
            Socket(InetAddress.getByName("127.0.0.1"), port).close()
            fail("port $port still accepts connections")
        } catch (e: IOException) {
            // expected: nothing listens any more
        }
    }

    @Test fun `listens on 127_0_0_1 only, ignores a favicon and returns the redirect`() = runBlocking {
        val receiver = open()
        val port = receiver.port
        assertEquals("http://127.0.0.1:$port", receiver.redirectUri)
        val answer = async(Dispatchers.IO) { receiver.await(10.seconds) }

        val favicon = get(port, "/favicon.ico")
        assertTrue(favicon, favicon.startsWith("HTTP/1.1 404"))

        val page = get(port, "/?state=s1&code=4/0Ab&scope=email")
        assertTrue(page, page.startsWith("HTTP/1.1 200"))
        assertTrue("the back link", page.contains("href=\"com.edith.app.debug://signed-in\""))
        assertTrue(page.contains("Then switch back to Edith"))

        assertEquals("http://127.0.0.1:$port/?state=s1&code=4/0Ab&scope=email", answer.await())
        assertClosed(port)
    }

    @Test fun `an error answer is returned too`() = runBlocking {
        val receiver = open()
        val answer = async(Dispatchers.IO) { receiver.await(10.seconds) }
        val page = get(receiver.port, "/?error=access_denied&state=s1")
        assertTrue(page.contains("Sign-in didn't finish"))
        assertEquals("http://127.0.0.1:${receiver.port}/?error=access_denied&state=s1", answer.await())
    }

    @Test fun `an idle connection doesn't block the real request`() = runBlocking {
        val receiver = open()
        val answer = async(Dispatchers.IO) { receiver.await(10.seconds) }
        Socket(InetAddress.getByName("127.0.0.1"), receiver.port).use {   // a browser's spare connection, never used
            get(receiver.port, "/?state=s1&code=c")
            assertEquals("http://127.0.0.1:${receiver.port}/?state=s1&code=c", answer.await())
        }
    }

    @Test fun `timeout closes the port`() = runBlocking {
        val receiver = open()
        assertNull(receiver.await(300.milliseconds))
        assertClosed(receiver.port)
    }

    @Test fun `cancel closes the port`() = runBlocking {
        val receiver = open()
        val answer = async(Dispatchers.IO) { receiver.await(10.seconds) }
        delay(200)
        answer.cancel()
        runCatching { answer.await() }
        assertClosed(receiver.port)
    }
}
