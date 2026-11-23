package com.edith.app.tools.framework

import com.edith.app.chat.engine.ConfirmationRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

/** C08 §6 and D10: who may answer a confirmation card, and that a voice can never open a door. */
class ConfirmationsTest {
    private fun card(screenOnly: Boolean) = ConfirmationRequest("Open: Garage Door?", id = "c1", screenOnly = screenOnly)

    @Test fun `a screen-only card ignores a voice approval and keeps waiting for the tap`() = runTest {
        val confirmations = Confirmations()
        val answer = confirmations.register(card(screenOnly = true))
        assertFalse(confirmations.resolve("c1", true, ApprovalChannel.Voice), "a voice can't approve it")
        assertFalse(answer.isCompleted, "nothing was released")
        assertTrue(confirmations.resolve("c1", true, ApprovalChannel.Tap), "the tap counts")
        assertEquals(true, answer.await())
    }

    @Test fun `a voice can still turn a screen-only card down`() = runTest {
        val confirmations = Confirmations()
        val answer = confirmations.register(card(screenOnly = true))
        assertTrue(confirmations.resolve("c1", false, ApprovalChannel.Voice))
        assertEquals(false, answer.await())
    }

    @Test fun `an ordinary card may be approved by voice`() = runTest {
        val confirmations = Confirmations()
        val answer = confirmations.register(card(screenOnly = false))
        assertTrue(confirmations.resolve("c1", true, ApprovalChannel.Voice))
        assertEquals(true, answer.await())
    }

    @Test fun `an answer for a request that isn't waiting is ignored, and a second answer doesn't change the first`() = runTest {
        val confirmations = Confirmations()
        assertFalse(confirmations.resolve("nobody", true, ApprovalChannel.Tap))
        val answer = confirmations.register(card(screenOnly = false))
        assertTrue(confirmations.resolve("c1", false, ApprovalChannel.Tap))
        assertFalse(confirmations.resolve("c1", true, ApprovalChannel.Tap))
        assertEquals(false, answer.await())
    }
}
