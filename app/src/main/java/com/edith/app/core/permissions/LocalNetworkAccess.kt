package com.edith.app.core.permissions

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Whether Edith may connect to devices on the local network — the owner's PC running Ollama, including the
 * emulator's 10.0.2.2 (04-lessons L23). Android 17 (API 37) requires the runtime permission below; without it
 * connections just time out. Older Android versions don't have the permission and allow LAN access.
 */
interface LocalNetworkAccess {
    fun isGranted(): Boolean

    companion object {
        /** `Manifest.permission.ACCESS_LOCAL_NETWORK`; spelled out because the constant only exists on API 37+. */
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        const val FIRST_API = 37
    }
}

class AndroidLocalNetworkAccess(private val context: Context) : LocalNetworkAccess {
    override fun isGranted(): Boolean =
        Build.VERSION.SDK_INT < LocalNetworkAccess.FIRST_API ||
            ContextCompat.checkSelfPermission(context, LocalNetworkAccess.PERMISSION) == PackageManager.PERMISSION_GRANTED
}
