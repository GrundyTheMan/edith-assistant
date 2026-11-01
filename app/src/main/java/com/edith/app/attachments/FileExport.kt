package com.edith.app.attachments

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import java.io.File

/** Save and Share on Edith's file cards (C16 §4). Blocking: call off the main thread. */
object FileExport {
    /** Matches the provider's authority in the manifest (`${applicationId}.files`). */
    const val PROVIDER_SUFFIX = ".files"
    private const val SHARE_DIR = "share"

    /** Writes [source]'s exact bytes to [target] (where the owner chose to save). False if it couldn't. */
    fun save(context: Context, source: File, target: Uri): Boolean = try {
        context.contentResolver.openOutputStream(target, "wt")?.use { out -> source.inputStream().use { it.copyTo(out) } } != null
    } catch (e: Exception) {
        Log.w(LogTag.Chat, "Couldn't save a file", e)
        false
    }

    /**
     * A content URI for sharing [file] under [name] (the stored file has a random name): a copy in `cacheDir/share`,
     * which is emptied each time and is the only folder the provider exposes.
     */
    fun shareUri(context: Context, file: File, name: String): Uri {
        val dir = File(context.cacheDir, SHARE_DIR).apply { deleteRecursively(); mkdirs() }
        val copy = File(dir, ReplyFormat.sanitize(name)).also { file.copyTo(it, overwrite = true) }
        return FileProvider.getUriForFile(context, context.packageName + PROVIDER_SUFFIX, copy)
    }
}
