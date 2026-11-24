package com.edith.app.core.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.kCCHmacAlgSHA256

// Built only on a Mac (the iPhone targets are skipped on Windows): the macOS CI job (shared-ios.yml) compiles and runs this.
// An empty array has no first element to take the address of, so it is passed as a null pointer with length 0.

@OptIn(ExperimentalForeignApi::class)
actual fun sha256(data: ByteArray): ByteArray {
    val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
    out.usePinned { o ->
        if (data.isEmpty()) {
            CC_SHA256(null, 0u, o.addressOf(0).reinterpret())
        } else {
            data.usePinned { d -> CC_SHA256(d.addressOf(0), data.size.toUInt(), o.addressOf(0).reinterpret()) }
        }
    }
    return out
}

@OptIn(ExperimentalForeignApi::class)
actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
    out.usePinned { o ->
        val macOut = o.addressOf(0)
        when {
            key.isEmpty() && data.isEmpty() -> CCHmac(kCCHmacAlgSHA256, null, 0u, null, 0u, macOut)
            key.isEmpty() -> data.usePinned { d -> CCHmac(kCCHmacAlgSHA256, null, 0u, d.addressOf(0), data.size.toULong(), macOut) }
            data.isEmpty() -> key.usePinned { k -> CCHmac(kCCHmacAlgSHA256, k.addressOf(0), key.size.toULong(), null, 0u, macOut) }
            else -> key.usePinned { k -> data.usePinned { d -> CCHmac(kCCHmacAlgSHA256, k.addressOf(0), key.size.toULong(), d.addressOf(0), data.size.toULong(), macOut) } }
        }
    }
    return out
}
