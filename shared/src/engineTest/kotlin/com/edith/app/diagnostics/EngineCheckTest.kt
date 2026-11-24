package com.edith.app.diagnostics

import com.edith.app.attachments.PathAttachmentStore
import com.edith.app.chat.engine.ReplyListener
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.net.KtorClients
import com.edith.app.core.permissions.LocalNetworkAccess
import com.edith.app.core.permissions.ToolPermissions
import com.edith.app.core.secrets.SecretStore
import com.edith.app.di.PlatformServices
import com.edith.app.di.SharedContainer
import com.edith.app.testing.MemorySettings
import com.edith.app.testing.TickingClock
import com.edith.app.testing.inMemoryTestDatabase
import com.edith.app.tools.framework.ToolRegistry
import com.edith.app.tools.location.Coordinates
import com.edith.app.tools.location.LocationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** K4.1: the engine self-test the first iPhone build shows, run here with pretend parts on the desktop and the iPhone simulator. */
class EngineCheckTest {
    private class MemorySecrets : SecretStore {
        val values = mutableMapOf<String, String>()
        override suspend fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        override suspend fun get(key: String): String? = values[key]
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val db = inMemoryTestDatabase()
    private val secrets = MemorySecrets()
    private val settings = MemorySettings()

    private fun check(serverUp: Boolean): EngineCheck {
        val client = lazy {
            HttpClient(
                MockEngine { request ->
                    when {
                        !serverUp -> respond("", HttpStatusCode.ServiceUnavailable)
                        request.url.encodedPath.endsWith("/api/version") -> respond("""{"version":"0.40.13"}""", HttpStatusCode.OK, json)
                        request.url.encodedPath.endsWith("/api/tags") -> respond("""{"models":[{"name":"gemma4:12b"},{"name":"qwen3:8b"}]}""", HttpStatusCode.OK, json)
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                },
            )
        }
        val clients = object : KtorClients {
            override val ktorPlain: Lazy<HttpClient> = client
            override val ktorSecure: Lazy<HttpClient> = client
        }
        val container = SharedContainer(
            PlatformServices(
                dispatchers = AppDispatchers(Dispatchers.Default, Dispatchers.Default, Dispatchers.Default),
                clock = TickingClock(), database = lazy { db }, settings = settings, secrets = secrets, http = clients,
                attachments = PathAttachmentStore(SystemTemporaryDirectory.toString() + "/edith-check-files", SystemTemporaryDirectory.toString() + "/edith-check-cache"),
                location = object : LocationProvider {
                    override suspend fun current(): Outcome<Coordinates> = Outcome.Failure(AppError.PermissionDenied("location"))
                    override suspend fun placeName(at: Coordinates): String? = null
                },
                toolPermissions = ToolPermissions { true },
                localNetwork = object : LocalNetworkAccess { override fun isGranted() = true },
            ),
            toolRegistry = { ToolRegistry(emptyList()) },
            replyListeners = { mutableListOf<ReplyListener>() },
            afterAttachments = {},
        )
        return EngineCheck(container, secrets, settings)
    }

    @AfterTest fun close() { db.close() }

    @Test fun everyCheckPassesWhenTheServerAnswers() = runBlocking {
        val lines = check(serverUp = true).run("http://100.64.0.1:11434")
        assertEquals(listOf("Database", "Keychain", "Settings", "Ollama", "Models"), lines.map { it.name })
        assertTrue(lines.all { it.ok }, lines.toString())
        assertTrue("0.40.13" in lines[3].detail, lines[3].detail)
        assertTrue("2 installed" in lines[4].detail && "gemma4:12b" in lines[4].detail, lines[4].detail)
    }

    @Test fun theAddressIsSavedAndTheKeychainSecretIsRemoved() = runBlocking {
        check(serverUp = true).run("  http://example.test:11434 ")
        assertEquals("http://example.test:11434", settings.current().serverUrl)
        assertTrue(secrets.values.isEmpty(), "the test secret is deleted again")
    }

    @Test fun anUnreachableServerFailsOnlyTheOllamaChecks() = runBlocking {
        val lines = check(serverUp = false).run("http://100.64.0.1:11434")
        assertEquals(listOf(true, true, true, false, false), lines.map { it.ok }, lines.toString())
    }
}
