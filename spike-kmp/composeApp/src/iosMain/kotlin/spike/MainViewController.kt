package spike

import androidx.compose.ui.window.ComposeUIViewController

/** The iPhone's entry: the same `App()` as the desktop, inside a view controller that the SwiftUI shell shows. */
fun MainViewController() = ComposeUIViewController { App() }
