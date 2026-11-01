package com.edith.app.chat.service

import com.edith.app.chat.engine.ConfirmationRequest
import com.edith.app.chat.engine.EngineState
import com.edith.app.core.error.AppError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** D13: the reply service is started once per turn — not per streamed word, and not when idle or failed. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplyKeepAliveTest {
    @Test fun `starts once when a turn begins and again for the next turn`() = runTest(UnconfinedTestDispatcher()) {
        val state = MutableStateFlow<EngineState>(EngineState.Idle)
        var starts = 0
        ReplyKeepAlive(state) { starts++ }.watch(backgroundScope)
        assertEquals("idle at launch: nothing", 0, starts)

        state.value = EngineState.Working(1, "Edith is thinking…", "")
        state.value = EngineState.Working(1, "Edith is thinking…", "Hel")
        state.value = EngineState.Working(1, "Edith is thinking…", "Hello")
        state.value = EngineState.AwaitingConfirmation(1, ConfirmationRequest("Send email?"), "Hello")
        assertEquals("one start for the whole turn", 1, starts)

        state.value = EngineState.Idle
        state.value = EngineState.Failed(1, AppError.Timeout)
        assertEquals(1, starts)

        state.value = EngineState.Working(2, "Edith is thinking…", "")
        assertEquals("the next turn starts it again", 2, starts)
    }
}
