package com.edith.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Light, like the crash banner: one line and two buttons. */
@Composable
fun VoiceBanner(onOpenSettings: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier.fillMaxWidth().testTag(VOICE_BANNER_TAG)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp)) {
            Text(
                VOICE_BANNER_TEXT,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onOpenSettings) { Text("Open Settings") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

const val VOICE_BANNER_TAG = "voice_banner"
const val VOICE_BANNER_TEXT = "I can't speak yet. Get my voice in Settings → Voice."
