package com.edith.app.ring

import com.edith.app.core.error.Outcome
import com.edith.app.home.LiveFeed
import kotlinx.coroutines.flow.StateFlow

/** One live call to a Ring camera, as the service sees it (C32): how it ended, and a way to end it. The video itself is the platform's ([RingLive.feed]). */
interface RingCall {
    /** Why the connection was lost (failed, or closed by Ring), null while it holds. */
    val lost: StateFlow<String?>
    fun close()
}

/**
 * Makes live calls to Ring cameras (C32): WebRTC, which is the platform's (Android's `WebRtcClient`; the iPhone has none yet, so its service is made with no
 * live view and offers snapshots, events and clips). [open] sends Edith's offer (SDP text) to the service through [exchange] and returns the call, or why not.
 */
interface RingLive {
    suspend fun open(exchange: suspend (offerSdp: String) -> String?): Outcome<RingCall>

    /** The view of [call] the screens draw: [lifecycle] (the service's own end, stop and time limit) with the platform's video added. */
    fun feed(call: RingCall, lifecycle: LiveFeed): LiveFeed
}
