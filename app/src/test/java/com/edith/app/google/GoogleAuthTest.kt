package com.edith.app.google

import android.app.Activity
import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.ollama.ModelInfo
import com.edith.app.settings.DataStoreSettingsRepository
import com.edith.app.settings.Settings
import com.edith.app.testing.FakeSettingsRepository
import com.edith.app.tools.framework.IntegrationStatus
import com.edith.app.tools.framework.Tool
import com.edith.app.tools.framework.ToolContext
import com.edith.app.tools.framework.ToolGuidanceSection
import com.edith.app.tools.framework.ToolRegistry
import com.edith.app.tools.framework.ToolResult
import com.edith.app.tools.framework.ToolSpec
import com.edith.app.chat.engine.PromptContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import com.edith.app.tools.framework.ToolArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZonedDateTime

/** C17: the Google account's states with fake sign-in and authorization, the tool offering, and no token stored. */
class GoogleAuthTest {
    @get:Rule val temp = TemporaryFolder()

    private class FakeSignIn : GoogleSignIn {
        var result: GoogleSignIn.Result = GoogleSignIn.Result.SignedIn("owner@gmail.com", "Owner")
        var signOuts = 0
        override suspend fun signIn(activity: Activity, webClientId: String) = result
        override suspend fun signOut() { signOuts++ }
    }

    private class FakeAuthorizer : GoogleAuthorizer {
        val answers = ArrayDeque<GoogleAuthorizer.Result>()
        var default: GoogleAuthorizer.Result = GoogleAuthorizer.Result.Granted("ya29.secret-token")
        var consent: GoogleAuthorizer.Result = GoogleAuthorizer.Result.Granted("ya29.after-consent")
        val cleared = mutableListOf<String>()
        val revoked = mutableListOf<String>()
        val asked = mutableListOf<Set<GoogleScope>>()
        override suspend fun authorize(scopes: Set<GoogleScope>): GoogleAuthorizer.Result { asked += scopes; return answers.removeFirstOrNull() ?: default }
        override fun fromConsent(data: Intent?) = consent
        override suspend fun clearToken(token: String) { cleared += token }
        override suspend fun revoke(email: String) { revoked += email }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val signIn = FakeSignIn()
    private val authorizer = FakeAuthorizer()
    private val settings = FakeSettingsRepository()
    private fun google(webClientId: String = "web.apps.googleusercontent.com") = GoogleAuth(signIn, authorizer, settings, webClientId, scope)

    @Test fun `without a Web client ID Google is not set up and nothing happens`() = runBlocking {
        val g = google("")
        assertEquals(GoogleState.NotSetUp, g.state.value)
        g.signIn(Activity())
        assertEquals(GoogleState.NotSetUp, g.state.value)
        assertEquals(IntegrationStatus.NotConnected, g.status.value)
    }

    @Test fun `signing in with access granted connects and keeps only the email`() = runBlocking {
        val g = google()
        assertEquals(GoogleState.SignedOut, g.state.value)
        g.signIn(Activity())
        assertEquals(GoogleState.Connected("owner@gmail.com"), g.state.value)
        assertEquals(IntegrationStatus.Connected, g.status.value)
        assertEquals(Settings(googleAccountEmail = "owner@gmail.com"), settings.current())
        assertEquals("every scope asked for at once", GoogleScope.entries.toSet(), authorizer.asked.single())
    }

    @Test fun `needs consent, then granted, connects`() = runBlocking {
        authorizer.answers += GoogleAuthorizer.Result.NeedsConsent(null)
        val g = google()
        g.signIn(Activity())
        assertEquals(GoogleState.NeedsConsent("owner@gmail.com"), g.state.value)
        assertEquals(IntegrationStatus.NotConnected, g.status.value)
        g.onConsentResult(null)
        assertEquals(GoogleState.Connected("owner@gmail.com"), g.state.value)
    }

    @Test fun `consent refused stays at needs consent`() = runBlocking {
        authorizer.answers += GoogleAuthorizer.Result.NeedsConsent(null)
        authorizer.consent = GoogleAuthorizer.Result.Failed("Access wasn't approved.")
        val g = google()
        g.signIn(Activity())
        g.onConsentResult(null)
        assertEquals(GoogleState.NeedsConsent("owner@gmail.com"), g.state.value)
    }

    @Test fun `a cancelled sign-in changes nothing and a failed one says why`() = runBlocking {
        val g = google()
        signIn.result = GoogleSignIn.Result.Cancelled
        g.signIn(Activity())
        assertEquals(GoogleState.SignedOut, g.state.value)
        signIn.result = GoogleSignIn.Result.Failed("No Google account is set up on this phone.")
        g.signIn(Activity())
        assertEquals(GoogleState.Error(null, "No Google account is set up on this phone."), g.state.value)
        assertNull(settings.current().googleAccountEmail)
    }

    @Test fun `at startup a saved account is checked quietly`() = runBlocking {
        settings.update { it.copy(googleAccountEmail = "owner@gmail.com") }
        val g = google()
        g.start()
        assertEquals(GoogleState.Connected("owner@gmail.com"), g.state.value)

        authorizer.answers += GoogleAuthorizer.Result.NeedsConsent(null)
        val again = google()
        again.start()
        assertEquals(GoogleState.NeedsConsent("owner@gmail.com"), again.state.value)
    }

    @Test fun `no saved account stays signed out at startup`() = runBlocking {
        val g = google()
        g.start()
        assertEquals(GoogleState.SignedOut, g.state.value)
        assertTrue(authorizer.asked.isEmpty())
    }

    @Test fun `a token before signing in is GoogleNotConnected`() = runBlocking {
        assertEquals(Outcome.Failure(AppError.GoogleNotConnected), google().accessToken(setOf(GoogleScope.CalendarEvents)))
    }

    @Test fun `a 401 clears the token and retries once`() = runBlocking {
        val g = google().also { it.signIn(Activity()) }
        authorizer.answers += GoogleAuthorizer.Result.Granted("old")
        authorizer.answers += GoogleAuthorizer.Result.Granted("new")
        var calls = 0
        val r = g.authorized(setOf(GoogleScope.CalendarEvents)) { token -> calls++; if (token == "old") GoogleCall.Unauthorized else GoogleCall.Ok("events") }
        assertEquals(Outcome.Success("events"), r)
        assertEquals(listOf("old"), authorizer.cleared)
        assertEquals(2, calls)
        assertEquals(GoogleState.Connected("owner@gmail.com"), g.state.value)
    }

    @Test fun `two 401s in a row mean access was withdrawn`() = runBlocking {
        val g = google().also { it.signIn(Activity()) }
        val r = g.authorized(setOf(GoogleScope.GmailReadonly)) { GoogleCall.Unauthorized }
        assertEquals(Outcome.Failure(AppError.GoogleAuth(GoogleAuth.NEEDS_PERMISSION)), r)
        assertEquals(GoogleState.NeedsConsent("owner@gmail.com"), g.state.value)
        assertEquals(IntegrationStatus.NotConnected, g.status.value)
    }

    @Test fun `disconnect revokes, signs out and forgets the email`() = runBlocking {
        val g = google().also { it.signIn(Activity()) }
        g.disconnect()
        assertEquals(listOf("owner@gmail.com"), authorizer.revoked)
        assertEquals(1, signIn.signOuts)
        assertNull(settings.current().googleAccountEmail)
        assertEquals(GoogleState.SignedOut, g.state.value)
        assertEquals(IntegrationStatus.NotConnected, g.status.value)
    }

    // --- Tools and the prompt (C08 integration, L6) -----------------------------------------------------------

    private class GoogleTool(name: String) : Tool {
        override val spec = ToolSpec(name, "a google tool")
        override val statusLabel = "Checking…"
        override val displayName = name
        override val integrationId = GoogleAuth.ID
        override suspend fun execute(args: ToolArgs, ctx: ToolContext) = ToolResult.Ok("ok")
    }

    private val model = ModelInfo("gemma4:12b", setOf("tools"))
    private fun prompt(registry: ToolRegistry) = ToolGuidanceSection { registry }.text(PromptContext(ZonedDateTime.now(), model)).orEmpty()

    @Test fun `Google's tools are offered only while connected, and the prompt says what she can do`() = runBlocking {
        val g = google()
        val withTools = ToolRegistry(listOf(GoogleTool("list_calendar_events"), GoogleTool("search_email")), listOf(g))
        assertTrue(withTools.available(model).isEmpty())
        assertTrue(prompt(withTools).contains("Google (calendar and email) isn't connected"))

        g.signIn(Activity())
        assertEquals(listOf("list_calendar_events", "search_email"), withTools.available(model).map { it.spec.name })
        assertTrue(prompt(withTools).contains("Google: connected as owner@gmail.com (calendar and email)."))

        val noTools = ToolRegistry(emptyList(), listOf(g))
        assertTrue("no promises without tools (L6)", prompt(noTools).contains("you have no calendar or email tools yet"))

        g.disconnect()
        assertTrue(withTools.available(model).isEmpty())
        assertFalse(prompt(withTools).contains("connected as"))
    }

    @Test fun `no token is ever written to the settings store`() = runBlocking {
        val file = File(temp.root, "settings.preferences_pb")
        val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val real = DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = storeScope) { file })
        val g = GoogleAuth(signIn, authorizer, real, "web", scope)
        g.signIn(Activity())
        assertEquals(Outcome.Success("ya29.secret-token"), g.accessToken(setOf(GoogleScope.CalendarEvents)))
        storeScope.coroutineContext.job.cancelAndJoin()
        val bytes = String(file.readBytes(), Charsets.ISO_8859_1)
        assertTrue("the email is kept", bytes.contains("owner@gmail.com"))
        assertFalse("no token", bytes.contains("ya29"))
    }
}
