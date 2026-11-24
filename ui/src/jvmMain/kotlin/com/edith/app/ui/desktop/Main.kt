package com.edith.app.ui.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.edith.app.ui.about.AboutSection
import com.edith.app.ui.theme.EdithTheme

/** The desktop window for looking at shared screens on Windows (`./gradlew :ui:run`). A developer tool, not a product (plan §4.1). */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Edith (desktop preview)") {
        EdithTheme {
            Surface {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AboutSection(edithVersion = "2.0.0-dev", ollamaVersion = "0.40.13", hasCrashReport = false, onViewCrashReport = {})
                }
            }
        }
    }
}
