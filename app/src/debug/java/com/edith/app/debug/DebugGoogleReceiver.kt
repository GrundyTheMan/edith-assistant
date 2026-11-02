package com.edith.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.edith.app.EdithApp
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.google.GoogleCall
import com.edith.app.google.GoogleScope
import kotlinx.coroutines.launch
import okhttp3.Request

/**
 * Debug builds only (C17 acceptance). With Google connected, gets a Calendar access token and makes one real Calendar
 * request (the next event's existence, nothing of its content). Logs the token's length and the HTTP status only,
 * never the token. Only the shell can send it (DUMP permission):
 *
 *     adb shell am broadcast -n com.edith.app.debug/com.edith.app.debug.DebugGoogleReceiver
 */
class DebugGoogleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as EdithApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                val google = container.googleAuth
                when (val t = google.accessToken(setOf(GoogleScope.CalendarEvents))) {
                    is Outcome.Success -> Log.i(LogTag.Google, "DEBUG_GOOGLE: Calendar token OK (${t.value.length} characters)")
                    is Outcome.Failure -> Log.i(LogTag.Google, "DEBUG_GOOGLE: no token: ${t.error.userMessage}")
                }
                val r = google.authorized(setOf(GoogleScope.CalendarEvents)) { token ->
                    val request = Request.Builder()
                        .url("https://www.googleapis.com/calendar/v3/calendars/primary/events?maxResults=1&fields=kind")
                        .header("Authorization", "Bearer $token")
                        .build()
                    container.okHttp.newCall(request).execute().use { response ->
                        when (response.code) {
                            200 -> GoogleCall.Ok(response.code)
                            401 -> GoogleCall.Unauthorized
                            else -> GoogleCall.Failed(AppError.GoogleAuth("HTTP ${response.code}"))
                        }
                    }
                }
                Log.i(LogTag.Google, "DEBUG_GOOGLE: Calendar request -> ${if (r is Outcome.Success) "HTTP ${r.value}" else (r as Outcome.Failure).error.userMessage}")
            } catch (e: Exception) {
                Log.e(LogTag.Google, "DEBUG_GOOGLE: failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
