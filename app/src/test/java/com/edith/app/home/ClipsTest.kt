package com.edith.app.home

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.home.ui.ClipsUi
import com.edith.app.home.ui.clipTitle
import com.edith.app.home.ui.clipWhen
import com.edith.app.home.ui.clipsEmptyText
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** C27 Clips: what [Home] does with the services' clips, and the words the Clips list uses. */
class ClipsTest {
    private class WithClips(
        override val id: String,
        var page: Outcome<ClipsPage>,
        var urls: Outcome<String> = Outcome.Success("https://example.test/$id.mp4"),
        var hang: Boolean = false,
    ) : HomeBackend {
        override val displayName = id.replaceFirstChar { it.uppercase() }
        override val status = MutableStateFlow(com.edith.app.tools.framework.IntegrationStatus.Connected)
        override val hasClips = true
        val asked = mutableListOf<Pair<Long, Long>>()
        override suspend fun devices(): Outcome<List<HomeDevice>> = Outcome.Success(emptyList())
        override suspend fun state(deviceId: String) = Outcome.Success(DeviceState())
        override suspend fun command(deviceId: String, command: DeviceCommand) = Outcome.Success(DeviceState())
        override suspend fun snapshot(deviceId: String): Outcome<ByteArray> = Outcome.Failure(AppError.Timeout)
        override suspend fun clips(sinceMs: Long, untilMs: Long): Outcome<ClipsPage> { asked += sinceMs to untilMs; if (hang) awaitCancellation(); return page }
        override suspend fun clipUrl(clipId: String): Outcome<String> = urls
    }

    private fun clip(id: String, at: Long, kind: EventKind = EventKind.Motion) = DeviceClip(id, "", "Front Door", at, kind, 20)

    @Test fun `clips from every service come together newest first, each marked with its service`() = runTest {
        val ring = WithClips("ring", Outcome.Success(ClipsPage(listOf(clip("a", 100), clip("c", 300)))))
        val blink = WithClips("blink", Outcome.Success(ClipsPage(listOf(clip("b", 200)))))
        val k = HomeKit(this, backgroundScope, ring, blink, demoOn = false)
        val r = k.home.clips(0, 1000)
        assertEquals(listOf("c", "b", "a"), r.clips.map { it.id })
        assertEquals(listOf("ring", "blink", "ring"), r.clips.map { it.backend })
        assertEquals(listOf(0L to 1000L), ring.asked)
        assertTrue(k.home.hasClips)
    }

    @Test fun `one service failing or hanging doesn't hide the other's clips, and is said`() = runTest {
        val good = WithClips("blink", Outcome.Success(ClipsPage(listOf(clip("b", 200)))))
        val bad = WithClips("ring", Outcome.Failure(AppError.ToolFailed("Ring", "it needs signing in again")))
        val slow = WithClips("slow", Outcome.Success(ClipsPage(emptyList())), hang = true)
        val k = HomeKit(this, backgroundScope, good, bad, slow, demoOn = false)
        val r = k.home.clips(0, 1000)
        assertEquals(listOf("b"), r.clips.map { it.id })
        assertEquals(listOf("Ring didn't answer: it needs signing in again.", "Slow didn't answer: it didn't answer in time."), r.problems)
    }

    @Test fun `no plan counts only when there are no clips from anywhere`() = runTest {
        val noPlan = WithClips("ring", Outcome.Success(ClipsPage(emptyList(), noPlan = true)))
        val k = HomeKit(this, backgroundScope, noPlan, demoOn = false)
        assertTrue(k.home.clips(0, 1).noPlan)
        val other = WithClips("blink", Outcome.Success(ClipsPage(listOf(clip("b", 5)))))
        k.home.register(other)
        testScheduler.runCurrent()
        assertFalse("Blink has clips, so this isn't a no-plan answer", k.home.clips(0, 10).noPlan)
    }

    @Test fun `a clip is played from a fresh address its own service gives`() = runTest {
        val ring = WithClips("ring", Outcome.Success(ClipsPage(emptyList())))
        val k = HomeKit(this, backgroundScope, ring, demoOn = false)
        assertEquals("https://example.test/ring.mp4", (k.home.clipUrl(clip("a", 1).copy(backend = "ring")) as Outcome.Success).value)
        assertTrue(k.home.clipUrl(clip("a", 1).copy(backend = "gone")) is Outcome.Failure)
    }

    @Test fun `a service without clips doesn't show the chip, and its default answers plainly`() = runTest {
        val k = HomeKit(this, backgroundScope)   // the Demo home only
        assertFalse(k.home.hasClips)
        assertTrue(k.fake.clips(0, 1) is Outcome.Failure)
    }

    @Test fun `the words - title, when and the empty cases`() {
        assertEquals("Front Door · A person · 0:23", clipTitle(DeviceClip("1", "ring", "Front Door", 0, EventKind.Person, 23)))
        assertEquals("Front Door · Motion", clipTitle(DeviceClip("1", "ring", "Front Door", 0, EventKind.Motion, null)))
        val now = java.time.Instant.parse("2026-11-14T20:00:00Z").toEpochMilli()
        fun at(s: String) = java.time.Instant.parse(s).toEpochMilli()
        assertEquals("Today 3:42 PM", clipWhen(at("2026-11-14T15:42:00Z"), now, ZoneOffset.UTC))
        assertEquals("Yesterday 9:05 AM", clipWhen(at("2026-11-13T09:05:00Z"), now, ZoneOffset.UTC))
        assertEquals("Wed 11 Nov, 6:30 PM", clipWhen(at("2026-11-11T18:30:00Z"), now, ZoneOffset.UTC))
        assertTrue("Ring Protect" in clipsEmptyText(ClipsUi(noPlan = true, loading = false)))
        assertEquals("Ring didn't answer: x.", clipsEmptyText(ClipsUi(problems = listOf("Ring didn't answer: x."), loading = false)))
        assertEquals("No clips in the last 60 days.", clipsEmptyText(ClipsUi(loading = false)))
    }
}
