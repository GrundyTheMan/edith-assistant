package com.edith.app.chat.ui

/**
 * What Edith's file cards need from the platform (C16 §4): the text of a file for View, and Save and Share. The iPhone's is a share sheet and the Files app's picker
 * (`IosFileExporter`); Android does these in its own `ChatScreen` (a content provider). [path] is the stored file's address, [name] the name to show and save it under.
 */
interface FileExporter {
    /** The file's text, or null when it can't be read. */
    fun readText(path: String): String?

    /** Asks where in the Files app to keep a copy under [name]. */
    fun save(path: String, name: String)

    /** Opens the share sheet with a copy under [name] (so the person sharing sees the name, not the stored file's random one). */
    fun share(path: String, name: String)
}
