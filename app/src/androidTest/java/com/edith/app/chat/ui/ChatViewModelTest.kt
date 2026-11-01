package com.edith.app.chat.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.edith.app.core.error.AppError
import com.edith.app.data.db.MessageRole
import com.edith.app.data.db.MessageSource
import com.edith.app.data.db.MessageStatus
import com.edith.app.testing.ChatTestKit
import com.edith.app.voice.speech.SpeechController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** C07 §1 with C06b: the ViewModel over the real engine, repository and Room, with a scripted Ollama. */
@RunWith(AndroidJUnit4::class)
class ChatViewModelTest {
    private lateinit var kit: ChatTestKit
    private val stores = mutableListOf<ViewModelStore>()
    private val speech = object : SpeechController {
        val speaking = MutableStateFlow<Long?>(null)
        val played = mutableListOf<Long>()
        override val speakingMessageId: StateFlow<Long?> = speaking
        override fun play(messageId: Long, text: String) { played += messageId; speaking.value = messageId }
        override fun stop() { speaking.value = null }
    }

    @Before fun setUp() { kit = ChatTestKit() }

    @After fun tearDown() {
        onMain { stores.forEach { it.clear() } }   // cancels each ViewModel's coroutines
        kit.close()
    }

    private fun viewModel(savedState: SavedStateHandle = SavedStateHandle()): ChatViewModel {
        val store = ViewModelStore().also { stores += it }
        lateinit var vm: ChatViewModel
        onMain {
            val factory = viewModelFactory { initializer { ChatViewModel(kit.repo, kit.engine, kit.current, speech, ChatFeatures(), kit.clock, savedState) } }
            vm = ViewModelProvider.create(store, factory)[ChatViewModel::class]
        }
        return vm
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private suspend fun ChatViewModel.awaitState(why: String = "", until: (ChatUiState) -> Boolean): ChatUiState =
        try { withTimeout(10_000) { state.first(until) } } catch (e: Exception) { throw AssertionError("Timed out waiting: $why; last: ${state.value}", e) }

    @Test fun showsTheOpenConversationAndFollowsASwitch() = runBlocking {
        val a = kit.repo.createConversation()
        kit.repo.addUserMessage(a, "About plants", MessageSource.Typed)
        val b = kit.repo.createConversation()
        kit.repo.addUserMessage(b, "About trains", MessageSource.Typed)
        kit.repo.rename(b, "Trains")
        kit.current.select(a)
        val vm = viewModel()

        val inA = vm.awaitState("chat A") { it.conversationId == a && it.messages.isNotEmpty() }
        assertEquals(listOf("About plants"), inA.messages.map { it.text })
        assertEquals("About plants", inA.title)   // the first message is the provisional title (D12)

        onMain { vm.select(b) }
        val inB = vm.awaitState("chat B") { it.conversationId == b && it.messages.isNotEmpty() }
        assertEquals(listOf("About trains"), inB.messages.map { it.text })
        assertEquals("Trains", inB.title)
        assertEquals(setOf(a, b), inB.conversations.map { it.id }.toSet())
        assertEquals(listOf(b), inB.conversations.filter { it.isCurrent }.map { it.id })
    }

    @Test fun aReplyStreamsInItsOwnChatAndBlocksSendingEverywhere() = runBlocking {
        val a = kit.current.resolve()
        val b = kit.repo.createConversation()
        kit.repo.addUserMessage(b, "Other chat", MessageSource.Typed)
        kit.ollama.words = (1..20).map { " word$it" }
        kit.ollama.wordDelayMs = 60
        val vm = viewModel()
        vm.awaitState("loaded") { it.loaded && it.conversationId == a }

        onMain { vm.onDraftChange("Hello Edith") }
        assertTrue(vm.state.value.canSend(vm.draft))
        onMain { vm.send() }
        assertEquals("the field clears", "", vm.draft)

        val streaming = vm.awaitState("streaming in A") { it.inFlight?.partialText?.contains("word2") == true }
        assertTrue(streaming.busy)
        assertTrue("the owner's message shows straight away", streaming.messages.any { it.text == "Hello Edith" } || streaming.pendingUserText == "Hello Edith")
        assertTrue(streaming.conversations.single { it.id == a }.isReplying)

        onMain { vm.select(b); vm.onDraftChange("Can I send?") }
        val inB = vm.awaitState("switched to B") { it.conversationId == b && it.messages.isNotEmpty() }
        assertNull("A's reply doesn't show in B", inB.inFlight)
        assertTrue(inB.busy)
        assertFalse("one turn at a time: B can't send", inB.canSend("Can I send?"))
        assertEquals(ChatViewModel.OTHER_CHAT_HINT, inB.sendHint)

        onMain { vm.select(a) }
        val done = vm.awaitState("A finished") { st -> st.conversationId == a && !st.busy && st.inFlight == null && st.messages.size == 2 }
        assertEquals(MessageStatus.Complete, done.messages.last().status)
        assertTrue(done.messages.last().text.startsWith("word1 word2"))
        assertNull(done.pendingUserText)
        assertNull(done.sendHint)
    }

    @Test fun eachChatKeepsItsOwnDraftAndItSurvivesRecreation() = runBlocking {
        val a = kit.current.resolve()
        kit.repo.addUserMessage(a, "keep A", MessageSource.Typed)   // so New chat makes a second chat
        val savedState = SavedStateHandle()
        val vm = viewModel(savedState)
        vm.awaitState { it.conversationId == a }
        onMain { vm.onDraftChange("draft for A") }

        onMain { vm.newChat() }
        val b = vm.awaitState("new chat") { it.conversationId != null && it.conversationId != a }.conversationId!!
        assertEquals("a new chat starts empty", "", vm.draft)
        onMain { vm.onDraftChange("draft for B") }

        onMain { vm.select(a) }
        vm.awaitState { it.conversationId == a }
        withTimeout(5_000) { while (vm.draft != "draft for A") kotlinx.coroutines.delay(10) }

        // The screen is recreated (process death keeps the SavedStateHandle): A's draft is still there.
        val again = viewModel(savedState)
        again.awaitState { it.conversationId == a }
        withTimeout(5_000) { while (again.draft != "draft for A") kotlinx.coroutines.delay(10) }
        assertEquals("draft for B", savedState.get<String>("draft_$b"))
    }

    @Test fun regenerateIsOfferedOnlyOnTheNewestMessageWhenItIsEdiths() = runBlocking {
        val a = kit.current.resolve()
        kit.repo.addUserMessage(a, "Q1", MessageSource.Typed)
        val first = kit.repo.addAssistantMessage(a, "A1", "m", emptyList())!!
        kit.repo.addUserMessage(a, "Q2", MessageSource.Typed)
        val second = kit.repo.addAssistantMessage(a, "A2", "m", listOf("get_weather"))!!
        val vm = viewModel()

        val st = vm.awaitState { it.messages.size == 4 }
        assertEquals(listOf(second), st.messages.filter { it.isLatestAssistant }.map { it.id })
        assertFalse(st.messages.single { it.id == first }.isLatestAssistant)
        assertEquals(listOf("get_weather"), st.messages.single { it.id == second }.toolsUsed)

        kit.repo.addUserMessage(a, "Q3", MessageSource.Typed)
        val afterUser = vm.awaitState { it.messages.size == 5 }
        assertTrue("a newest user message means no Regenerate", afterUser.messages.none { it.isLatestAssistant })
    }

    @Test fun aFailedReplyShowsOnceAndOnlyThePermissionProblemGetsTheErrorLine() = runBlocking {
        kit.current.resolve()
        val vm = viewModel()
        vm.awaitState { it.loaded }

        kit.ollama.failure = AppError.Timeout
        onMain { vm.onDraftChange("Hello?"); vm.send() }
        val timedOut = vm.awaitState("failed bubble") { it.messages.lastOrNull()?.status == MessageStatus.Failed && !it.busy }
        assertNull("a timeout shows as the failed bubble only", timedOut.error)
        assertEquals(AppError.Timeout.userMessage, timedOut.messages.last().errorMessage)
        assertTrue(timedOut.messages.last().isLatest)
        assertNull(timedOut.inFlight)

        kit.ollama.failure = null
        kit.networkGranted = false
        onMain { vm.regenerate() }   // Retry
        val denied = vm.awaitState("permission error and its stored failure") { st -> st.error != null && st.messages.any { it.status == MessageStatus.Failed } }
        assertTrue(denied.error!!.needsLocalNetworkPermission)
        assertEquals("Retry replaced the old failure", 1, denied.messages.count { it.status == MessageStatus.Failed })

        onMain { vm.dismissError() }
        vm.awaitState("dismissed") { it.error == null }

        kit.networkGranted = true
        onMain { vm.onLocalNetworkPermissionResult(granted = true) }   // allowed → the turn is tried again
        val ok = vm.awaitState("retried after allowing") { it.messages.lastOrNull()?.let { m -> m.role == MessageRole.Assistant && m.status == MessageStatus.Complete } == true }
        assertEquals("Hello there, friend!", ok.messages.last().text)
        assertEquals(listOf("Hello?", "Hello there, friend!"), ok.messages.map { it.text })
    }

    @Test fun stoppingKeepsWhatEdithWroteAndPlaybackUsesTheSpeechController() = runBlocking {
        kit.current.resolve()
        kit.ollama.words = (1..50).map { " word$it" }
        kit.ollama.wordDelayMs = 80
        val vm = viewModel()
        vm.awaitState { it.loaded }
        onMain { vm.onDraftChange("Tell me a story"); vm.send() }
        vm.awaitState("streaming") { it.inFlight?.partialText?.contains("word3") == true }

        onMain { vm.stopReply() }
        val stopped = vm.awaitState("stopped") { it.messages.lastOrNull()?.status == MessageStatus.Cancelled && it.inFlight == null }
        assertTrue(stopped.messages.last().text.startsWith("word1 word2 word3"))

        val reply = stopped.messages.last()
        onMain { vm.play(reply) }
        assertEquals(reply.id, vm.awaitState { it.speakingMessageId != null }.speakingMessageId)
        onMain { vm.stopPlayback() }
        vm.awaitState { it.speakingMessageId == null }
        assertEquals(listOf(reply.id), speech.played)
    }

    @Test fun deletingTheOpenChatOpensTheNextAndRenameShowsInTheList() = runBlocking {
        val older = kit.repo.createConversation()
        kit.repo.addUserMessage(older, "older chat", MessageSource.Typed)
        val newer = kit.repo.createConversation()
        kit.repo.addUserMessage(newer, "newer chat", MessageSource.Typed)
        kit.current.select(newer)
        val vm = viewModel()
        vm.awaitState { it.conversationId == newer }

        onMain { vm.rename(older, "Renamed") }
        vm.awaitState("renamed in list") { st -> st.conversations.any { it.id == older && it.title == "Renamed" } }

        onMain { vm.delete(newer) }
        val after = vm.awaitState("switched after delete") { it.conversationId == older && it.messages.isNotEmpty() }
        assertEquals("Renamed", after.title)
        assertEquals(listOf(older), after.conversations.map { it.id })
    }
}
