package com.edith.app.core.net

import android.net.TrafficStats
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Labels every socket Edith opens with [tag] for Android's per-app traffic accounting. Without a label, debug
 * builds' StrictMode reports "Untagged socket detected" on every connection.
 */
class TaggedSocketFactory(
    private val tag: Int = EDITH_TRAFFIC_TAG,
    private val delegate: SocketFactory = getDefault(),
) : SocketFactory() {
    override fun createSocket(): Socket = tagged(delegate.createSocket())
    override fun createSocket(host: String?, port: Int): Socket = tagged(delegate.createSocket(host, port))
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        tagged(delegate.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress?, port: Int): Socket = tagged(delegate.createSocket(host, port))
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        tagged(delegate.createSocket(address, port, localAddress, localPort))

    /** tagSocket applies the calling thread's tag, so set that first. */
    private fun tagged(socket: Socket): Socket {
        TrafficStats.setThreadStatsTag(tag)
        TrafficStats.tagSocket(socket)
        return socket
    }

    companion object {
        /** Any stable app-chosen number; 0x0ED1 reads as "EDI". */
        const val EDITH_TRAFFIC_TAG = 0x0ED1
    }
}
