package com.edith.app.chat.ui

/**
 * The owner's say-so for the microphone (K4.3). On the iPhone the system asks once, the first time; a refusal can only be undone in Settings,
 * so [openSettings] takes the owner there. Android has its own glue in `ChatScreen` (the permission launcher).
 */
interface MicrophoneAccess {
    /** Asks if the owner has not been asked yet; [onResult] is called on the main thread, true when recording is allowed. */
    fun request(onResult: (granted: Boolean) -> Unit)

    /** Opens the app's page in the phone's Settings. */
    fun openSettings()
}
