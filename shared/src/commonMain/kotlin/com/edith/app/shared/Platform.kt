package com.edith.app.shared

/** The first piece of shared code (K1.1): says which platform it runs on. It proves that Android, the desktop and the iPhone all build and test the same source. */
class SharedGreeting(private val platformName: String) {
    fun hello(): String = "Edith on $platformName"
}
