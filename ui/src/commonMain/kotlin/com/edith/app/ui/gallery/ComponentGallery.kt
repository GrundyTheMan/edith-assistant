package com.edith.app.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.edith.app.chat.ui.AttachmentUi
import com.edith.app.chat.ui.ChatFeatures
import com.edith.app.chat.ui.InputRow
import com.edith.app.chat.ui.MessageItem
import com.edith.app.chat.ui.MessageUi
import com.edith.app.data.db.AttachmentKind
import com.edith.app.data.db.MessageRole
import com.edith.app.data.db.MessageStatus
import com.edith.app.chat.ui.renderMarkdown
import com.edith.app.ui.about.AboutSection
import com.edith.app.ui.components.CrashBanner
import com.edith.app.ui.components.VoiceBanner
import com.edith.app.ui.theme.EdithTheme

/**
 * Every shared component on one page, in light and dark (K3.1). It replaces `@Preview` (an Android tool): the desktop window shows it on
 * Windows (`./gradlew :ui:run`), and the UI tests check it for unnamed controls and unreadable colours. Add each new component here.
 */
@Composable
fun ComponentGallery() {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        EdithTheme(darkTheme = false) { Samples("Light") }
        EdithTheme(darkTheme = true) { Samples("Dark") }
    }
}

@Composable
private fun Samples(title: String) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AboutSection(edithVersion = "2.0.0-dev", ollamaVersion = "0.40.13", hasCrashReport = true, onViewCrashReport = {})
            }
            VoiceBanner(onOpenSettings = {}, onDismiss = {})
            CrashBanner(report = "java.lang.IllegalStateException: sample", onDismiss = {})
            val mine = MessageUi(1, MessageRole.User, "What's the weather like?", "2:30 PM", MessageStatus.Complete, null, emptyList(), emptyList(), isLatest = false, isLatestAssistant = false)
            val reply = MessageUi(
                2, MessageRole.Assistant, "It is **sunny** and 21 degrees.", "2:31 PM", MessageStatus.Complete, null, listOf("get_weather"),
                listOf(AttachmentUi(1, "beach-list.md", AttachmentKind.File, id = 9, fromEdith = true, mimeType = "text/markdown", preview = "- towel")),
                isLatest = true, isLatestAssistant = true,
            )
            MessageItem(mine, maxBubbleWidth = 280.dp, busy = false, speaking = false, onPlay = {}, onStopPlayback = {}, onRegenerate = {})
            MessageItem(reply, maxBubbleWidth = 280.dp, busy = false, speaking = false, onPlay = {}, onStopPlayback = {}, onRegenerate = {})
            InputRow(draft = "", canSend = false, features = ChatFeatures(attach = true, record = true, dictation = true), onDraftChange = {}, onSend = {})
            val link = MaterialTheme.colorScheme.primary
            val code = MaterialTheme.colorScheme.surfaceVariant
            for (block in renderMarkdown("**Bold**, *italic*, `code` and https://example.com\n\n- a list item\n- another", link, code)) {
                Text((block.marker?.let { "$it " } ?: "") + block.text.text, color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}
