package com.edith.app.chat.ui

/**
 * Shows the system's file picker for one audio file and gives back its address (K4.3). On the iPhone it is the Files app's document picker, which hands
 * the app a copy it can read; Android has its own glue in `ChatScreen`.
 */
fun interface AudioPicker {
    /** [onPicked] is called on the main thread with the chosen file's address, or null when nothing was chosen. */
    fun pick(onPicked: (String?) -> Unit)
}
