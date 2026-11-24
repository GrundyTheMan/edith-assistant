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
}
