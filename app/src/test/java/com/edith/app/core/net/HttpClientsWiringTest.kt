package com.edith.app.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * C37: keeps the rule in place as the app grows. Reads the sources as text (like `GmailTest`'s "only `GmailTools` calls
 * `client.send(`"), so a future service can't quietly get a client that allows cleartext. If one of these fails, the
 * fix is almost always to take `httpClients.secure`. If a service really must speak plain HTTP (a LAN device, like C34's
 * printer), add it here and say why in the PR.
 */
class HttpClientsWiringTest {
    private val main = File("src/main/java/com/edith/app")
    private val container = File(main, "di/AppContainer.kt").readText()
    private val mainFiles = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Code lines only: no comments or KDoc. */
    private fun code(text: String) = text.lines().filterNot { it.trimStart().let { l -> l.startsWith("*") || l.startsWith("//") || l.startsWith("/*") } }

    @Test fun `there is no single shared client left to be handed to a service by mistake`() {
        assertFalse(container.contains("okHttpLazy"))
        assertFalse(Regex("""\bval okHttp\b""").containsMatchIn(container))
    }

    @Test fun `only Ollama, the page reader and the printer get the plain client`() {
        val plain = code(container).filter { "httpClients.plain" in it || "httpClients.ktorPlain" in it }
        assertEquals("plain: $plain", 3, plain.size)
        assertTrue("Ollama's client: $plain", plain.any { "KtorOllamaClient(" in it })
        assertTrue("the page reader: $plain", plain.any { "PageReader(" in it })
        assertTrue("the 3D printer, a LAN device (C34): $plain", plain.any { "MoonrakerApi(" in it })
    }

    @Test fun `the clients that carry credentials all come from the secure one`() {
        val expected = listOf(
            "OllamaWebSearch(", "DuckDuckGoSearch(", "OpenMeteoClient(", "VoiceDownloader(", "MicrosoftOAuth", "BrowserOAuth(",
            "ICloudAccounts(", "JarvisAccounts(", "MerossApi(", "RingApi(", "BlinkApi(", "AlexaApi(",
        )
        val lines = code(container)
        for (name in expected) {
            val line = lines.firstOrNull { name in it && "httpClients" in it }
            assertTrue("$name isn't given httpClients.secure in AppContainer", line != null && ("httpClients.secure" in line || "httpClients.ktorSecure" in line))
        }
    }

    @Test fun `no service builds its own client or turns cleartext back on`() {
        val own = Regex("""\bOkHttpClient\s*(\(\s*\)|\.Builder\s*\()""")
        for (file in mainFiles) {
            val name = file.invariantSeparatorsPath.substringAfter("com/edith/app/")
            val lines = code(file.readText())
            if (name != "di/AppContainer.kt") {
                assertFalse("$name builds its own OkHttpClient: take httpClients.secure instead", lines.any { own.containsMatchIn(it) })
            }
            assertFalse("$name allows CLEARTEXT itself", lines.any { "ConnectionSpec.CLEARTEXT" in it })
        }
    }
}
