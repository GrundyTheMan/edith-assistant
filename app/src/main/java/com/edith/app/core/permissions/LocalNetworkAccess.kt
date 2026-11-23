package com.edith.app.core.permissions

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** [LocalNetworkAccess] backed by Android's runtime permission (L23). */
class AndroidLocalNetworkAccess(private val context: Context) : LocalNetworkAccess {
    override fun isGranted(): Boolean =
        Build.VERSION.SDK_INT < LocalNetworkAccess.FIRST_API ||
            ContextCompat.checkSelfPermission(context, LocalNetworkAccess.PERMISSION) == PackageManager.PERMISSION_GRANTED
}
