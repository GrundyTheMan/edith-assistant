package com.edith.app.settings

import com.edith.app.testing.assertEquals
import com.edith.app.testing.assertTrue
import kotlin.test.Test

class ServerUrlTest {
    private fun valid(input: String) = (ServerUrl.normalize(input) as ServerUrl.Result.Valid).url
    private fun invalid(input: String) = ServerUrl.normalize(input) is ServerUrl.Result.Invalid

    @Test fun `bare address gets scheme and Ollama's port`() =
        assertEquals("http://192.168.4.82:11434", valid("192.168.4.82"))

    @Test fun `trailing slash is removed and an explicit port kept`() =
        assertEquals("http://x:1234", valid("http://x:1234/"))

    @Test fun `scheme without port gets the default port`() =
        assertEquals("http://10.0.2.2:11434", valid("http://10.0.2.2"))

    @Test fun `surrounding whitespace is trimmed and https kept`() =
        assertEquals("https://pc.local:443", valid("  https://pc.local:443  "))

    @Test fun `host name with port works`() =
        assertEquals("http://my-pc:11434", valid("my-pc:11434"))

    @Test fun `invalid input is rejected with a message`() {
        listOf("", "   ", "192.168 .4.82", "ftp://pc:21", "http://", "http://pc:11434/api", "http://p\$c").forEach {
            assertTrue("should reject '$it'", invalid(it))
        }
        assertEquals(
            "The address can't contain spaces.",
            (ServerUrl.normalize("192.168 .4.82") as ServerUrl.Result.Invalid).message,
        )
    }

    private fun onIphone(input: String) = ServerUrl.normalize(input, AtsPlainHttpRule)

    @Test fun `the iPhone allows plain http to the home network and to Tailscale names`() {
        listOf("192.168.4.82", "10.1.2.3", "172.20.0.5", "169.254.1.1", "my-pc", "printer.local", "my-pc.tail1234.ts.net:11434", "localhost").forEach {
            assertTrue("should allow '$it'", onIphone(it) is ServerUrl.Result.Valid)
        }
    }

    @Test fun `the iPhone refuses plain http to a Tailscale number and says to use the name`() {
        val message = (onIphone("100.101.102.103") as ServerUrl.Result.Invalid).message
        assertTrue(message, ".ts.net" in message)
        assertTrue(onIphone("http://100.64.0.1:11434") is ServerUrl.Result.Invalid)
        assertTrue(onIphone("100.128.0.1") is ServerUrl.Result.Invalid)   // outside 100.64.0.0/10: a public address
    }

    @Test fun `the iPhone refuses plain http to other hosts but https is fine everywhere`() {
        assertTrue(onIphone("example.com") is ServerUrl.Result.Invalid)
        assertTrue(onIphone("172.32.0.1") is ServerUrl.Result.Invalid)
        assertEquals("https://example.com:11434", (onIphone("https://example.com") as ServerUrl.Result.Valid).url)
        assertEquals("https://100.101.102.103:11434", (onIphone("https://100.101.102.103") as ServerUrl.Result.Valid).url)
    }

    @Test fun `Android has no such rule`() {
        assertEquals("http://100.101.102.103:11434", valid("100.101.102.103"))
    }
}
