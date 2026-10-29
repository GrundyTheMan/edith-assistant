package com.edith.app.ui.navigation

/** The bottom-bar destinations, in display order. Icons are mapped in [EdithNavHost]. */
enum class Destination(val route: String, val label: String) {
    Chat("chat", "Chat"),
    Calendar("calendar", "Calendar"),
    Settings("settings", "Settings");

    companion object {
        val start = Chat
    }
}
