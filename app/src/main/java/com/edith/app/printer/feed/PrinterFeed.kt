package com.edith.app.printer.feed

import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.jarvis.JarvisApi
import com.edith.app.jarvis.JarvisConnection

/**
 * Finds the paired Jarvis (C38) and its printer. It owns no state: the one [PrinterSession] holds that. Network goes through
 * the Jarvis connection's pinned client, so nothing here makes an `OkHttpClient`. (It stays in `app/` because [JarvisConnection] does.)
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
