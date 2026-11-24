package com.edith.app.core.crypto

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    // HMAC pads a short key with zeros, so an empty key is the same as one zero byte (Java refuses an empty SecretKeySpec; CommonCrypto and Node accept it).
    val usable = if (key.isEmpty()) byteArrayOf(0) else key
    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(usable, "HmacSHA256")) }
    return mac.doFinal(data)
}
