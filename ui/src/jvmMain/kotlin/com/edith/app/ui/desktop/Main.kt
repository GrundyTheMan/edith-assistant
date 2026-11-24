package com.edith.app.ui.desktop

import androidx.compose.material3.Surface
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.edith.app.ui.gallery.ComponentGallery

/** The desktop window for looking at shared components on Windows (`./gradlew :ui:run`). A developer tool, not a product (plan §4.1). */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Edith (desktop preview)") {
        Surface { ComponentGallery() }
    }
}
