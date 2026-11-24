package com.edith.app.tools.framework

import com.edith.app.chat.engine.ConfirmationRequest
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

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

    /** Replaced whole on every change, so any thread can read it and the updates never lose one another. */
    private val pending = MutableStateFlow<Map<String, Waiting>>(emptyMap())

    fun register(request: ConfirmationRequest): CompletableDeferred<Boolean> =
        CompletableDeferred<Boolean>().also { answer -> pending.update { it + (request.id to Waiting(request, answer)) } }

    suspend fun await(request: ConfirmationRequest, answer: CompletableDeferred<Boolean>): Boolean =
        try { answer.await() } finally { pending.update { it - request.id } }

    /**
     * The owner's answer, `by` the way they gave it. Ignored (false) if that request is no longer waiting, or if a voice
     * approves a screen-only card, which then keeps waiting for the tap. Turning a card down is allowed from either.
     * True when the answer counted.
     */
    fun resolve(id: String, approved: Boolean, by: ApprovalChannel): Boolean {
        val waiting = pending.value[id] ?: return false
        if (approved && waiting.request.screenOnly && by != ApprovalChannel.Tap) {
            Log.w(LogTag.Tools, "A voice approval of a screen-only card was ignored")
            return false
        }
        return waiting.answer.complete(approved)
    }
}
