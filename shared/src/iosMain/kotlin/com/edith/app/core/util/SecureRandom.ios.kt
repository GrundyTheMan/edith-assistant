@file:OptIn(ExperimentalForeignApi::class)

package com.edith.app.core.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

/** Apple's secure random generator. Written without a Mac to try it on: the macOS jobs compile it. */
actual fun systemRandom(): RandomSource = RandomSource { bytes ->
    if (bytes.isEmpty()) return@RandomSource
    val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, bytes.size.convert(), it.addressOf(0)) }
    check(status == errSecSuccess) { "The system could not make random bytes" }
}
