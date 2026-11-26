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

final class RingRtcPeer: NSObject, SwiftRtcPeer, RTCPeerConnectionDelegate, RTCVideoViewDelegate {
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

    // What the screen shows under the picture while this is proven on a phone (K4.18); written from WebRTC's threads, read from the main one.
    private let lock = NSLock()
    private var connectionNote = "new"
    private var iceNote = "new"
    private var candidateCount: Int?
    private var answerNote = "not yet"
    private var videoNote = "none yet"

    private func note(_ change: () -> Void) {
        lock.lock(); defer { lock.unlock() }
        change()
    }

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
                self.waitForCandidates {
                    let offer = self.connection.localDescription?.sdp
                    self.note { self.candidateCount = offer.map { $0.components(separatedBy: "a=candidate").count - 1 } }
                    result.done(value: offer)
                }
            }
        }
    }

    func acceptAnswer(sdp: String, result: RtcBoolResult) {
        connection.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: sdp)) { [self] error in
            self.note { self.answerNote = error == nil ? "accepted" : "refused (\(error!.localizedDescription))" }
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
        view.delegate = self
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
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        note { iceNote = Self.words(newState) }
    }
    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {
        if newState == .complete { whenGathered?() }
    }

    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        note { connectionNote = Self.words(newState) }
        switch newState {
        case .failed: reportLost("The live view couldn't connect to Ring.")
        case .disconnected, .closed: reportLost("Ring ended the live view.")
        default: break
        }
    }
}

// MARK: Status for the screen

extension RingRtcPeer {
    func status() -> String {
        lock.lock(); defer { lock.unlock() }
        let candidates = candidateCount.map(String.init) ?? "not gathered yet"
        return "Connection: \(connectionNote) · ICE: \(iceNote)\nCandidates in the offer: \(candidates) · Ring's answer: \(answerNote)\nVideo: \(videoNote)"
    }

    /// The first picture arriving (and any change of its size) is the proof that video flows.
    func videoView(_ videoView: RTCVideoRenderer, didChangeVideoSize size: CGSize) {
        note { videoNote = "\(Int(size.width))×\(Int(size.height))" }
    }

    static func words(_ state: RTCPeerConnectionState) -> String {
        switch state {
        case .new: return "new"
        case .connecting: return "connecting"
        case .connected: return "connected"
        case .disconnected: return "disconnected"
        case .failed: return "failed"
        case .closed: return "closed"
        @unknown default: return "unknown"
        }
    }

    static func words(_ state: RTCIceConnectionState) -> String {
        switch state {
        case .new: return "new"
        case .checking: return "checking"
        case .connected: return "connected"
        case .completed: return "completed"
        case .failed: return "failed"
        case .disconnected: return "disconnected"
        case .closed: return "closed"
        case .count: return "count"
        @unknown default: return "unknown"
        }
    }
}
