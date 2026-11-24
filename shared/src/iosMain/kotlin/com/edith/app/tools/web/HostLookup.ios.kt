@file:OptIn(ExperimentalForeignApi::class)

package com.edith.app.tools.web

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.AF_UNSPEC
import platform.posix.SOCK_STREAM
import platform.posix.addrinfo
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6

/** The system's name lookup (getaddrinfo), read into plain bytes. Written without a Mac to try it on: the macOS job is the first check. */
internal actual fun resolveHostAddresses(host: String): List<ByteArray> = memScoped {
    val hints = alloc<addrinfo>()   // memory from alloc starts zeroed
    hints.ai_family = AF_UNSPEC
    hints.ai_socktype = SOCK_STREAM
    val result = alloc<CPointerVar<addrinfo>>()
    if (getaddrinfo(host, null, hints.ptr, result.ptr) != 0) return@memScoped emptyList()
    val found = mutableListOf<ByteArray>()
    try {
        var entry = result.value
        while (entry != null) {
            val info = entry.pointed
            val address = info.ai_addr
            if (address != null) {
                when (info.ai_family) {
                    AF_INET -> {
                        val raw = address.reinterpret<sockaddr_in>().pointed.sin_addr.ptr.reinterpret<ByteVar>()
                        found += ByteArray(4) { raw[it] }
                    }
                    AF_INET6 -> {
                        val raw = address.reinterpret<sockaddr_in6>().pointed.sin6_addr.ptr.reinterpret<ByteVar>()
                        found += ByteArray(16) { raw[it] }
                    }
                }
            }
            entry = info.ai_next
        }
    } finally {
        freeaddrinfo(result.value)
    }
    found
}
