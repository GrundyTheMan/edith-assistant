package com.edith.app.alerts.push

import com.edith.app.alerts.AlertChoices
import com.edith.app.jarvis.FeedResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Alerts while closed, stage 2: what is sent to Jarvis and when, with a fake platform and a fake Jarvis. */
@OptIn(ExperimentalCoroutinesApi::class)
class PushRegistrationTest {
    private class FakeSeam : PushSeam {
        override val token = MutableStateFlow<String?>(null)
        override val denied = MutableStateFlow(false)
        var starts = 0
        override fun start() { starts++ }
    }

    private class FakeFeed : PushFeed {
        val registered = mutableListOf<PushRegisterBody>()
        var unregisters = 0
        var registerResult: FeedResult<Unit> = FeedResult.Ok(Unit)
        var statusResult: FeedResult<PushStatus> = FeedResult.Ok(PushStatus(configured = true, registered = true))
        override suspend fun register(body: PushRegisterBody): FeedResult<Unit> { registered += body; return registerResult }
        override suspend fun unregister(): FeedResult<Unit> { unregisters++; return FeedResult.Ok(Unit) }
        override suspend fun status(): FeedResult<PushStatus> = statusResult
    }

    private class Rig(scope: CoroutineScope) {
        val seam = FakeSeam()
        val feed = FakeFeed()
        val enabled = MutableStateFlow(true)
        val choices = MutableStateFlow(AlertChoices(setOf("ring:Doorbell", "blink:Motion")))
        val paired = MutableStateFlow(true)
        val registration = PushRegistration(seam, feed, enabled, choices, paired, env = "production", bundleId = "com.example.app", scope = scope)
            .also { it.run() }
    }

    private fun TestScope.rig() = Rig(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

    @Test fun registersTheTokenAndTheKindsInTheFormJarvisExpects() = runTest {
        val r = rig()
        r.seam.token.value = "abcd1234"
        assertEquals(listOf(PushRegisterBody("abcd1234", "production", "com.example.app", listOf("blink:motion", "ring:doorbell"))), r.feed.registered)
        assertEquals(PushState.Registered, r.registration.state.value)
    }

    @Test fun asksApplesForATokenAndSaysItIsWaiting() = runTest {
        val r = rig()
        assertEquals(PushState.WaitingForApple, r.registration.state.value)
        assertEquals(1, r.seam.starts)
        assertEquals(0, r.feed.registered.size)
    }

    @Test fun doesNotRegisterTheSameThingTwice() = runTest {
        val r = rig()
        r.seam.token.value = "abcd1234"
        r.paired.value = false
        r.paired.value = true     // a pairing that came back with nothing changed
        assertEquals(1, r.feed.registered.size)
    }

    @Test fun registersAgainWhenTheKindsOrTheTokenChange() = runTest {
        val r = rig()
        r.seam.token.value = "abcd1234"
        r.choices.value = AlertChoices(setOf("ring:Doorbell"))
        r.seam.token.value = "ffff0000"
        assertEquals(listOf(listOf("blink:motion", "ring:doorbell"), listOf("ring:doorbell"), listOf("ring:doorbell")), r.feed.registered.map { it.kinds })
        assertEquals(listOf("abcd1234", "abcd1234", "ffff0000"), r.feed.registered.map { it.token })
    }

    @Test fun unregistersWhenSwitchedOffOrNoKindIsLeft() = runTest {
        val r = rig()
        r.seam.token.value = "abcd1234"
        r.enabled.value = false
        assertEquals(1, r.feed.unregisters)
        assertEquals(PushState.Off, r.registration.state.value)
        r.enabled.value = true
        r.choices.value = AlertChoices()
        assertEquals(2, r.feed.unregisters)
        assertEquals(PushState.NoKinds, r.registration.state.value)
    }

    @Test fun nothingIsSentWithoutJarvisOrWithNotificationsRefused() = runTest {
        val r = rig()
        r.seam.token.value = "abcd1234"
        r.feed.registered.clear()
        r.paired.value = false
        assertEquals(PushState.NoJarvis, r.registration.state.value)
        r.paired.value = true
        r.seam.denied.value = true
        assertEquals(PushState.PermissionDenied, r.registration.state.value)
        assertEquals(0, r.feed.unregisters)
    }

    @Test fun anUnreachableJarvisShowsAndRetriesWhenSomethingChanges() = runTest {
        val r = rig()
        r.feed.registerResult = FeedResult.Unreachable
        r.seam.token.value = "abcd1234"
        assertIs<PushState.Failed>(r.registration.state.value)
        r.feed.registerResult = FeedResult.Ok(Unit)
        r.choices.value = AlertChoices(setOf("ring:Doorbell"))
        assertEquals(PushState.Registered, r.registration.state.value)
    }

    @Test fun jarvisRefusingOrForgettingThePhoneIsShown() = runTest {
        val r = rig()
        r.feed.registerResult = FeedResult.Err(400, "BAD_TOKEN", "That token is not valid.", "")
        r.seam.token.value = "abcd1234"
        assertEquals("That token is not valid.", (r.registration.state.value as PushState.Failed).why)
        r.feed.registerResult = FeedResult.Unauthorized
        r.seam.token.value = "ffff0000"
        assertEquals(PushState.NoJarvis, r.registration.state.value)
    }

    @Test fun saysWhenJarvisHasNoPushKeyYet() = runTest {
        val r = rig()
        r.feed.statusResult = FeedResult.Ok(PushStatus(configured = false))
        r.seam.token.value = "abcd1234"
        assertEquals(PushState.JarvisHasNoKey, r.registration.state.value)
    }
}
