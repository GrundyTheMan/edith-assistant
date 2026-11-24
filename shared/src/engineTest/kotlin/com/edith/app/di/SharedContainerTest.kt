package com.edith.app.di

import com.edith.app.attachments.PathAttachmentStore
import com.edith.app.chat.engine.EngineState
import com.edith.app.chat.engine.ReplyListener
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.Outcome
import com.edith.app.core.net.KtorClients
import com.edith.app.core.permissions.LocalNetworkAccess
import com.edith.app.core.permissions.ToolPermissions
import com.edith.app.core.secrets.SecretStore
import com.edith.app.testing.MemorySettings
import com.edith.app.testing.TickingClock
import com.edith.app.testing.inMemoryTestDatabase
import com.edith.app.tools.framework.ToolRegistry
import com.edith.app.tools.location.Coordinates
import com.edith.app.tools.location.LocationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * K2.12: the shared container builds the chat engine from nothing but [PlatformServices] fakes, on the desktop and on the iPhone simulator. It is
 * the first check that the engine can be assembled without Android's container.
 */
class SharedContainerTest {
    private class MemorySecrets : SecretStore {
        private val values = mutableMapOf<String, String>()
        override suspend fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        override suspend fun get(key: String): String? = values[key]
    }

    private val mock = lazy { HttpClient(MockEngine { respondOk("") }) }
    private val db = inMemoryTestDatabase()
    private var registryCalls = 0

    private fun container(): SharedContainer {
        val clients = object : KtorClients {
            override val ktorPlain: Lazy<HttpClient> = mock
            override val ktorSecure: Lazy<HttpClient> = mock
        }
        val platform = PlatformServices(
            dispatchers = AppDispatchers(Dispatchers.Default, Dispatchers.Default, Dispatchers.Default),
            clock = TickingClock(), database = lazy { db }, settings = MemorySettings(), secrets = MemorySecrets(), http = clients,
            attachments = PathAttachmentStore(SystemTemporaryDirectory.toString() + "/edith-container-files", SystemTemporaryDirectory.toString() + "/edith-container-cache"),
            location = object : LocationProvider {
                override suspend fun current(): Outcome<Coordinates> = Outcome.Failure(com.edith.app.core.error.AppError.PermissionDenied("location"))
                override suspend fun placeName(at: Coordinates): String? = null
            },
            toolPermissions = ToolPermissions { true },
            localNetwork = object : LocalNetworkAccess { override fun isGranted() = true },
        )
        return SharedContainer(platform, toolRegistry = { registryCalls++; ToolRegistry(emptyList(), emptyList()) }, replyListeners = { mutableListOf<ReplyListener>() }, afterAttachments = {})
    }

    @AfterTest fun close() { db.close() }

    @Test fun theEngineIsBuiltFromThePlatformServicesAlone() {
        val c = container()
        assertTrue(c.chatEngine.state.value is EngineState.Idle)
        assertEquals(1, registryCalls, "the tool registry is asked for once, when the engine is built")
    }

    @Test fun theSameObjectIsHandedOutEveryTime() {
        val c = container()
        assertSame(c.chatEngine, c.chatEngine)
        assertSame(c.chatRepository, c.chatRepository)
        assertSame(c.confirmations, c.confirmations)
    }

    @Test fun aFirstConversationCanBeResolved() = runBlocking {
        val c = container()
        val id = c.currentConversation.resolve()
        assertTrue(id > 0, "a conversation was created")
    }

    @Test fun nothingIsBuiltUntilItIsAskedFor() {
        container()
        assertEquals(0, registryCalls)
    }
}
