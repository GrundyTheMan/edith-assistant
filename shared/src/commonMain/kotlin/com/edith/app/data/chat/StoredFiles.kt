package com.edith.app.data.chat

/** The one thing the chat history asks of wherever attachment files are kept: to delete one when its message goes (C16 §2). */
interface StoredFiles {
    fun delete(storedPath: String?)
}
