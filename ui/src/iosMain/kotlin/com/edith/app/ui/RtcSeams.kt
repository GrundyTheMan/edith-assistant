@file:OptIn(ExperimentalForeignApi::class)

package com.edith.app.ui

import com.edith.app.ring.RingPeer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UIKit.UIView
import kotlin.coroutines.resume

// What the Swift shell (iosApp/) gives the shared code for Ring's live view (K4.18): WebRTC is Google's library, used from Swift. These are plain callback
// interfaces so Swift implements them without Kotlin's coroutines or flows; [BridgedRingPeer] turns one into the shared [RingPeer].

/** A text result (an SDP) or null for "it failed", delivered once, on any thread. */
interface RtcStringResult {
    fun done(value: String?)
}

/** A yes or no, delivered once, on any thread. */
interface RtcBoolResult {
    fun done(ok: Boolean)
}

/** One receive-only WebRTC connection, made in Swift. */
interface SwiftRtcPeer {
    /** Makes the offer, sets it as the local description and waits (up to 3 s) for the network candidates; answers with the SDP, or null. */
    fun createOffer(result: RtcStringResult)

    /** Sets Ring's answer as the remote description. */
    fun acceptAnswer(sdp: String, result: RtcBoolResult)

    /** Told why the connection was lost after it was up; called at most once. */
    fun onLost(listener: RtcStringResult)

    /** A view that draws the call's video. Called on the main thread when the screen shows it. */
    fun makeVideoView(): UIView

    /** Ends the connection; safe to call twice. */
    fun close()
}

/** Makes [SwiftRtcPeer]s; null when WebRTC could not start. (Not named `newPeer`: Kotlin renames methods that start with "new" for Swift.) */
interface SwiftRtcFactory {
    fun makePeer(): SwiftRtcPeer?
}

/** A Swift peer as the shared code's [RingPeer]; the screen asks [swift] for the view. */
class BridgedRingPeer(val swift: SwiftRtcPeer) : RingPeer {
    private val lostState = MutableStateFlow<String?>(null)

    init {
        swift.onLost(object : RtcStringResult {
            override fun done(value: String?) { if (lostState.value == null) lostState.value = value ?: "Ring ended the live view." }
        })
    }

    override val lost: StateFlow<String?> get() = lostState

    override suspend fun offer(): String? = suspendCancellableCoroutine { c ->
        swift.createOffer(object : RtcStringResult { override fun done(value: String?) { c.resume(value) } })
    }

    override suspend fun accept(answer: String): Boolean = suspendCancellableCoroutine { c ->
        swift.acceptAnswer(answer, object : RtcBoolResult { override fun done(ok: Boolean) { c.resume(ok) } })
    }

    override fun close() = swift.close()
}
