package com.edith.app.core.util

import platform.Foundation.NSRecursiveLock

actual class PlatformLock {
    private val inner = NSRecursiveLock()
    actual fun lock() = inner.lock()
    actual fun unlock() = inner.unlock()
}
