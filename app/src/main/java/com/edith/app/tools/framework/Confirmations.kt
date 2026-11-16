package com.edith.app.tools.framework

import com.edith.app.chat.engine.ConfirmationRequest
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/** How the owner answered a confirmation: with a tap on its card, or by voice (hands-free). */
enum class ApprovalChannel { Tap, Voice }

/**
 * Hands a tool's confirmation request to the owner and waits for the answer (C08 §6, D11). The turn runner registers
 * the request *before* the screen can show it, so an answer can never arrive before anyone is waiting for it.
 * Cancelling the turn while waiting cancels the wait: nothing is executed.
 *
 * A [ConfirmationRequest.screenOnly] card (unlocking a door, opening the garage: D10) can be approved only by a tap:
 * [resolve] ignores a voice's approval of one, so even a voice path added later can't open a door.
 */
class Confirmations {
    private class Waiting(val request: ConfirmationRequest, val answer: CompletableDeferred<Boolean>)

    private val pending = ConcurrentHashMap<String, Waiting>()

    fun register(request: ConfirmationRequest): CompletableDeferred<Boolean> =
        CompletableDeferred<Boolean>().also { pending[request.id] = Waiting(request, it) }

    suspend fun await(request: ConfirmationRequest, answer: CompletableDeferred<Boolean>): Boolean =
        try { answer.await() } finally { pending.remove(request.id) }

    /**
     * The owner's answer, `by` the way they gave it. Ignored (false) if that request is no longer waiting, or if a voice
     * approves a screen-only card, which then keeps waiting for the tap. Turning a card down is allowed from either.
     * True when the answer counted.
     */
    fun resolve(id: String, approved: Boolean, by: ApprovalChannel): Boolean {
        val waiting = pending[id] ?: return false
        if (approved && waiting.request.screenOnly && by != ApprovalChannel.Tap) {
            Log.w(LogTag.Tools, "A voice approval of a screen-only card was ignored")
            return false
        }
        return waiting.answer.complete(approved)
    }
}
