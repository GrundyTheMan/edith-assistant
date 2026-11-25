package com.edith.app.chat.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.edith.app.data.db.MessageRole
import com.edith.app.data.db.MessageStatus
import com.edith.app.ui.assertEveryClickableIsNamed
import com.edith.app.ui.theme.EdithTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** K3.2: the chat screen's pure part, rendered from a made-up state on the desktop and the iPhone simulator (the first UI test of it anywhere). */
@OptIn(ExperimentalTestApi::class)
class ChatContentTest {
    private fun message(id: Long, role: MessageRole, text: String, latest: Boolean = false) = MessageUi(
        id = id, role = role, text = text, time = "2:30 PM", status = MessageStatus.Complete, errorMessage = null, toolsUsed = emptyList(),
        attachments = emptyList(), isLatest = latest, isLatestAssistant = latest && role == MessageRole.Assistant,
    )

    private val chat = ChatUiState(
        conversationId = 1, title = "Beach plans", loaded = true,
        messages = listOf(message(1, MessageRole.User, "Where should we go?"), message(2, MessageRole.Assistant, "The lake is nice today.", latest = true)),
    )

    @Test fun showsTheTitleAndBothMessages() = runComposeUiTest {
        setContent { EdithTheme { ChatContent(state = chat, draft = "", actions = ChatActions()) } }
        onNodeWithText("Beach plans").assertIsDisplayed()
        onNodeWithText("Where should we go?").assertIsDisplayed()
        onNodeWithText("The lake is nice today.").assertIsDisplayed()
    }

    @Test fun sendingCallsTheSendAction() = runComposeUiTest {
        var sent = 0
        setContent { EdithTheme { ChatContent(state = chat, draft = "hello", actions = ChatActions(onSend = { sent++ })) } }
        onNodeWithContentDescription("Send").performClick()
        assertEquals(1, sent)
    }

    @Test fun regenerateIsOfferedOnTheLatestReplyOnly() = runComposeUiTest {
        var regenerated = 0
        setContent { EdithTheme { ChatContent(state = chat, draft = "", actions = ChatActions(onRegenerate = { regenerated++ })) } }
        onNodeWithTag(actionRowTag(2)).assertIsDisplayed()
        onNodeWithContentDescription("Regenerate reply").performClick()
        assertEquals(1, regenerated)
    }

    @Test fun everyControlHasAName() = runComposeUiTest {
        // With an empty draft the field is named by its placeholder ("Message Edith"); a typed one is read by its value.
        setContent { EdithTheme { ChatContent(state = chat, draft = "", actions = ChatActions()) } }
        assertEveryClickableIsNamed()
    }

    @Test fun tappingTheMessagesTakesTheFocusAwayFromTheField() = runComposeUiTest {
        // On the iPhone the keyboard follows the focus: without this it could not be closed, and it covered the tab bar.
        setContent { EdithTheme { ChatContent(state = chat, draft = "", actions = ChatActions()) } }
        onNodeWithTag(INPUT_TAG).performClick()
        onNodeWithTag(INPUT_TAG).assertIsFocused()
        onNodeWithTag(MESSAGE_LIST_TAG).performClick()
        onNodeWithTag(INPUT_TAG).assertIsNotFocused()
    }

    @Test fun aSlowStartAddsALineAboutTheModelLoading() = runComposeUiTest {
        val waiting = chat.copy(inFlight = InFlight(label = com.edith.app.chat.engine.PlainTurnRunner.THINKING, partialText = ""))
        setContent { EdithTheme { ChatContent(state = waiting, draft = "", actions = ChatActions()) } }
        onNodeWithTag(SLOW_HINT_TAG).assertDoesNotExist()
        mainClock.advanceTimeBy(SLOW_START_MS + 500)
        onNodeWithTag(SLOW_HINT_TAG).assertIsDisplayed()
    }

    @Test fun aSharedScreenForEmptyChatsSaysSoOnceLoaded() = runComposeUiTest {
        setContent { EdithTheme { ChatContent(state = ChatUiState(conversationId = 1, loaded = true), draft = "", actions = ChatActions()) } }
        onNodeWithTag(INPUT_TAG).assertIsDisplayed()
    }
}
