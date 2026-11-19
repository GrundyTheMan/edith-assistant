package com.edith.app.printer.feed

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.jarvis.JarvisApi
import com.edith.app.jarvis.JarvisConnection

/** What the app can do about the printer right now (C39 part 3.1). */
internal sealed interface PrinterAccess {
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
internal interface PrinterFeedSource {
    /** Reads what Jarvis shares (asks Jarvis once) and says what the app can do. */
    suspend fun access(): PrinterAccess
}

/**
 * Finds the paired Jarvis (C38) and its printer. It owns no state: the one [PrinterSession] holds that. Network goes through
 * the Jarvis connection's pinned client, so nothing here makes an `OkHttpClient`.
 */
internal class PrinterFeed(private val jarvis: () -> JarvisConnection?) : PrinterFeedSource {
    override suspend fun access(): PrinterAccess {
        val connection = jarvis() ?: return PrinterAccess.NoJarvis
        return when (val r = connection.refreshShared()) {
            is Outcome.Success -> r.value?.let { PrinterAccess.Ready(PrinterFeedApi(connection.feedApi), it) } ?: PrinterAccess.NotShared
            is Outcome.Failure ->
                if (r.error == JarvisApi.UNAUTHORIZED) PrinterAccess.Away(PrinterWords.UNAUTHORIZED, removed = true)
                else PrinterAccess.Away((r.error as? AppError.ToolFailed)?.detail?.takeIf { it == JarvisApi.MOVED } ?: PrinterWords.UNREACHABLE)
        }
    }
}
