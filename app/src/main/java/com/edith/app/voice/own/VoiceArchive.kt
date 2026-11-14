package com.edith.app.voice.own

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException

/** The archive isn't a voice Edith can use, or tries something a voice never would. */
class BadVoiceArchive(message: String) : IOException(message)

/**
 * Unpacks a voice's `.tar.bz2` (C36 §3). The file is **untrusted** even after its SHA-256 matched (that only proves
 * it's the file Edith expected), so:
 *  - an entry with an absolute path, `..`, a drive letter or a backslash, or that is a link, stops everything (zip-slip);
 *  - the number of entries and the total size are capped;
 *  - only the model (`*.onnx`), `tokens.txt`, `espeak-ng-data/` and what is in it and the model card are written, under fixed names;
 *  - the expected files must all be there at the end.
 * The archive has one top folder (`vits-piper-<voice>/`), which is dropped.
 */
object VoiceArchive {
    const val MAX_ENTRIES = 5_000
    const val MAX_TOTAL_BYTES = 300L * 1024 * 1024

    fun extract(archive: File, into: File, maxEntries: Int = MAX_ENTRIES, maxBytes: Long = MAX_TOTAL_BYTES) {
        into.deleteRecursively()
        into.mkdirs()
        val root = into.canonicalFile
        var entries = 0
        var total = 0L
        var model = false
        var tokens = false
        var espeakFiles = 0
        archive.inputStream().buffered().use { raw ->
            TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(raw))).use { tar ->
                while (true) {
                    val entry: TarArchiveEntry = tar.nextEntry ?: break
                    if (++entries > maxEntries) throw BadVoiceArchive("Too many files in the voice")
                    if (entry.isSymbolicLink || entry.isLink) throw BadVoiceArchive("The voice contains a link")
                    val path = safeSegments(entry.name)
                    if (!entry.isDirectory) {
                        total += entry.size
                        if (total > maxBytes) throw BadVoiceArchive("The voice is bigger than expected")
                    }
                    if (entry.isDirectory || !entry.isFile) continue
                    val target = targetFor(path) ?: continue   // not one of the files Edith uses
                    if (target == OwnVoice.MODEL && model) throw BadVoiceArchive("The voice has two models")
                    val out = File(root, target).canonicalFile
                    if (!out.path.startsWith(root.path + File.separator)) throw BadVoiceArchive("A file points outside the voice")
                    out.parentFile!!.mkdirs()
                    out.outputStream().buffered().use { sink ->
                        val copied = tar.copyTo(sink)
                        if (copied != entry.size) throw BadVoiceArchive("A file in the voice is cut short")
                    }
                    when {
                        target == OwnVoice.MODEL -> model = true
                        target == OwnVoice.TOKENS -> tokens = true
                        target.startsWith(OwnVoice.ESPEAK_DATA + "/") -> espeakFiles++
                    }
                }
            }
        }
        if (!model || !tokens || espeakFiles == 0) throw BadVoiceArchive("The voice is missing files")
    }

    /** The entry's path split into names, or an exception if it could leave the folder. */
    private fun safeSegments(name: String): List<String> {
        if (name.startsWith("/") || name.startsWith("\\") || '\\' in name || ':' in name || '\u0000' in name) {
            throw BadVoiceArchive("A file has a path that isn't allowed")
        }
        val segments = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) throw BadVoiceArchive("A file has a path that isn't allowed")
        return segments
    }

    /** Where a file of the archive goes inside the voice's folder, or null if it isn't used. */
    private fun targetFor(path: List<String>): String? {
        if (path.size < 2) return null   // [0] is the top folder
        val rest = path.drop(1)
        return when {
            rest.size == 1 && rest[0].endsWith(".onnx") -> OwnVoice.MODEL
            rest.size == 1 && rest[0] == OwnVoice.TOKENS -> OwnVoice.TOKENS
            rest.size == 1 && rest[0] == OwnVoice.MODEL_CARD -> OwnVoice.MODEL_CARD
            rest[0] == OwnVoice.ESPEAK_DATA && rest.size > 1 -> rest.joinToString("/")
            else -> null
        }
    }
}
