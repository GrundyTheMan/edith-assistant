package com.edith.app.printer.feed

import com.edith.app.jarvis.FeedResult
import com.edith.app.testing.FakeClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** C39 E3: the session that follows the printer while the page is on screen. A fake feed and fake routes; virtual time. */
class PrinterSessionTest {
    private val shared = SharedPrinter(name = "AD5X", control = true)
    private val clock = FakeClock(Instant.ofEpochMilli(1_000_000))

    private fun TestScope.session(feed: PrinterFeedSource) = PrinterSession(feed, clock, backgroundScope as CoroutineScope)
    private fun ready(routes: FakePrinterRoutes) = PrinterAccess.Ready(routes, shared)
    private fun PrinterSession.live() = state.value as PrinterScreenState.Live
    private fun PrinterSession.away() = state.value as PrinterScreenState.JarvisAway

    @Test fun `the first question has no revision and does not wait, the next waits for a change`() = runTest {
        val routes = FakePrinterRoutes()
        val s = session(FakePrinterFeed(ready(routes)))
        assertEquals(PrinterScreenState.Connecting, s.state.value)
        s.attach(); runCurrent()
        routes.say(rev = 5, epoch = 9); runCurrent()
        assertEquals(5L, s.live().data.rev)
        assertEquals(2, routes.asked.size)
        assertNull(routes.asked[0].after); assertEquals(0, routes.asked[0].waitS)
        assertEquals(5L, routes.asked[1].after); assertEquals(9L, routes.asked[1].epoch); assertEquals(20, routes.asked[1].waitS)
    }

    @Test fun `an unchanged revision is Jarvis saying nothing new and changes nothing`() = runTest {
        val routes = FakePrinterRoutes()
        val s = session(FakePrinterFeed(ready(routes)))
        s.attach(); runCurrent()
        routes.say(5); runCurrent()
        val first = s.state.value
        clock.advance(java.time.Duration.ofSeconds(20))
        routes.say(5); runCurrent()
        assertSame(first, s.state.value)
        routes.say(6); runCurrent()
        assertEquals(6L, s.live().data.rev)
        assertEquals(4, routes.asked.size)
    }

    @Test fun `a new epoch means Jarvis restarted so take the answer even with a lower revision, and send the new epoch`() = runTest {
        val routes = FakePrinterRoutes()
        val s = session(FakePrinterFeed(ready(routes)))
        s.attach(); runCurrent()
        routes.say(rev = 500, epoch = 1); runCurrent()
        routes.say(rev = 3, epoch = 2); runCurrent()
        assertEquals(3L, s.live().data.rev)
        assertEquals(2L, routes.asked.last().epoch)
        assertEquals(3L, routes.asked.last().after)
    }

    @Test fun `the offset is Jarvis's clock minus the phone's`() = runTest {
        val routes = FakePrinterRoutes()
        val s = session(FakePrinterFeed(ready(routes)))
        s.attach(); runCurrent()
        routes.say(1, serverTime = 1_005_000); runCurrent()
        assertEquals(5_000L, s.live().offsetMs)
        assertEquals(1_000_000L, s.live().receivedAt)
        assertFalse(s.live().stale)
    }

    @Test fun `quiet is sent when the page says so`() = runTest {
        val routes = FakePrinterRoutes()
        val s = session(FakePrinterFeed(ready(routes)))
        s.quiet = true
        s.attach(); runCurrent()
        assertTrue(routes.asked[0].quiet)
    }

    @Test fun `no Jarvis and not shared are shown and nothing is asked`() = runTest {
        val none = session(FakePrinterFeed(PrinterAccess.NoJarvis)); none.attach(); runCurrent()
        assertEquals(PrinterScreenState.NoJarvis, none.state.value)
        val off = session(FakePrinterFeed(PrinterAccess.NotShared)); off.attach(); runCurrent()
        assertEquals(PrinterScreenState.NotShared, off.state.value)
    }

    @Test fun `Jarvis stopping the share ends the loop`() = runTest {
        val routes = FakePrinterRoutes()
        val s = session(FakePrinterFeed(ready(routes)))
        s.attach(); runCurrent()
        routes.say(1); runCurrent()
        routes.fail(FeedResult.Err(403, "PRINTER_NOT_SHARED", "no", ""))
        runCurrent()
        assertEquals(PrinterScreenState.NotShared, s.state.value)
        advanceTimeBy(60_000)
        assertEquals(2, routes.asked.size)
    }

    @Test fun `a removed phone is told to pair again and the loop ends`() = runTest {
        val routes = FakePrinterRoutes()
        val s = session(FakePrinterFeed(ready(routes)))
        s.attach(); runCurrent()
        routes.say(1); runCurrent()
        routes.fail(FeedResult.Unauthorized); runCurrent()
        assertEquals(PrinterWords.UNAUTHORIZED, s.away().why)
        assertEquals(1L, s.away().last?.rev)
        advanceTimeBy(60_000)
        assertEquals(2, routes.asked.size)
    }

    @Test fun `when Jarvis can't be reached the last page stays, and the wait grows 2 4 8 up to 30 seconds`() = runTest {
        val routes = FakePrinterRoutes()
        val s = session(FakePrinterFeed(ready(routes)))
        s.attach(); runCurrent()
        routes.say(1); runCurrent()
        val waits = listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
        for ((i, wait) in waits.withIndex()) {
            routes.fail(FeedResult.Unreachable); runCurrent()
            val before = routes.asked.size
            assertEquals(PrinterWords.UNREACHABLE, s.away().why)
            assertEquals("the last answer is kept", 1L, s.away().last?.rev)
            advanceTimeBy(wait - 1); runCurrent()
            assertEquals("still waiting after failure ${i + 1}", before, routes.asked.size)
            advanceTimeBy(1); runCurrent()
            assertEquals("asks again after $wait ms", before + 1, routes.asked.size)
            assertNull("asks at once, with no revision, after a failure", routes.asked.last().after)
        }
    }

    @Test fun `recovery shows the printer again and starts the waits over`() = runTest {
        val routes = FakePrinterRoutes()
        val s = session(FakePrinterFeed(ready(routes)))
        s.attach(); runCurrent()
        routes.fail(FeedResult.Unreachable); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        routes.fail(FeedResult.Unreachable); runCurrent()
        advanceTimeBy(4_000); runCurrent()
        routes.say(7); runCurrent()
        assertEquals(7L, s.live().data.rev)
        routes.fail(FeedResult.Unreachable); runCurrent()
        val before = routes.asked.size
        advanceTimeBy(2_000); runCurrent()
        assertEquals("back to a 2 second wait", before + 1, routes.asked.size)
    }

    @Test fun `too many requests waits 3 seconds and goes on`() = runTest {
        val routes = FakePrinterRoutes()
        val s = session(FakePrinterFeed(ready(routes)))
        s.attach(); runCurrent()
        routes.say(1); runCurrent()
        routes.fail(FeedResult.Err(429, "TOO_MANY", "slow down", "")); runCurrent()
        val before = routes.asked.size
        advanceTimeBy(2_999); runCurrent()
        assertEquals(before, routes.asked.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(before + 1, routes.asked.size)
        assertTrue("the page still shows the printer", s.state.value is PrinterScreenState.Live)
    }

    @Test fun `an unreachable Jarvis at the start is shown, then tried again`() = runTest {
        val routes = FakePrinterRoutes()
        val s = session(FakePrinterFeed(PrinterAccess.Away(PrinterWords.UNREACHABLE), ready(routes)))
        s.attach(); runCurrent()
        assertEquals(PrinterWords.UNREACHABLE, s.away().why)
        assertNull(s.away().last)
        advanceTimeBy(2_000); runCurrent()
        routes.say(1); runCurrent()
        assertEquals(1L, s.live().data.rev)
    }

    @Test fun `a phone Jarvis has removed at the start is not retried`() = runTest {
        val feed = FakePrinterFeed(PrinterAccess.Away(PrinterWords.UNAUTHORIZED, removed = true))
        val s = session(feed)
        s.attach(); runCurrent()
        advanceTimeBy(120_000)
        assertEquals(1, feed.calls)
        assertEquals(PrinterWords.UNAUTHORIZED, s.away().why)
    }

    @Test fun `leaving stops the asking, and coming back shows the old page marked stale until a new answer`() = runTest {
        val routes = FakePrinterRoutes()
        val s = session(FakePrinterFeed(ready(routes)))
        s.attach(); runCurrent()
        routes.say(4); runCurrent()
        s.detach(); runCurrent()
        assertTrue(s.live().stale)
        val asked = routes.asked.size
        advanceTimeBy(60_000); runCurrent()
        assertEquals("no questions while away", asked, routes.asked.size)
        s.attach(); runCurrent()
        assertTrue("the old page, marked stale", s.live().stale)
        assertEquals(4L, s.live().data.rev)
        routes.say(5); runCurrent()
        assertFalse(s.live().stale)
        assertEquals(5L, s.live().data.rev)
    }

    @Test fun `leaving turns Controls off only when they are on`() = runTest {
        val on = FakePrinterRoutes()
        val a = session(FakePrinterFeed(ready(on)))
        a.attach(); runCurrent()
        on.say(1, armed = true); runCurrent()
        a.detach(); runCurrent()
        assertEquals(listOf(false), on.controlsCalls)

        val off = FakePrinterRoutes()
        val b = session(FakePrinterFeed(ready(off)))
        b.attach(); runCurrent()
        off.say(1, armed = false); runCurrent()
        b.detach(); runCurrent()
        assertTrue(off.controlsCalls.isEmpty())
    }

    @Test fun `refreshNow asks again at once without a revision`() = runTest {
        val routes = FakePrinterRoutes()
        val s = session(FakePrinterFeed(ready(routes)))
        s.attach(); runCurrent()
        routes.say(3); runCurrent()
        s.refreshNow(); runCurrent()
        assertNull(routes.asked.last().after)
        routes.say(4); runCurrent()
        assertEquals(4L, s.live().data.rev)
    }

    @Test fun `the waits are 2 seconds doubling to 30`() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (1..6).map { PrinterSession.backoff(it) })
    }
}
