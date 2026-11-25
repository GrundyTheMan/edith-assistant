package com.edith.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edith.app.chat.ui.ChatFeatures
import com.edith.app.chat.ui.ChatViewModel
import com.edith.app.chat.ui.IosPhotoPicker
import com.edith.app.chat.ui.SimpleChatScreen
import com.edith.app.di.createIosApp
import com.edith.app.diagnostics.EngineCheck
import com.edith.app.ui.diagnostics.EngineCheckScreen
import com.edith.app.ui.theme.EdithTheme
import com.edith.app.voice.speech.SilentSpeechController
import kotlinx.coroutines.launch
import platform.UIKit.UIViewController

/**
 * The iPhone's entry (K4.1, K4.2): the shared screens inside a view controller that the SwiftUI shell in `iosApp/` shows. It builds the shared
 * engine once per launch from Apple's own parts (Keychain, Darwin, files), does what Android's container does at startup (clears leftovers,
 * opens the database, picks the open chat), and shows two tabs: the chat, and the setup page with the engine check where the Ollama address is typed.
 * Chat has photo attachments (K4.3); voice and audio files come later.
 */
fun MainViewController(): UIViewController {
    val app = createIosApp()
    val check = EngineCheck(app.container, app.secrets, app.settings)
    app.intake.clearLeftovers()
    app.container.appScope.launch {
        app.container.retention.onStart()
        app.container.currentConversation.resolve()
    }
    val photoPicker = IosPhotoPicker()
    return ComposeUIViewController {
        EdithTheme {
            Surface {
                var tab by rememberSaveable { mutableStateOf(0) }
                val vm = viewModel {
                    ChatViewModel(
                        repo = app.container.chatRepository,
                        engine = app.container.chatEngine,
                        current = app.container.currentConversation,
                        speech = SilentSpeechController,
                        features = ChatFeatures(attach = true, audioFiles = false),
                        intake = app.intake,
                        attachmentStore = app.attachments,
                        clock = app.container.clock,
                        toolLabel = app.registry::displayName,
                        savedState = SavedStateHandle(),
                    )
                }
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),   // each screen handles its own top inset
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) }, label = { Text("Chat") })
                            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Filled.Settings, contentDescription = null) }, label = { Text("Setup") })
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        if (tab == 0) SimpleChatScreen(vm, photoPicker) else EngineCheckScreen(check)
                    }
                }
            }
        }
    }
}
