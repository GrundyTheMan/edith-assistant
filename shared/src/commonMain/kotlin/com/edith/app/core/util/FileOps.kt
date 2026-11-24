package com.edith.app.core.util

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** The whole file at [path] (K2.9: a recording is read to be sent to the model). */
fun readFileBytes(path: String): ByteArray = SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() }

/** Deletes the file at [path]; a missing file or a refusal is not an error (a leftover recording is harmless). */
fun deleteFileQuietly(path: String) {
    try {
        SystemFileSystem.delete(Path(path), mustExist = false)
    } catch (e: Exception) {
        // nothing to do: the next start clears the recordings folder
    }
}
