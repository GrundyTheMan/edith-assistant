package com.edith.app.tools.web

import java.net.InetAddress

internal actual fun resolveHostAddresses(host: String): List<ByteArray> =
    try {
        InetAddress.getAllByName(host).map { it.address }
    } catch (e: Exception) {
        emptyList()
    }
