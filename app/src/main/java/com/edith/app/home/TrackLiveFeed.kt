package com.edith.app.home

/** Video as a WebRTC track, drawn with [eglContext] (Ring). Android only: the iPhone gets its own WebRTC in K4.5 (plan K0.6b), so this stays out of the shared module. */
interface TrackLiveFeed : LiveFeed {
    val track: org.webrtc.VideoTrack
    val eglContext: org.webrtc.EglBase.Context
}
