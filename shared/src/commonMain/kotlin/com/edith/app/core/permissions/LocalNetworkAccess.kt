package com.edith.app.core.permissions

/**
 * Whether Edith may connect to devices on the local network — the owner's PC running Ollama, including the
 * emulator's 10.0.2.2 (04-lessons L23). Android 17 (API 37) requires a runtime permission; without it connections just
 * time out (the Android check is `AndroidLocalNetworkAccess`, in the app). The iPhone asks the owner itself.
 */
interface LocalNetworkAccess {
    fun isGranted(): Boolean

    companion object {
        /** `Manifest.permission.ACCESS_LOCAL_NETWORK`; spelled out because the constant only exists on API 37+. */
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        const val FIRST_API = 37
    }
}
