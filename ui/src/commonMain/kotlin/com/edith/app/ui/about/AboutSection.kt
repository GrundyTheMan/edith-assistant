package com.edith.app.ui.about

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Settings → About (C04 §3), the first shared screen part (K3.0). Same words and layout as the Android version had: it shows the app's
 * version, the Ollama server's version (or that it can't be reached), and the last crash report if there is one. It holds no logic.
 * Call it inside a Column.
 */
@Composable
fun AboutSection(
    edithVersion: String,
    ollamaVersion: String?,
    hasCrashReport: Boolean,
    onViewCrashReport: () -> Unit,
) {
    Text("About", style = MaterialTheme.typography.titleMedium)
    Text("Edith $edithVersion")
    Text("Ollama ${ollamaVersion ?: "— not reachable"}")
    if (hasCrashReport) {
        TextButton(onClick = onViewCrashReport, contentPadding = PaddingValues(0.dp)) { Text("View last crash report") }
    } else {
        Text("No crash reports", style = MaterialTheme.typography.bodySmall)
    }
}
