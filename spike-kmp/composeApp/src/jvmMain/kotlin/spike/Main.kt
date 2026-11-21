package spike

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/** Run the shared screen on the desktop: the fast loop on Windows (`gradlew :composeApp:run`). */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Edith (shared-code spike)") { App() }
}
