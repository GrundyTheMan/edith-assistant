package com.edith.app.core.crypto

/** SHA-256 of [data] (K2.4: Jarvis's certificate fingerprint). Android and the desktop use `MessageDigest`, the iPhone CommonCrypto. */
expect fun sha256(data: ByteArray): ByteArray

/** HMAC-SHA256 of [data] under [key] (K2.4: the pairing proof). */
expect fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

private const val HEX = "0123456789abcdef"

/** Lowercase hex, two characters a byte (`String.format` is not available to Kotlin/Native). */
fun ByteArray.toLowerHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
    }
    return out.toString()
}
