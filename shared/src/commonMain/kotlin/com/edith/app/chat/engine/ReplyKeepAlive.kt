package com.edith.app.chat.engine

import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * A reply that finishes after the owner leaves the app (D54). Android has a foreground service for this; an iPhone suspends an app a few seconds after it leaves the screen, so a long
 * answer from Ollama would die mid-sentence. iOS lends extra time (about 30 seconds) to work that asks for it, so this asks when a reply starts and gives the time back when it ends,
 * or when iOS says the time is up. It cannot hold a reply longer than that: a very slow model can still be cut off, and the reply then fails like any lost connection.
 *
 * [begin] asks the system for time and returns what to give back (null when it refused); [end] gives it back. [begin] is called with an expiry callback: when the system says the time
 * is up, it must be called so the time is given back (the system ends the app if it is not). Pure logic over those, so it is tested on the desktop.
 */
class ReplyKeepAlive(
    state: StateFlow<EngineState>,
    private val begin: (onExpired: () -> Unit) -> Any?,
    private val end: (Any) -> Unit,
    scope: CoroutineScope,
) {
    private var held: Any? = null

    init {
        scope.launch {
            // Only a reply being written needs the time; waiting for the owner's tap on a confirmation does not (nothing is running then).
            state.map { it is EngineState.Working }.distinctUntilChanged().collect { working ->
                if (working) acquire() else release()
            }
        }
    }

    private fun acquire() {
        if (held != null) return
        held = begin { release("The system's background time for a reply ran out.") }
    }

    private fun release(why: String? = null) {
        val h = held ?: return
        held = null
        if (why != null) Log.w(LogTag.Chat, why)
        end(h)
    }
}
