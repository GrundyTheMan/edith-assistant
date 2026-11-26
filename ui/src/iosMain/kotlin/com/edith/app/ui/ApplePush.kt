package com.edith.app.ui

import com.edith.app.alerts.push.PushSeam
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What Swift does when Kotlin asks for push: ask the owner's permission and register with Apple. Implemented in `iosApp/`. */
interface SwiftPushRequester {
    fun requestPermissionAndRegister()
}

/**
 * Apple's push (K6.2, `docs/design/ios-alerts-while-closed.md`) as the shared [PushSeam]: Swift owns the permission prompt and the token and reports them here
 * (`ApplePush.shared`), Kotlin reads them as flows. A tap on a push arrives as [tapped]. A plain object so Swift reaches it without any Kotlin coroutines.
 */
object ApplePush : PushSeam {
    private val _token = MutableStateFlow<String?>(null)
    private val _denied = MutableStateFlow(false)
    private val _tapped = MutableStateFlow(0)

    override val token: StateFlow<String?> get() = _token
    override val denied: StateFlow<Boolean> get() = _denied

    /** Counts taps on a push; the screen opens the clips each time it changes. */
    val tapped: StateFlow<Int> get() = _tapped

    /** Set by the Swift shell at launch. */
    var requester: SwiftPushRequester? = null

    override fun start() { requester?.requestPermissionAndRegister() }

    /** Swift: Apple gave this phone a token (hex). */
    fun onToken(hex: String) { _token.value = hex; _denied.value = false }

    /** Swift: the owner refused notifications, or Apple refused to register. */
    fun onDenied() { _denied.value = true }

    /** Swift: the owner tapped a push. */
    fun onTapped() { _tapped.value += 1 }
}
