package com.edith.app.core.lifecycle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Edith is on screen: [MainActivity][com.edith.app.MainActivity] (the only activity) sets it in
 * onStart/onStop. Android 17 blocks an app's network while it has nothing on screen and no foreground service
 * (`blocked=APP_BACKGROUND`, lesson L24, again in C17b), so work that needs the network can wait for this.
 */
object AppVisibility {
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    fun set(visible: Boolean) {
        _visible.value = visible
    }
}
