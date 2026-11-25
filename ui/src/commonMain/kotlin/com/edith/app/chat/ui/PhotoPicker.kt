package com.edith.app.chat.ui

/**
 * Shows the system's photo picker and gives back the pictures chosen as file addresses (K4.3). On the iPhone it is `PHPickerViewController`, which
 * needs no permission and whose copy leaves a file the intake can read; Android has its own glue in `ChatScreen`.
 */
fun interface PhotoPicker {
    /** [onPicked] is called on the main thread with the chosen files, or an empty list when nothing was chosen. */
    fun pick(limit: Int, onPicked: (List<String>) -> Unit)
}
