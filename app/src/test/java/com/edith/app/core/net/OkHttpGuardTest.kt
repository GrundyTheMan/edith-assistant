package com.edith.app.core.net

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * K1.4: services talk through Ktor, so they can move to shared code. OkHttp stays only where Ktor has no equivalent of an engine
 * hook, and only Android has it: the client's own settings ([HttpClients]), Blink's cookie jar and IPv4-only lookup, iCloud's
 * password interceptor (it adds the password only to Apple's hosts, on every redirect), Jarvis's certificate pin, the page reader's
 * private-address check. Those files take an OkHttp client and run Ktor over it with [HttpClients.over]. Add nothing to this list:
 * a new service takes the Ktor client.
 */
class OkHttpGuardTest {
    private val androidHooks = setOf(
        "core/net/HttpClients.kt", "core/net/TaggedSocketFactory.kt", "di/AppContainer.kt",
        "alexa/AlexaApi.kt", "blink/BlinkApi.kt", "icloud/ICloudAccounts.kt", "icloud/calendar/CalDavClient.kt",
        "jarvis/JarvisAccounts.kt", "jarvis/JarvisApi.kt", "tools/web/PageReader.kt",
    )

    @Test
    fun onlyTheEngineHooksUseOkHttp() {
        val root = File("src/main/java/com/edith/app")
        assertTrue("run from the app module: ${root.absolutePath}", root.isDirectory)
        val users = root.walkTopDown().filter { it.extension == "kt" }
            .filter { f -> f.readLines().any { it.trimStart().startsWith("import okhttp3.") || it.trimStart().startsWith("import okio.") } }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toSet()
        val strangers = users - androidHooks
        assertTrue("OkHttp used outside the engine hooks (take the Ktor client instead): $strangers", strangers.isEmpty())
    }
}
