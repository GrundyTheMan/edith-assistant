package com.edith.app.tools.framework

import com.edith.app.chat.engine.ConfirmationRequest
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Hands a tool's confirmation request to the owner and waits for the answer (C08 §6, D11). The turn runner registers
 * the request *before* the screen can show it, so an answer can never arrive before anyone is waiting for it.
 * Cancelling the turn while waiting cancels the wait: nothing is executed.
 */
class Confirmations {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    fun register(request: ConfirmationRequest): CompletableDeferred<Boolean> =
        CompletableDeferred<Boolean>().also { pending[request.id] = it }

    suspend fun await(request: ConfirmationRequest, answer: CompletableDeferred<Boolean>): Boolean =
        try { answer.await() } finally { pending.remove(request.id) }

    /** The owner's answer; ignored if that request is no longer waiting. */
    fun resolve(id: String, approved: Boolean) {
        pending[id]?.complete(approved)
    }
}
