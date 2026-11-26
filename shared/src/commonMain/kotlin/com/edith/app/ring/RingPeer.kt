package com.edith.app.ring

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.home.LiveFeed
import kotlinx.coroutines.flow.StateFlow

/**
 * One WebRTC connection to a Ring camera, as the shared code sees it (K4.18): the platform makes the real one (on the iPhone, Swift code around Google's
 * WebRTC library) and draws its video. Receive only: the offer asks for one video and one audio section and sends nothing.
 */
interface RingPeer {
    /** Edith's offer as SDP text, with the network candidates already in it (Ring takes the whole offer at once); null when the phone could not make one. */
    suspend fun offer(): String?

    /** Gives the connection Ring's answer; false when it could not be used. */
    suspend fun accept(answer: String): Boolean

    /** Why the connection was lost after it was up (failed, or ended by Ring); null while it holds. */
    val lost: StateFlow<String?>

    fun close()

    /** One line about the connection for the screen while this is proven on a phone: its state, the candidates, Ring's answer, the video. Empty when the platform has none. */
    fun status(): String = ""
}

/** Makes a [RingPeer]; null when this phone cannot start WebRTC. */
fun interface RingPeerFactory {
    fun create(): RingPeer?
}

/** A live feed whose video is a [RingPeer]'s; the screen asks the platform to draw [peer]. */
interface PeerLiveFeed : LiveFeed {
    val peer: RingPeer
}

private class PeerCall(val peer: RingPeer) : RingCall {
    override val lost: StateFlow<String?> get() = peer.lost
    override fun close() = peer.close()
}

/**
 * Ring's live calls over [RingPeer]s (K4.18): the steps Android's `WebRtcClient` takes (offer, Ring's answer, accept), written once over a platform peer so
 * they are tested on the desktop with a fake. Nothing is left open when a step fails or the caller gives up.
 */
class PeerRingLive(private val factory: RingPeerFactory) : RingLive {
    override suspend fun open(exchange: suspend (offerSdp: String) -> String?): Outcome<RingCall> {
        val peer = factory.create() ?: return Outcome.Failure(AppError.ToolFailed("Ring", "this phone couldn't start a live view"))
        var done = false
        try {
            fun fail(why: String): Outcome<RingCall> = Outcome.Failure(AppError.ToolFailed("Ring", why))
            val offer = peer.offer() ?: return fail("this phone couldn't make a live view request")
            val answer = exchange(offer) ?: return fail("Ring didn't start the live view")
            if (!peer.accept(answer)) return fail("Ring's answer wasn't one this phone could use")
            done = true
            return Outcome.Success(PeerCall(peer))
        } finally {
            if (!done) peer.close()   // a failed step, or a cancelled caller
        }
    }

    override fun feed(call: RingCall, lifecycle: LiveFeed): LiveFeed {
        val peer = (call as PeerCall).peer
        return object : PeerLiveFeed, LiveFeed by lifecycle {
            override val peer: RingPeer = peer
        }
    }
}
