package com.edith.app.ring

import com.edith.app.core.error.Outcome
import com.edith.app.home.LiveFeed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** K4.18: the steps of a Ring live call over a platform peer, with a fake peer. */
class PeerRingLiveTest {
    private class FakePeer(var offerText: String? = "OFFER", var accepts: Boolean = true) : RingPeer {
        var closed = false
        var answer: String? = null
        override suspend fun offer(): String? = offerText
        override suspend fun accept(answer: String): Boolean { this.answer = answer; return accepts }
        override val lost: MutableStateFlow<String?> = MutableStateFlow(null)
        override fun close() { closed = true }
    }

    private class Lifecycle : LiveFeed {
        override val ended: StateFlow<String?> = MutableStateFlow(null)
        var stopped = false
        override fun stop() { stopped = true }
    }

    @Test fun theOfferGoesToRingAndTheAnswerComesBackToThePeer() = runBlocking {
        val peer = FakePeer()
        var sent: String? = null
        val r = PeerRingLive { peer }.open { offer -> sent = offer; "ANSWER" }
        assertIs<Outcome.Success<RingCall>>(r)
        assertEquals("OFFER", sent)
        assertEquals("ANSWER", peer.answer)
        assertFalse(peer.closed)
        peer.lost.value = "Ring ended the live view."
        assertEquals("Ring ended the live view.", r.value.lost.value)
        r.value.close()
        assertTrue(peer.closed)
    }

    @Test fun noPeerMeansNoLiveView() = runBlocking {
        assertIs<Outcome.Failure>(PeerRingLive { null }.open { "ANSWER" }); Unit
    }

    @Test fun anOfferThatCouldNotBeMadeClosesThePeer() = runBlocking {
        val peer = FakePeer(offerText = null)
        assertIs<Outcome.Failure>(PeerRingLive { peer }.open { error("Ring must not be asked") })
        assertTrue(peer.closed)
    }

    @Test fun ringRefusingClosesThePeer() = runBlocking {
        val peer = FakePeer()
        assertIs<Outcome.Failure>(PeerRingLive { peer }.open { null })
        assertTrue(peer.closed)
    }

    @Test fun anAnswerThePeerCannotUseClosesThePeer() = runBlocking {
        val peer = FakePeer(accepts = false)
        assertIs<Outcome.Failure>(PeerRingLive { peer }.open { "ANSWER" })
        assertTrue(peer.closed)
    }

    @Test fun aCallerThatGivesUpWhileRingThinksClosesThePeer() = runBlocking {
        val peer = FakePeer()
        val waiting = CompletableDeferred<Unit>()
        val job = async(start = CoroutineStart.UNDISPATCHED) { PeerRingLive { peer }.open { waiting.await(); "ANSWER" } }
        job.cancelAndJoin()
        assertTrue(peer.closed)
    }

    @Test fun theFeedKeepsTheLifecycleAndCarriesThePeer() = runBlocking {
        val peer = FakePeer()
        val live = PeerRingLive { peer }
        val call = (live.open { "ANSWER" } as Outcome.Success).value
        val lifecycle = Lifecycle()
        val feed = live.feed(call, lifecycle)
        assertIs<PeerLiveFeed>(feed)
        assertSame(peer, feed.peer)
        feed.stop()
        assertTrue(lifecycle.stopped)
        assertEquals(null, feed.ended.value)
    }
}
