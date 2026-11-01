package com.edith.app.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.edith.app.attachments.Retention
import com.edith.app.ui.theme.EdithTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** C16 §2: Settings → Attachments: the explanation, usage, the confirmation before deleting, and Delete all. */
@RunWith(AndroidJUnit4::class)
class AttachmentSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun explanationUsageAndConfirmations() {
        var state by mutableStateOf(AttachmentSettingsUi(keep = 10, usage = Retention.Usage(12, 8_400_000)))
        val calls = mutableListOf<String>()
        compose.setContent {
            EdithTheme {
                AttachmentsContent(
                    state, { calls += "keep $it" }, { calls += "confirm" }, { calls += "cancel" },
                    { calls += "ask all" }, { calls += "delete all" }, { calls += "cancel all" },
                )
            }
        }
        compose.onNodeWithText("Keeps the most recent 10 attachments you sent and 10 Edith sent, on this phone only. Older ones are deleted.").assertExists()
        compose.onNodeWithTag(ATTACHMENT_USAGE_TAG).assert(hasText("12 files, 8.4 MB"))
        compose.onNodeWithText("Delete all attachments now").performClick()

        state = state.copy(confirmKeep = 3 to 7)
        compose.onNodeWithText("This will delete 7 older attachments.").assertExists()
        compose.onNodeWithText("Delete").performClick()

        state = state.copy(confirmKeep = null, confirmDeleteAll = true)
        compose.onNodeWithText("Delete all").performClick()

        state = state.copy(confirmDeleteAll = false, keep = 0, usage = Retention.Usage(0, 0))
        compose.onNodeWithText("Keep: none (deleted when Edith closes)").assertExists()
        compose.onNodeWithText("No attachments stored").assertExists()
        compose.onNodeWithText("Delete all attachments now").assertIsNotEnabled()
        assertEquals(listOf("ask all", "confirm", "delete all"), calls)
    }
}
