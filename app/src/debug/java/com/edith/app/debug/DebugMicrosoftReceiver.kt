package com.edith.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.edith.app.EdithApp
import com.edith.app.accounts.Provider
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.microsoft.GraphClient
import com.edith.app.microsoft.MicrosoftConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Debug builds only (C23). For each Microsoft account, makes a few Graph requests with no time limit and logs, for
 * each: the HTTP status, the protocol, Graph's error code, Graph's `request-id` and how long it took. Never the token,
 * the address or any content. Only the shell can send it (DUMP permission):
 *
 *     adb shell am broadcast -n com.edith.app.debug/com.edith.app.debug.DebugMicrosoftReceiver
 */
class DebugMicrosoftReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as EdithApp).container
        val pending = goAsync()
        pending.finish()   // the probes can take minutes; they log as they go
        container.appScope.launch {
            val http = container.okHttp.newBuilder().readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS).build()
            val accounts = container.accounts.entries().filter { it.account.provider == Provider.Microsoft }
            if (accounts.isEmpty()) Log.i(LogTag.Accounts, "DEBUG_MICROSOFT: no Microsoft account")
            for (entry in accounts) {
                val id = entry.account.id
                val auth = (container.accounts.connection(id) as? MicrosoftConnection)?.auth ?: continue
                val token = when (val t = auth.accessToken()) {
                    is Outcome.Success -> t.value
                    is Outcome.Failure -> { Log.i(LogTag.Accounts, "DEBUG_MICROSOFT: $id no token: ${t.error.userMessage}"); continue }
                }
                for (path in PROBES) {
                    val started = System.currentTimeMillis()
                    val line = withContext(Dispatchers.IO) {
                        try {
                            val request = Request.Builder().url(GraphClient.BASE_URL + path).header("Authorization", "Bearer $token").build()
                            http.newCall(request).execute().use { r ->
                                val body = r.body.string()
                                "HTTP ${r.code} ${r.protocol} code=${GraphClient.graphCode(body)} request-id=${r.header("request-id")}"
                            }
                        } catch (e: IOException) {
                            "failed (${e::class.simpleName})"
                        }
                    }
                    Log.i(LogTag.Accounts, "DEBUG_MICROSOFT: $id $path -> $line in ${System.currentTimeMillis() - started} ms")
                }
            }
            Log.i(LogTag.Accounts, "DEBUG_MICROSOFT: done")
        }
    }

    companion object {
        private val PROBES = listOf(
            "/me?\$select=id",
            "/me/calendars",
            "/me/calendars?\$select=id,name,isDefaultCalendar,canEdit&\$top=50",
            "/me/mailFolders/inbox",
            "/me/mailFolders/inbox/messages?\$top=1&\$select=id",
        )
    }
}
