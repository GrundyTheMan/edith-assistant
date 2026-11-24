package com.edith.app.printer.feed

/** What the app can do about the printer right now (C39 part 3.1). */
sealed interface PrinterAccess {
    /** No Jarvis is paired. */
    data object NoJarvis : PrinterAccess
    /** Paired, but the printer is not shared with this phone (or this Jarvis is too old to know it). */
    data object NotShared : PrinterAccess
    /** Shared: [api] asks Jarvis, [shared] is what Jarvis says about the printer (name, control, presets, cameras). */
    data class Ready(val api: PrinterRoutes, val shared: SharedPrinter) : PrinterAccess
    /** Jarvis could not be asked: [why] is in plain words; [removed] when it does not know this phone any more. */
    data class Away(val why: String, val removed: Boolean = false) : PrinterAccess
}

/** Where the page, Home and the chat tools get the printer from. */
interface PrinterFeedSource {
    /** Reads what Jarvis shares (asks Jarvis once) and says what the app can do. */
    suspend fun access(): PrinterAccess
}
