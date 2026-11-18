package com.edith.app.ring

import android.content.Context
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume

/** One WebRTC call to a Ring camera (C32): the video [track], drawn with [eglContext]. [close] ends the call. */
interface RtcCall {
    val track: VideoTrack
    val eglContext: EglBase.Context
    /** Why the connection was lost (failed, or closed by Ring), null while it holds. */
    val lost: StateFlow<String?>
    fun close()
}

/**
 * Makes WebRTC calls (C32). [exchange] sends Edith's offer (SDP text) to the service and returns its answer, or null when
 * the service refused; the real implementation is [WebRtcClient], tests pass a fake because the native library can't run on the JVM.
 */
interface RingRtc {
    suspend fun open(exchange: suspend (offerSdp: String) -> String?): Outcome<RtcCall>
}

/**
 * Ring's WebRTC, with the Android library (`org.webrtc`, from Stream's build; the owner approved adding it, 2026-11-17).
 * Receive only: one video and one audio section, nothing sent (Ring wants both; the audio is muted, since no sound is wanted).
 * Ring takes the whole offer at once with no trickled candidates, so candidates are gathered first, for at most 3 seconds
 * (as Jarvis's `RingLive.tsx` does).
 */
class WebRtcClient(private val context: Context) : RingRtc {
    private val egl: EglBase by lazy { EglBase.create() }
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
        PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setAudioDeviceModule(JavaAudioDeviceModule.builder(context.applicationContext).createAudioDeviceModule())
            .createPeerConnectionFactory()
    }

    override suspend fun open(exchange: suspend (offerSdp: String) -> String?): Outcome<RtcCall> = withContext(Dispatchers.Default) {
        val lostState = MutableStateFlow<String?>(null)
        val gathered = CompletableDeferred<Unit>()
        val pc = try {
            factory.createPeerConnection(
                PeerConnection.RTCConfiguration(ICE_SERVERS.map { PeerConnection.IceServer.builder(it).createIceServer() }).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                },
                object : PeerConnection.Observer {
                    override fun onSignalingChange(s: PeerConnection.SignalingState) = Unit
                    override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) = Unit
                    override fun onIceConnectionReceivingChange(b: Boolean) = Unit
                    override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) { if (s == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit) }
                    override fun onIceCandidate(c: IceCandidate) = Unit
                    override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) = Unit
                    override fun onAddStream(s: MediaStream) = Unit
                    override fun onRemoveStream(s: MediaStream) = Unit
                    override fun onDataChannel(d: org.webrtc.DataChannel) = Unit
                    override fun onRenegotiationNeeded() = Unit
                    override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                        when (s) {
                            PeerConnection.PeerConnectionState.FAILED -> lostState.value = "The live view couldn't connect to Ring."
                            PeerConnection.PeerConnectionState.DISCONNECTED, PeerConnection.PeerConnectionState.CLOSED -> if (lostState.value == null) lostState.value = "Ring ended the live view."
                            else -> Unit
                        }
                    }
                },
            )
        } catch (e: Exception) {
            Log.w(LogTag.Home, "WebRTC couldn't start", e)
            null
        } ?: return@withContext Outcome.Failure(AppError.ToolFailed("Ring", "this phone couldn't start a live view"))

        fun fail(why: String): Outcome<RtcCall> { runCatching { pc.close(); pc.dispose() }; return Outcome.Failure(AppError.ToolFailed("Ring", why)) }
        try {
            val recvOnly = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            val video = pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, recvOnly)
            val audio = pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recvOnly)
            (audio.receiver.track() as? AudioTrack)?.setEnabled(false)
            val videoTrack = video.receiver.track() as? VideoTrack ?: return@withContext fail("this phone couldn't make a video track")

            val offer = pc.awaitOffer() ?: return@withContext fail("this phone couldn't make a live view request")
            if (!pc.awaitLocal(offer)) return@withContext fail("this phone couldn't make a live view request")
            withTimeoutOrNull(GATHER_MS) { gathered.await() }   // whatever candidates there are by then go in the offer
            val sdp = pc.localDescription?.description ?: return@withContext fail("this phone couldn't make a live view request")
            val answer = exchange(sdp) ?: return@withContext fail("Ring didn't start the live view")
            if (!pc.awaitRemote(SessionDescription(SessionDescription.Type.ANSWER, answer))) return@withContext fail("Ring's answer wasn't one this phone could use")
            Outcome.Success(object : RtcCall {
                override val track = videoTrack
                override val eglContext: EglBase.Context = egl.eglBaseContext
                override val lost: StateFlow<String?> = lostState.asStateFlow()
                override fun close() { runCatching { pc.close(); pc.dispose() } }
            })
        } catch (e: Exception) {
            Log.w(LogTag.Home, "A Ring live view failed to start", e)
            fail("the live view couldn't be started")
        }
    }

    private suspend fun PeerConnection.awaitOffer(): SessionDescription? = suspendCancellableCoroutine { cont ->
        createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(d: SessionDescription) { cont.resume(d) }
            override fun onCreateFailure(e: String?) { cont.resume(null) }
        }, MediaConstraints())
    }

    private suspend fun PeerConnection.awaitLocal(d: SessionDescription): Boolean = suspendCancellableCoroutine { cont ->
        setLocalDescription(object : SdpAdapter() {
            override fun onSetSuccess() { cont.resume(true) }
            override fun onSetFailure(e: String?) { cont.resume(false) }
        }, d)
    }

    private suspend fun PeerConnection.awaitRemote(d: SessionDescription): Boolean = suspendCancellableCoroutine { cont ->
        setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() { cont.resume(true) }
            override fun onSetFailure(e: String?) { cont.resume(false) }
        }, d)
    }

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(e: String?) = Unit
        override fun onSetFailure(e: String?) = Unit
    }

    companion object {
        /** Ring's own STUN servers (Jarvis's `RING_ICE_SERVERS`). */
        val ICE_SERVERS = listOf("stun:stun.kinesisvideo.us-east-1.amazonaws.com:443", "stun:stun.kinesisvideo.us-east-2.amazonaws.com:443")
        private const val GATHER_MS = 3_000L
    }
}
