package com.edith.app.chat.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.edith.app.core.error.AppError
import com.edith.app.data.db.MessageSource
import com.edith.app.testing.ChatTestKit
import com.edith.app.ui.theme.EdithTheme
import com.edith.app.voice.speech.SilentSpeechController
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** C07 tests: the real chat screen over the real engine, repository and Room, with a scripted Ollama. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ChatScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var kit: ChatTestKit
    private val store = ViewModelStore()

    @Before fun setUp() {
        // Send asks for notifications once (D13); grant it so no system dialog covers the screen mid-test.
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(InstrumentationRegistry.getInstrumentation().targetContext.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        kit = ChatTestKit()
    }

    @After fun tearDown() {
        compose.runOnUiThread { store.clear() }
        kit.close()
    }

    private fun show(): ChatViewModel {
        lateinit var vm: ChatViewModel
        compose.runOnUiThread {
            val factory = viewModelFactory {
                initializer { ChatViewModel(kit.repo, kit.engine, kit.current, SilentSpeechController, ChatFeatures(), kit.clock, SavedStateHandle()) }
            }
            vm = ViewModelProvider.create(store, factory)[ChatViewModel::class]
        }
        shown = vm
        compose.setContent { EdithTheme { ChatScreen(vm) } }
        waitFor { vm.state.value.loaded }   // Send stays off until the open conversation has loaded
        return vm
    }

    private var shown: ChatViewModel? = null
    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean) = try {
        compose.waitUntil(timeoutMs, condition)
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("Timed out. Screen state: ${shown?.state?.value}; engine: ${kit.engine.state.value}", e)
    }
    private fun inMessages(matcher: androidx.compose.ui.test.SemanticsMatcher) = matcher.and(hasAnyAncestor(hasTestTag(MESSAGE_LIST_TAG)))
    private fun exists(matcher: androidx.compose.ui.test.SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    @Test fun sendingClearsTheFieldAndTheReplyArrives() {
        runBlocking { kit.current.resolve() }
        kit.ollama.wordDelayMs = 300
        show()
        compose.onNodeWithText("Ask Edith anything — your schedule, the weather, or what's on your mind.").assertExists()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()   // nothing typed yet

        compose.onNodeWithTag(INPUT_TAG).performTextInput("Hi Edith")
        compose.onNodeWithContentDescription("Send").assertIsEnabled().performClick()

        compose.onNodeWithTag(INPUT_TAG).assert(hasText("").or(hasText("Message Edith")))
        waitFor { exists(inMessages(hasText("Hi Edith"))) }
        waitFor { exists(inMessages(hasText("Hello there, friend!"))) && !exists(hasTestTag(IN_FLIGHT_TAG)) }
        compose.onNodeWithContentDescription("Regenerate reply").assertExists()
    }

    /** C08 §6: a tool waiting for the owner's OK shows a card; the buttons answer it. */
    @Test fun theConfirmationCardShowsTheRequestAndItsButtonsAnswer() {
        val answers = mutableListOf<Boolean>()
        val request = com.edith.app.chat.engine.ConfirmationRequest("Add to your calendar?", listOf("When" to "Fri Oct 30, 12:00–1:00 PM"), id = "r1")
        val state = ChatUiState(conversationId = 1, loaded = true, busy = true,
            inFlight = InFlight("Waiting for your OK…", "I can add that.", confirmation = request))
        compose.setContent { EdithTheme { ChatContent(state, draft = "", actions = ChatActions(onAnswerConfirmation = { answers += it })) } }

        compose.onNodeWithText("Add to your calendar?").assertIsDisplayed()
        compose.onNodeWithText("Fri Oct 30, 12:00–1:00 PM").assertIsDisplayed()
        compose.onNode(inMessages(hasText("I can add that."))).assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel action").performClick()
        compose.onNodeWithContentDescription("Confirm action").performClick()
        assertEquals(listOf(false, true), answers)
    }

    /** C12: Record and dictation buttons, the recording row's controls, Transcribing…, and Open settings for the mic. */
    @Test fun voiceControlsRecordStopCancelDictateAndOpenSettings() {
        val calls = mutableListOf<String>()
        val actions = ChatActions(
            voice = VoiceActions(
                onRecord = { calls += "record" }, onStopAndSend = { calls += "stop" },
                onCancelRecording = { calls += "cancel" }, onDictate = { calls += "dictate" },
            ),
            onOpenAppSettings = { calls += "settings" },
        )
        val idle = ChatUiState(conversationId = 1, loaded = true, features = ChatFeatures(record = true, dictation = true))
        var state by mutableStateOf(idle)
        compose.setContent { EdithTheme { ChatContent(state, draft = "", actions = actions) } }

        compose.onNodeWithContentDescription("Record audio").performClick()
        compose.onNodeWithContentDescription("Speak a message").performClick()

        state = idle.copy(recording = RecordingUi(System.currentTimeMillis() - 7_000), voiceBusy = true)
        compose.onNodeWithTag(RECORDING_TIME_TAG).assert(hasText("0:07").or(hasText("0:08")))
        assertTrue("the text field gives way to the recording", !exists(hasTestTag(INPUT_TAG)))
        compose.onNodeWithContentDescription("Stop and send recording").performClick()
        compose.onNodeWithContentDescription("Cancel recording").performClick()

        state = idle.copy(voiceBusy = true, inFlight = InFlight(ChatViewModel.TRANSCRIBING, "", canStop = false))
        compose.onNodeWithText("Transcribing…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Record audio").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()

        state = idle.copy(dictating = true)
        compose.onNodeWithContentDescription("Stop dictation").assertIsDisplayed()
        compose.onNodeWithText("Listening…").assertIsDisplayed()

        state = idle.copy(error = ChatError(com.edith.app.voice.capture.VoiceMemos.MIC_DENIED, needsLocalNetworkPermission = false, opensAppSettings = true))
        compose.onNodeWithText("Open settings").performClick()
        assertEquals(listOf("record", "dictate", "stop", "cancel", "settings"), calls)
    }

    /** C14: the hands-free button and status line, the mic buttons waiting while it's on, and the ear on its messages. */
    @Test fun handsFreeButtonStatusAndEar() {
        var toggles = 0
        val off = ChatUiState(
            conversationId = 1, loaded = true, features = ChatFeatures(record = true, dictation = true),
            handsFree = HandsFreeUi(on = false),
            messages = listOf(
                MessageUi(1, com.edith.app.data.db.MessageRole.User, "what's the weather", "9:00 AM", com.edith.app.data.db.MessageStatus.Complete,
                    null, emptyList(), emptyList(), isLatest = true, isLatestAssistant = false, handsFree = true),
            ),
        )
        var state by mutableStateOf(off)
        compose.setContent { EdithTheme { ChatContent(state, draft = "", actions = ChatActions(onHandsFree = { toggles++ })) } }

        compose.onNodeWithContentDescription("Said hands-free").assertIsDisplayed()
        compose.onNodeWithContentDescription("Turn hands-free on").performClick()
        assertEquals(1, toggles)
        assertTrue(!exists(hasTestTag(HANDS_FREE_STATUS_TAG)))

        state = off.copy(handsFree = ChatViewModel.handsFreeUi(com.edith.app.voice.handsfree.HandsFree.State.Listening, "Hey Edith"))
        compose.onNodeWithTag(HANDS_FREE_STATUS_TAG).assert(hasText("Listening for “Hey Edith”"))
        compose.onNodeWithContentDescription("Record audio").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Speak a message").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Turn hands-free off").performClick()
        assertEquals(2, toggles)

        state = off.copy(handsFree = ChatViewModel.handsFreeUi(com.edith.app.voice.handsfree.HandsFree.State.Capturing("what's the"), "Hey Edith"))
        compose.onNodeWithTag(HANDS_FREE_STATUS_TAG).assert(hasText("what's the"))

        // Hands-free can't start while dictation has the microphone.
        state = off.copy(dictating = true)
        compose.onNodeWithContentDescription("Turn hands-free on").assertIsNotEnabled()
    }

    /** C15: the paperclip's menu, chips (preparing, ready, ✕), Send with only attachments, and attachments in a bubble. */
    @Test fun attachmentsInTheInputRowAndInBubbles() {
        val calls = mutableListOf<String>()
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val photo = java.io.File(app.cacheDir, "ui-test.jpg").apply {
            outputStream().use { android.graphics.Bitmap.createBitmap(40, 30, android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
        }
        val actions = ChatActions(
            onAttachPhoto = { calls += "photo" }, onAttachAudio = { calls += "audio" },
            onRemoveAttachment = { calls += "remove $it" }, onToggleAttachment = { calls += "play ${it.fileName}" },
            onOpenImage = { calls += "open ${java.io.File(it).name}" },
        )
        val image = com.edith.app.data.db.AttachmentKind.Image
        val audio = com.edith.app.data.db.AttachmentKind.Audio
        val message = MessageUi(
            1, com.edith.app.data.db.MessageRole.User, "", "9:00 AM", com.edith.app.data.db.MessageStatus.Complete, null, emptyList(),
            listOf(
                AttachmentUi(1, "list.jpg", image, id = 11, path = photo.path),
                AttachmentUi(2, "note.wav", audio, id = 12, path = photo.path, durationMs = 65_000),
                AttachmentUi(3, "old.jpg", image, id = 13, path = null),
            ),
            isLatest = true, isLatestAssistant = false,
        )
        val base = ChatUiState(conversationId = 1, loaded = true, features = ChatFeatures(attach = true), messages = listOf(message))
        var state by mutableStateOf(base.copy(pending = listOf(PendingUi(5, "Photo", image, null, ready = false))))
        compose.setContent { EdithTheme { ChatContent(state, draft = "", actions = actions) } }

        compose.onNodeWithContentDescription("Attach a file").performClick()
        compose.onNodeWithText("Photo").performClick()
        compose.onNodeWithContentDescription("Attach a file").performClick()
        compose.onNodeWithText("Audio file").performClick()

        compose.onNodeWithText("Preparing…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()   // still preparing

        state = base.copy(pending = listOf(PendingUi(5, "receipt.jpg", image, photo.path, ready = true)))
        compose.onNodeWithText("receipt.jpg").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertIsEnabled()      // attachments alone can be sent
        compose.onNodeWithContentDescription("Remove receipt.jpg").performClick()

        compose.onNodeWithContentDescription("Attachment 1: list.jpg").performClick()
        compose.onNodeWithText("1:05").assertIsDisplayed()
        compose.onNodeWithContentDescription("Play note.wav").performClick()
        compose.onNodeWithText("old.jpg — no longer available", substring = true).assertIsDisplayed()

        state = state.copy(playingAttachmentId = 12)
        compose.onNodeWithContentDescription("Stop note.wav").assertIsDisplayed()
        assertEquals(listOf("photo", "audio", "remove 5", "open ui-test.jpg", "play note.wav"), calls)
    }

    /** C16: Edith's file card (preview, View, Save, Share), a resend, and the removed lines in her bubble. */
    @Test fun edithsFilesAndResendsInHerBubble() {
        val calls = mutableListOf<String>()
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val list = java.io.File(app.cacheDir, "ui-list.md").apply { writeText("- towel\n- sunscreen\n- hat\n- snacks") }
        val photo = java.io.File(app.cacheDir, "ui-photo.jpg").apply {
            outputStream().use { android.graphics.Bitmap.createBitmap(40, 30, android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
        }
        val file = com.edith.app.data.db.AttachmentKind.File
        val image = com.edith.app.data.db.AttachmentKind.Image
        val reply = MessageUi(
            2, com.edith.app.data.db.MessageRole.Assistant, "Here's your list.\n\n[Attached file: beach-list.md]", "9:01 AM",
            com.edith.app.data.db.MessageStatus.Complete, null, emptyList(),
            listOf(
                AttachmentUi(4, "beach-list.md", file, id = 14, path = list.path, fromEdith = true, mimeType = "text/markdown", preview = "- towel\n- sunscreen\n- hat"),
                AttachmentUi(1, "receipt.jpg", image, id = 11, path = photo.path, fromEdith = true, isResend = true),
                AttachmentUi(2, "old.jpg", image, id = 12, path = null, fromEdith = true, isResend = true),
                AttachmentUi(5, "notes.md", file, id = 15, path = null, fromEdith = true),
            ),
            isLatest = true, isLatestAssistant = true,
        )
        val actions = ChatActions(
            files = FileActions(onView = { calls += "view ${it.fileName}" }, onSave = { calls += "save ${it.fileName}" }, onShare = { calls += "share ${it.fileName}" }),
            onOpenImage = { calls += "open" },
        )
        compose.setContent { EdithTheme { ChatContent(ChatUiState(conversationId = 1, loaded = true, messages = listOf(reply)), draft = "", actions = actions) } }

        compose.onNodeWithText("Here's your list.").assertIsDisplayed()
        assertTrue("the placeholder isn't shown", !exists(hasText("[Attached file: beach-list.md]", substring = true)))
        compose.onNodeWithTag(FILE_CARD_TAG).assertIsDisplayed()
        compose.onNodeWithText("- towel\n- sunscreen\n- hat").assertIsDisplayed()
        compose.onNodeWithText("View").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Share").performClick()
        compose.onNodeWithContentDescription("Attachment 1: receipt.jpg").performClick()
        compose.onNodeWithText("Attachment 2 is no longer available.").assertIsDisplayed()
        compose.onNodeWithText("notes.md — no longer kept", substring = true).assertIsDisplayed()
        assertEquals(listOf("view beach-list.md", "save beach-list.md", "share beach-list.md", "open"), calls)
    }

    /** The in-flight bubble's look (C07 §4); the streaming states themselves are covered in ChatViewModelTest. */
    @Test fun theInFlightBubbleShowsTheLabelUntilTextArrivesAndCanBeStopped() {
        var stopped = 0
        var state by mutableStateOf(ChatUiState(conversationId = 1, loaded = true, busy = true, inFlight = InFlight("Checking the weather…", "")))
        compose.setContent { EdithTheme { ChatContent(state, draft = "", actions = ChatActions(onStopReply = { stopped++ })) } }

        compose.onNodeWithText("Checking the weather…").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        waitFor { exists(hasContentDescription("Stop reply")) }
        compose.onNodeWithContentDescription("Stop reply").performClick()
        assertEquals(1, stopped)

        state = state.copy(inFlight = InFlight("Checking the weather…", "It's **sunny**"))
        compose.onNode(inMessages(hasText("It's sunny"))).assertIsDisplayed()
        assertTrue("the label goes once text arrives", !exists(hasText("Checking the weather…")))
    }

    @Test fun stopKeepsThePartialReplyMarkedStopped() {
        runBlocking { kit.current.resolve() }
        kit.ollama.words = (1..60).map { " word$it" }
        kit.ollama.wordDelayMs = 80
        show()
        compose.onNodeWithTag(INPUT_TAG).performTextInput("Long story please")
        compose.onNodeWithContentDescription("Send").performClick()
        waitFor { exists(hasText("word3", substring = true)) }

        waitFor { exists(hasContentDescription("Stop reply")) }
        compose.onNodeWithContentDescription("Stop reply").performClick()

        waitFor { exists(hasText("(stopped)", substring = true)) }
        assertTrue(kit.ollama.streamCancelled)
    }

    @Test fun regenerateIsOnlyOnTheNewestReplyAndTheActionRowHugsTheBubbleEdge() {
        val newest = runBlocking {
            val id = kit.current.resolve()
            kit.repo.addUserMessage(id, "Q1", MessageSource.Typed)
            kit.repo.addAssistantMessage(id, "First reply", "m", emptyList())
            kit.repo.addUserMessage(id, "Q2", MessageSource.Typed)
            kit.repo.addAssistantMessage(id, "Second reply with **bold** text", "m", emptyList())!!
        }
        show()
        waitFor { exists(hasTestTag(actionRowTag(newest))) }

        val regenerate = compose.onAllNodesWithContentDescription("Regenerate reply").fetchSemanticsNodes()
        assertEquals("one Regenerate button", 1, regenerate.size)
        compose.onNode(hasContentDescription("Regenerate reply").and(hasAnyAncestor(hasTestTag(actionRowTag(newest))))).assertExists()
        assertEquals("Play on both replies", 2, compose.onAllNodesWithContentDescription("Play reply").fetchSemanticsNodes().size)

        // The approved look: the action row is pulled ActionRowOverlap (7 dp) up onto the bubble's bottom edge.
        val bubble = compose.onNodeWithTag(bubbleTag(newest)).getUnclippedBoundsInRoot()
        val row = compose.onNodeWithTag(actionRowTag(newest)).getUnclippedBoundsInRoot()
        val overlap = bubble.bottom - row.top
        assertTrue("action row overlaps the bubble by ~7 dp, was $overlap", abs((overlap - ActionRowOverlap).value) <= 1f)
        compose.onNode(inMessages(hasText("bold", substring = true))).assertExists()
        assertTrue("Markdown markers aren't shown", !exists(hasText("**bold**", substring = true)))
    }

    @Test fun aFailedReplyShowsItsErrorAndRetryAsksAgain() {
        runBlocking { kit.current.resolve() }
        kit.ollama.failure = AppError.ServerUnreachable("http://10.0.2.2:11434")
        show()
        compose.onNodeWithTag(INPUT_TAG).performTextInput("Hello?")
        compose.onNodeWithContentDescription("Send").performClick()
        waitFor { exists(hasText("Can't reach your Ollama server", substring = true)) }
        compose.onNodeWithText("Retry").assertExists()

        kit.ollama.failure = null
        compose.onNodeWithText("Retry").performClick()
        waitFor { exists(hasText("Hello there, friend!")) }
        assertTrue("the failure was replaced", !exists(hasText("Can't reach your Ollama server", substring = true)))
    }

    @Test fun theDrawerListsSwitchesRenamesAndDeletesChats() {
        val (plants, trains) = runBlocking {
            val p = kit.repo.createConversation().also { kit.repo.addUserMessage(it, "Watering ferns", MessageSource.Typed) }
            val t = kit.repo.createConversation().also { kit.repo.addUserMessage(it, "Steam engines", MessageSource.Typed) }
            kit.current.select(t)
            p to t
        }
        show()
        waitFor { exists(hasText("Steam engines")) }

        // Regression: Back with the list open closes the list; it used to close the app.
        compose.onNodeWithContentDescription("Conversations").performClick()
        compose.onNodeWithTag(DRAWER_LIST_TAG).assertIsDisplayed()
        Espresso.pressBack()
        waitFor { compose.onAllNodes(hasTestTag(DRAWER_LIST_TAG)).fetchSemanticsNodes().none { node -> node.boundsInRoot.right > 0f } }
        compose.onNodeWithTag(INPUT_TAG).assertIsDisplayed()

        compose.onNodeWithContentDescription("Conversations").performClick()
        compose.onNode(hasText("Watering ferns").and(hasAnyAncestor(hasTestTag(DRAWER_LIST_TAG)))).performClick()
        waitFor { kit.current.id.value == plants }
        waitFor { exists(hasText("Watering ferns").and(hasAnyAncestor(hasTestTag(MESSAGE_LIST_TAG)))) }

        // Rename from the list's ⋮ menu; the new name shows in the top bar.
        compose.onNodeWithContentDescription("Conversations").performClick()
        compose.onNodeWithContentDescription("Options for Watering ferns").performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNode(hasText("Watering ferns").and(hasSetTextAction())).performTextReplacement("Fern care")
        compose.onNodeWithText("Save").performClick()
        waitFor { exists(hasText("Fern care")) }

        // Delete the open chat: it asks first, then the other chat opens.
        compose.onNodeWithContentDescription("Options for Fern care").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete this chat?").assertExists()
        compose.onNode(hasText("Delete").and(hasAnyAncestor(isDialog()))).performClick()
        waitFor { kit.current.id.value == trains }
        waitFor { !exists(hasText("Fern care")) }
    }

    @Test fun newChatOpensAnEmptyChatAndTheReplyInAnotherChatDisablesSend() {
        runBlocking {
            val id = kit.current.resolve()
            kit.repo.addUserMessage(id, "First chat", MessageSource.Typed)
        }
        kit.ollama.words = (1..40).map { " w$it" }
        kit.ollama.wordDelayMs = 100
        show()
        compose.onNodeWithTag(INPUT_TAG).performTextInput("Start a reply")
        compose.onNodeWithContentDescription("Send").performClick()
        waitFor { exists(hasText("w2", substring = true)) }

        compose.onNodeWithContentDescription("New chat").performClick()
        waitFor { exists(hasText(ChatUiState.NEW_CHAT_TITLE)) && exists(hasText("Ask Edith anything", substring = true)) }
        waitFor { exists(hasText(ChatViewModel.OTHER_CHAT_HINT)) }
        compose.onNodeWithTag(INPUT_TAG).performTextInput("Can I?")
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }

    private fun seedLongChat(): Long = runBlocking {
        val id = kit.current.resolve()
        repeat(15) {
            kit.repo.addUserMessage(id, "Question $it", MessageSource.Typed)
            kit.repo.addAssistantMessage(id, "Answer $it, " + "with a few words of padding ".repeat(4), "m", emptyList())
        }
        id
    }

    /** Regression: on Send, the owner's message and the thinking bubble arrive together; the chat must still follow. */
    @Test fun sendingFromALongChatScrollsToTheReply() {
        seedLongChat()
        show()
        compose.onNodeWithTag(INPUT_TAG).performTextInput("Newest question")
        compose.onNodeWithContentDescription("Send").performClick()
        waitFor { exists(inMessages(hasText("Hello there, friend!"))) && !exists(hasTestTag(IN_FLIGHT_TAG)) }
        compose.waitForIdle()
        compose.onNode(inMessages(hasText("Hello there, friend!"))).assertIsDisplayed()
        compose.onNode(inMessages(hasText("Newest question"))).assertIsDisplayed()
        assertTrue("no New button when following", !exists(hasTestTag(NEW_BELOW_TAG)))
    }

    @Test fun scrolledFarUpANewMessageShowsTheNewButtonInsteadOfMoving() {
        val id = seedLongChat()
        show()
        repeat(6) { compose.onNodeWithTag(MESSAGE_LIST_TAG).performTouchInput { swipeDown() } }   // the owner drags far up
        waitFor { exists(inMessages(hasText("Question 0"))) }
        compose.onNode(inMessages(hasText("Question 0"))).assertIsDisplayed()

        runBlocking { kit.repo.addAssistantMessage(id, "A brand new message", "m", emptyList()) }
        waitFor { exists(hasTestTag(NEW_BELOW_TAG)) }
        compose.onNode(inMessages(hasText("Question 0"))).assertIsDisplayed()   // not yanked down

        compose.onNodeWithTag(NEW_BELOW_TAG).performClick()
        waitFor { !exists(hasTestTag(NEW_BELOW_TAG)) }
        compose.onNode(inMessages(hasText("A brand new message"))).assertIsDisplayed()
    }
}
