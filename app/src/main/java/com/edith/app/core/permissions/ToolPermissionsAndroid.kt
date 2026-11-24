package com.edith.app.core.permissions

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** The tool permissions, checked with Android's own permission system. */
fun ToolPermissions.Companion.android(context: Context) = ToolPermissions { permission ->
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
