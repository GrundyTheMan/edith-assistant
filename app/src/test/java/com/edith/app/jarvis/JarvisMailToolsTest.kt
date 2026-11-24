package com.edith.app.jarvis

import com.edith.app.accounts.Account
import com.edith.app.accounts.AccountDirectory
import com.edith.app.accounts.AccountEntry
import com.edith.app.accounts.AccountState
import com.edith.app.accounts.Capabilities
import com.edith.app.accounts.Provider
import com.edith.app.accounts.tools.MailHandles
import com.edith.app.accounts.tools.mailTools
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.settings.Settings
import com.edith.app.testing.FakeClock
import com.edith.app.tools.framework.Tool
import com.edith.app.tools.framework.ToolArgs
import com.edith.app.tools.framework.ToolContext
import com.edith.app.tools.framework.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** C38: the work account can save an email draft, and can't be asked to send. No request ever leaves the phone here. */
class JarvisMailToolsTest {
    private val account = Account("w1", Provider.Jarvis, "BEN-PC", "Work")
    private val dispatchers = AppDispatchers(Dispatchers.Unconfined, Dispatchers.Unconfined, Dispatchers.Unconfined)
    private val api = JarvisApi({ null }, OkHttpPinnedHttp(lazy { OkHttpClient() }), dispatchers)   // no link: any call would fail, and none is made
    private val entry = AccountEntry(account, AccountState.Connected, Capabilities(calendar = false, mail = true), null, JarvisMailSource(account, api))
    private val dir = object : AccountDirectory { override fun entries() = listOf(entry) }
    private val tools = mailTools(dir, MailHandles()).associateBy { it.spec.name }
    private val ctx = ToolContext(FakeClock(Instant.parse("2026-11-17T12:00:00Z"), ZoneId.of("UTC")), Settings(), dispatchers)

    private fun args(tool: Tool, vararg pairs: Pair<String, String>) = ToolArgs(JsonObject(pairs.associate { it.first to JsonPrimitive(it.second) }), tool.spec)

    @Test fun `asked to send, Edith gets no card and is told to offer a draft`() = runBlocking {
        val send = tools.getValue("send_email")
        val a = args(send, "to" to "dana@corp.example", "subject" to "Hi", "body" to "Hello")
        assertNull("no Send card for an account that can't send", send.confirmation(a))
        val r = send.execute(a, ctx)
        assertTrue(r is ToolResult.Error)
        assertTrue(r.text, r.text.contains("draft"))
    }

    @Test fun `a draft still gets its card`() = runBlocking {
        val draft = tools.getValue("draft_email")
        assertNotNull(draft.confirmation(args(draft, "to" to "dana@corp.example", "subject" to "Hi", "body" to "Hello")))
    }
}
