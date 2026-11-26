import Foundation
import UIKit
import WebRTC
import EdithUI

/// Ring's live view on the iPhone (K4.18): one receive-only WebRTC connection, made with Google's library (the `stasel/WebRTC` package). The shared Kotlin code
/// does the signalling with Ring and asks this for the offer, gives it Ring's answer and shows the view it draws; the same steps as Android's `WebRtcClient`.
/// Written without a Mac to try it on: CI compiles it, and the owner's phone is the first real run.
final class RingRtcFactory: NSObject, SwiftRtcFactory {
    func makePeer() -> SwiftRtcPeer? {
        RingRtcPeer.make()
    }
}

final class RingRtcPeer: NSObject, SwiftRtcPeer, RTCPeerConnectionDelegate {
    /// One factory for the whole app; its first use starts the library.
    private static let factory: RTCPeerConnectionFactory = {
        RTCInitializeSSL()
        return RTCPeerConnectionFactory(encoderFactory: RTCDefaultVideoEncoderFactory(), decoderFactory: RTCDefaultVideoDecoderFactory())
    }()

    /// Ring's own STUN servers (as Jarvis and Android use).
    private static let iceServers = [
        "stun:stun.kinesisvideo.us-east-1.amazonaws.com:443",
        "stun:stun.kinesisvideo.us-east-2.amazonaws.com:443",
    ]

    private let connection: RTCPeerConnection
    private var videoTrack: RTCVideoTrack?
    private var lostListener: RtcStringResult?
    private var whenGathered: (() -> Void)?
    private var closedByUs = false
    private var lostReported = false

    private init(connection: RTCPeerConnection) {
        self.connection = connection
        super.init()
    }

    static func make() -> RingRtcPeer? {
        let config = RTCConfiguration()
        config.iceServers = [RTCIceServer(urlStrings: iceServers)]
        config.sdpSemantics = .unifiedPlan
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let connection = factory.peerConnection(with: config, constraints: constraints, delegate: nil) else { return nil }
        let peer = RingRtcPeer(connection: connection)
        connection.delegate = peer
        // Receive only: one video and one audio section, nothing sent. Ring wants both; the audio is muted, since no sound is wanted.
        let recvOnly = RTCRtpTransceiverInit()
        recvOnly.direction = .recvOnly
        let video = connection.addTransceiver(of: .video, init: recvOnly)
        let audio = connection.addTransceiver(of: .audio, init: recvOnly)
        (audio?.receiver.track as? RTCAudioTrack)?.isEnabled = false
        guard let track = video?.receiver.track as? RTCVideoTrack else {
            connection.close()
            return nil
        }
        peer.videoTrack = track
        return peer
    }

    // MARK: SwiftRtcPeer

    func createOffer(result: RtcStringResult) {
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        connection.offer(for: constraints) { [weak self] sdp, error in
            guard let self = self, let sdp = sdp, error == nil else { result.done(value: nil); return }
            self.connection.setLocalDescription(sdp) { error in
                if error != nil { result.done(value: nil); return }
                // Ring takes the whole offer at once, with no trickled candidates, so the candidates are gathered first, for at most 3 seconds.
                self.waitForCandidates { result.done(value: self.connection.localDescription?.sdp) }
            }
        }
    }

    func acceptAnswer(sdp: String, result: RtcBoolResult) {
        connection.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: sdp)) { error in
            result.done(ok: error == nil)
        }
    }

    func onLost(listener: RtcStringResult) {
        lostListener = listener
    }

    func makeVideoView() -> UIView {
        let view = RTCMTLVideoView(frame: .zero)
        view.videoContentMode = .scaleAspectFit
        view.backgroundColor = .black
        videoTrack?.add(view)
        return view
    }

    func close() {
        closedByUs = true
        connection.close()
    }

    // MARK: Gathering candidates

    private func waitForCandidates(_ done: @escaping () -> Void) {
        if connection.iceGatheringState == .complete { done(); return }
        var finished = false
        let finish = {
            DispatchQueue.main.async {
                if finished { return }
                finished = true
                self.whenGathered = nil
                done()
            }
        }
        whenGathered = finish
        DispatchQueue.main.asyncAfter(deadline: .now() + 3, execute: finish)
    }

    private func reportLost(_ why: String) {
        if closedByUs || lostReported { return }
        lostReported = true
        lostListener?.done(value: why)
    }

    // MARK: RTCPeerConnectionDelegate

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        if newState == .complete { whenGathered?() }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        switch newState {
        case .failed: reportLost("The live view couldn't connect to Ring.")
        case .disconnected, .closed: reportLost("Ring ended the live view.")
        default: break
        }
    }
}
