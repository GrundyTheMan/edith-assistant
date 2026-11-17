package com.edith.app.alerts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The owner tapped a camera alert (C33): `MainActivity` raises this, the navigation brings the Home tab forward, and the
 * Home tab opens its Clips list and calls [consume]. A flag, not an event, so a tap that arrives before the screens exist is not lost.
 */
object AlertTaps {
    private val pending = MutableStateFlow(false)
    val openClips: StateFlow<Boolean> = pending.asStateFlow()

    fun request() { pending.value = true }
    fun consume() { pending.value = false }
}
