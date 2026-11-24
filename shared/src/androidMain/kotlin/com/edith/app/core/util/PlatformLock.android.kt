package com.edith.app.core.util

import java.util.concurrent.locks.ReentrantLock

actual class PlatformLock {
    private val inner = ReentrantLock()
    actual fun lock() = inner.lock()
    actual fun unlock() = inner.unlock()
}
