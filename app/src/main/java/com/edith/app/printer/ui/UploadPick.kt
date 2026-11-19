package com.edith.app.printer.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

/** What the system file picker gave back: the file's display name and its size in bytes (-1 when the provider did not say). */
internal class PickedFile(val name: String, val size: Long)

internal object UploadPick {
    /** Reads the name and size of [uri] without opening the file's contents. A provider that answers nothing gives an empty name and size -1 (both then fail the upload checks, in words). */
    fun describe(resolver: ContentResolver, uri: Uri): PickedFile {
        var name = ""
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n).orEmpty()
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        }
        return PickedFile(name, size)
    }
}
