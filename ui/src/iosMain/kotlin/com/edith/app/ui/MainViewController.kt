package com.edith.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import com.edith.app.chat.ui.IosFilePicker
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
 * Chat has photo attachments (K4.3); voice and audio files come later. [rtc] is the Swift shell's WebRTC for Ring's live view (K4.18); null: Ring has no live view.
 */
fun MainViewController(rtc: SwiftRtcFactory?): UIViewController {
    // The crash hook goes in first, and what the last run left is read before this run marks itself as in front (D58).
    val crash = com.edith.app.core.crash.IosCrashReporter.create().also { it.install() }
    val crashAtLaunch = crash.pending()
    val app = createIosApp(rtc?.let { factory -> com.edith.app.ring.RingPeerFactory { factory.makePeer()?.let(::BridgedRingPeer) } })
    val check = EngineCheck(app.container, app.secrets, app.settings)
    app.intake.clearLeftovers()
    app.container.appScope.launch {
        app.container.retention.onStart()
        app.container.currentConversation.resolve()
    }
    val photoPicker = IosPhotoPicker()
    val audioPicker = IosFilePicker()
    val microphone = IosMicrophoneAccess()
    val fileExporter = com.edith.app.chat.ui.IosFileExporter()
    return ComposeUIViewController {
        EdithTheme {
            Surface {
                var tab by rememberSaveable { mutableStateOf(0) }
                val homeVm = viewModel { com.edith.app.home.ui.HomeViewModel(app.home, app.container.clock) { app.accounts.jarvisConnection() != null } }
                val printerVm = viewModel { com.edith.app.printer.ui.PrinterViewModel(app.printerSession, app.printerPrefs, app.container.clock) }
                var showPrinter by rememberSaveable { mutableStateOf(false) }
                var showCheck by rememberSaveable { mutableStateOf(false) }
                // The last crash report (D58): the banner at the top, and Settings → About keeps it until it is dismissed. Edith is "in front" between
                // start and stop; a run that ends there without a report is told as a crash.
                var crashReport by remember { mutableStateOf(crashAtLaunch) }
                val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
                DisposableEffect(lifecycle) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_START) crash.markFront()
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) crash.markBackground()
                    }
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }
                // While you were away: what the cameras reported since the app was last opened (K4.16), found once at launch.
                var catchUp by remember { mutableStateOf<List<com.edith.app.alerts.Alert>>(emptyList()) }
                androidx.compose.runtime.LaunchedEffect(Unit) { catchUp = app.alerts.catchUp() }
                val banners by app.alerts.banners.collectAsState()
                val settingsVm = viewModel { com.edith.app.settings.SettingsViewModel(app.settings, app.container.modelCatalog, app.container.ollama, app.container.modelSelection, object : com.edith.app.core.permissions.LocalNetworkAccess { override fun isGranted() = true }, com.edith.app.settings.AtsPlainHttpRule) }
                val webSearchVm = viewModel { com.edith.app.ui.settings.WebSearchSettingsViewModel(app.container.webSearchSetup, app.container.ollamaWeb) }
                val attachmentsVm = viewModel { com.edith.app.ui.settings.AttachmentSettingsViewModel(app.settings, app.container.retention) }
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
                            NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, icon = { Icon(Icons.Filled.Settings, contentDescription = null) }, label = { Text("Settings") })
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        when (tab) {
                            0 -> SimpleChatScreen(vm, photoPicker, microphone, audioPicker, fileExporter)
                            1 -> if (showPrinter) IosPrinterScreen(printerVm, onBack = { showPrinter = false }, openPairing = { showPrinter = false; tab = 3 })
                                else IosHomeScreen(homeVm, openMore = { tab = 3 }, openPrinter = { showPrinter = true }, catchUp = catchUp, onDismissCatchUp = { catchUp = emptyList() })
                            2 -> IosCalendarScreen(calendarVm, { todoVm() }, openMore = { tab = 3 })
                            else -> if (showCheck) Column {
                                androidx.compose.material3.TextButton(onClick = { showCheck = false }, modifier = Modifier.statusBarsPadding()) { Text("‹ Settings") }
                                EngineCheckScreen(check)
                            } else IosSettingsScreen(
                                settingsVm, webSearchVm, attachmentsVm, app.settings, app.speech, app.accounts, app.home, app.alertPrefs,
                                edithVersion = (platform.Foundation.NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String) ?: "",
                                openEngineCheck = { showCheck = true },
                                crashReport = crashReport,
                            )
                        }
                        // New camera alerts while the app is open, over whatever tab is showing.
                        com.edith.app.alerts.ui.AlertBanners(
                            banners,
                            onOpenClips = { showPrinter = false; tab = 1; homeVm.openClips(); app.alerts.dismissAll() },
                            onDismiss = app.alerts::dismiss,
                            modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter),
                        )
                        crashReport?.let {
                            com.edith.app.ui.components.CrashBanner(it, onDismiss = { crash.dismiss(); crashReport = null }, modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter))
                        }
                    }
                }
            }
        }
    }
}
