package com.edith.app.core.permissions

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Runtime permissions a tool needs, asked with Android's own dialog at the moment of use (C11, 01-architecture §9).
 * Tools run in the engine, but only a screen can show the dialog: [request] publishes [pending], the chat screen
 * launches the dialog and reports back with [resolve]. If the chat isn't on screen, the request waits until it is;
 * stopping the reply cancels the wait.
 */
class ToolPermissions(private val isGranted: (String) -> Boolean) {
    data class Request(val permission: String, val id: String = UUID.randomUUID().toString()) {
        internal val answer = CompletableDeferred<Boolean>()
    }

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    fun isGranted(permission: String): Boolean = isGranted.invoke(permission)

    /** True at once if already granted; otherwise asks and suspends until the owner answers. */
    suspend fun request(permission: String): Boolean {
        if (isGranted(permission)) return true
        val request = Request(permission)
        _pending.value = request
        return try {
            request.answer.await()
        } finally {
            _pending.compareAndSet(request, null)
        }
    }

    /** The dialog's result; ignored if that request is no longer waiting. */
    fun resolve(id: String, granted: Boolean) {
        _pending.value?.takeIf { it.id == id }?.answer?.complete(granted)
    }

    companion object {
        fun android(context: Context) = ToolPermissions { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
}
