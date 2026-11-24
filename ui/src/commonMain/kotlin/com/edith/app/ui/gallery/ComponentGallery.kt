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
            val link = MaterialTheme.colorScheme.primary
            val code = MaterialTheme.colorScheme.surfaceVariant
            for (block in renderMarkdown("**Bold**, *italic*, `code` and https://example.com\n\n- a list item\n- another", link, code)) {
                Text((block.marker?.let { "$it " } ?: "") + block.text.text, color = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}
