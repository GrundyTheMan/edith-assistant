package com.edith.app.chat.engine

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.edith.app.EdithApp
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.permissions.LocalNetworkAccess
import com.edith.app.data.chat.ChatRepository
import com.edith.app.data.db.EdithDatabase
import com.edith.app.data.db.EdithDatabaseCallback
import com.edith.app.data.db.MessageSource
import com.edith.app.data.db.MessageStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * C06 acceptance: a real reply from the owner's Ollama streams in piece by piece; logs time to first token and total.
 * Opt-in (needs the live server and depends on the model), so CI and check.sh skip it:
 *   adb shell am instrument -w -e live true -e class com.edith.app.chat.engine.ChatEngineLiveCheckTest \
 *       com.edith.app.test/androidx.test.runner.AndroidJUnitRunner
 * Results: `adb logcat -d -s Edith/Chat:*`. Uses an in-memory database, so nothing lands in the real chat history.
 */
@RunWith(AndroidJUnit4::class)
class ChatEngineLiveCheckTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = (instrumentation.targetContext.applicationContext as EdithApp).container
    private lateinit var db: EdithDatabase
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        assumeTrue("live checks run only with -e live true", InstrumentationRegistry.getArguments().getString("live") == "true")
        instrumentation.uiAutomation.grantRuntimePermission("com.edith.app", LocalNetworkAccess.PERMISSION)
        db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, EdithDatabase::class.java).addCallback(EdithDatabaseCallback).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        if (::scope.isInitialized) scope.cancel()
        if (::db.isInitialized) db.close()
    }

    @Test fun aRealReplyStreamsIn() = runBlocking {
        val deltaTimes = Collections.synchronizedList(mutableListOf<Long>())
        var started = 0L
        val repo = ChatRepository(db, container.clock, container.dispatchers)
        val engine = ChatEngine(
            repo, container.settingsRepository, container.modelSelection,
            PromptBuilder(container.clock) { container.promptSections }, ContextBuilder(), PlainTurnRunner(container.ollama),
            listOf(object : ReplyListener { override fun onDelta(text: String) { deltaTimes += System.nanoTime() } }),
            scope, container.localNetworkAccess,
        )

        // Warm-up turn so the model is loaded (the budget is for a warm model), then the measured turn.
        for (round in listOf("warm-up", "measured")) {
            deltaTimes.clear()
            started = System.nanoTime()
            engine.send(
                if (round == "warm-up") "Say hi in three words." else "In two sentences, what's a good name for a houseplant?",
                MessageSource.Typed,
            )
            val end = withTimeout(120_000) { engine.state.first { it is EngineState.Idle || it is EngineState.Failed } }
            assertEquals("turn failed: $end", EngineState.Idle, end)
            val reply = repo.latest()!!.message
            val firstMs = (deltaTimes.first() - started) / 1_000_000
            val totalMs = (System.nanoTime() - started) / 1_000_000
            Log.i(LogTag.Chat, "LIVE $round: model ${reply.model}, ${deltaTimes.size} pieces, first token ${firstMs} ms, total ${totalMs} ms: \"${reply.content}\"")
            assertEquals(MessageStatus.Complete, reply.status)
            assertTrue("should stream in several pieces, got ${deltaTimes.size}", deltaTimes.size > 3)
        }
    }
}
