package com.edith.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.edith.app.chat.ui.IosMicrophoneAccess
import com.edith.app.chat.ui.IosAudioPicker
import com.edith.app.chat.ui.IosPhotoPicker
import com.edith.app.chat.ui.SimpleChatScreen
import com.edith.app.di.createIosApp
import com.edith.app.diagnostics.EngineCheck
import com.edith.app.ui.diagnostics.EngineCheckScreen
import com.edith.app.ui.theme.EdithTheme
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
    val audioPicker = IosAudioPicker()
    val microphone = IosMicrophoneAccess()
    return ComposeUIViewController {
        EdithTheme {
            Surface {
                var tab by rememberSaveable { mutableStateOf(0) }
                val homeVm = viewModel { com.edith.app.home.ui.HomeViewModel(app.home, app.container.clock) { app.accounts.jarvisConnection() != null } }
                val printerVm = viewModel { com.edith.app.printer.ui.PrinterViewModel(app.printerSession, app.printerPrefs, app.container.clock) }
                var showPrinter by rememberSaveable { mutableStateOf(false) }
                val calendarVm = viewModel { com.edith.app.accounts.ui.CalendarViewModel(app.accounts, app.accounts.accounts, app.accounts.loaded, app.container.clock) }
                // The To-do view loads only once it is picked, so its view model is made then.
                val todoVm: @Composable () -> com.edith.app.accounts.ui.TodoViewModel = { viewModel { com.edith.app.accounts.ui.TodoViewModel(app.accounts, app.accounts.accounts, app.accounts.tasksAccess, app.accounts.loaded, app.container.clock, grant = { id, a, show -> app.accounts.grantTasks(id, a, show) }, consentResult = { app.accounts.onConsentResult(it) }, appScope = app.container.appScope) } }
                val vm = viewModel {
                    ChatViewModel(
                        repo = app.container.chatRepository,
                        engine = app.container.chatEngine,
                        current = app.container.currentConversation,
                        speech = app.speech,
                        features = ChatFeatures(attach = true, audioFiles = true, record = true, dictation = app.dictation.isAvailable),
                        voiceMemos = app.voiceMemos,
                        attachmentPlayer = app.player,
                        dictation = app.dictation,
                        handsFree = app.handsFree,
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
                            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Filled.Home, contentDescription = null) }, label = { Text("Home") })
                            NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) }, label = { Text("Calendar") })
                            NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, icon = { Icon(Icons.Filled.Settings, contentDescription = null) }, label = { Text("Setup") })
                            NavigationBarItem(selected = tab == 4, onClick = { tab = 4 }, icon = { Icon(Icons.Filled.AccountCircle, contentDescription = null) }, label = { Text("More") })
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        when (tab) {
                            0 -> SimpleChatScreen(vm, photoPicker, microphone, audioPicker)
                            1 -> if (showPrinter) IosPrinterScreen(printerVm, onBack = { showPrinter = false }, openPairing = { showPrinter = false; tab = 4 })
                                else IosHomeScreen(homeVm, openMore = { tab = 4 }, openPrinter = { showPrinter = true })
                            2 -> IosCalendarScreen(calendarVm, { todoVm() }, openMore = { tab = 4 })
                            3 -> EngineCheckScreen(check)
                            else -> AccountsScreen(app.accounts, app.settings, app.home)
                        }
                    }
                }
            }
        }
    }
}
