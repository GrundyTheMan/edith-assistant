package com.edith.app.chat.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** D54: background time is asked for while a reply is being written, and given back when it ends or the system says time is up. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplyKeepAliveTest {
    private val working = EngineState.Working(1, "", "")

    private class Rig(scope: CoroutineScope) {
        val state = MutableStateFlow<EngineState>(EngineState.Idle)
        var begins = 0
        val ends = mutableListOf<Any>()
        var refuse = false
        var expire: (() -> Unit)? = null
        val keepAlive = ReplyKeepAlive(
            state,
            begin = { onExpired -> begins++; expire = onExpired; if (refuse) null else "task$begins" },
            end = { ends += it },
            scope = scope,
        )
    }

    private fun TestScope.rig() = Rig(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

    @Test fun asksWhenAReplyStartsAndGivesTheTimeBackWhenItEnds() = runTest {
        val r = rig()
        assertEquals(0, r.begins)
        r.state.value = working
        assertEquals(1, r.begins)
        r.state.value = working.copy(partialText = "more text")     // still the same reply: no second request
        assertEquals(1, r.begins)
        r.state.value = EngineState.Idle
        assertEquals(listOf<Any>("task1"), r.ends)
    }

    @Test fun aFailedReplyAlsoGivesTheTimeBack() = runTest {
        val r = rig()
        r.state.value = working
        r.state.value = EngineState.Failed(1, com.edith.app.core.error.AppError.Timeout)
        assertEquals(1, r.ends.size)
    }

    @Test fun whenTheSystemSaysTimeIsUpTheTimeIsGivenBackOnceAndTheNextReplyAsksAgain() = runTest {
        val r = rig()
        r.state.value = working
        r.expire!!.invoke()
        assertEquals(listOf<Any>("task1"), r.ends)
        r.state.value = EngineState.Idle              // the reply ends later: nothing more to give back
        assertEquals(1, r.ends.size)
        r.state.value = working
        assertEquals(2, r.begins)
        r.state.value = EngineState.Idle
        assertEquals(listOf<Any>("task1", "task2"), r.ends)
    }

    @Test fun aRefusedRequestChangesNothingAndTheNextReplyTriesAgain() = runTest {
        val r = rig()
        r.refuse = true
        r.state.value = working
        r.state.value = EngineState.Idle
        assertEquals(0, r.ends.size)
        r.refuse = false
        r.state.value = working
        assertEquals(2, r.begins)
    }
}
