package com.edith.app.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.window.ComposeUIViewController
import com.edith.app.di.createIosApp
import com.edith.app.diagnostics.EngineCheck
import com.edith.app.ui.diagnostics.EngineCheckScreen
import com.edith.app.ui.theme.EdithTheme
import platform.UIKit.UIViewController

/**
 * The iPhone's entry (K4.1): the shared screens inside a view controller that the SwiftUI shell in `iosApp/` shows. It builds the shared
 * engine once per launch from Apple's own parts (Keychain, Darwin, files) and shows the engine check.
 */
fun MainViewController(): UIViewController {
    val app = createIosApp()
    val check = EngineCheck(app.container, app.secrets, app.settings)
    return ComposeUIViewController {
        EdithTheme {
            Surface { EngineCheckScreen(check) }
        }
    }
}
