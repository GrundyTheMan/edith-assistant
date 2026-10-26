package com.edith.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.edith.app.ui.theme.EdithTheme
import com.edith.app.ui.ChatScreen
import com.edith.app.ui.CalendarScreen
import com.edith.app.ui.SettingsScreen
import com.edith.app.utils.SpeechManager
import com.edith.app.data.room.AppDatabase
import com.edith.app.data.room.ChatHistoryDao
import com.edith.app.ui.ChatViewModel
import com.edith.app.network.ToolExecutor
import com.edith.app.data.calendar.GoogleRepository
import com.edith.app.utils.AttachmentReader
import com.edith.app.utils.ConfigManager
import com.edith.app.utils.VoiceService
import com.edith.app.network.AuthManager
import com.edith.app.network.AudioService
import com.edith.app.network.NetworkClient
import com.edith.app.network.WebSearchService
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    private lateinit var speechManager: SpeechManager
    private lateinit var database: AppDatabase
    private lateinit var authManager: AuthManager
    private lateinit var voiceService: VoiceService

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        speechManager = SpeechManager(this)
        database = AppDatabase.getDatabase(this)
        authManager = AuthManager(this)
        voiceService = VoiceService(this, ConfigManager(this))

        setContent {
            EdithTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(database.chatHistoryDao(), authManager, voiceService)
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        authManager.handleActivityResult(requestCode, data)
    }

    override fun onDestroy() {
        super.onDestroy()
        speechManager.release()
        voiceService.release()
    }
}

private enum class Destination(val label: String, val icon: ImageVector) {
    Chat("Chat", Icons.Filled.Chat),
    Calendar("Calendar", Icons.Filled.CalendarMonth),
    Settings("Settings", Icons.Filled.Settings)
}

@OptIn(ExperimentalLayoutApi::class) // consumeWindowInsets
@Composable
fun MainScreen(chatHistoryDao: ChatHistoryDao, authManager: AuthManager, voiceService: VoiceService) {
    var currentScreen by remember { mutableStateOf(Destination.Chat) }
    val context = LocalContext.current
    val activity = context as Activity
    val configManager = remember { ConfigManager(context) }

    val authState by authManager.authState.collectAsState()

    // Google sign-in is optional: calendar and email data are still mocked
    val account = authState
    val chatViewModel = remember(account) {
        val googleRepository = GoogleRepository(NetworkClient.getGoogleApi(), account)
        val audioService = AudioService(configManager)
        val toolExecutor = ToolExecutor(googleRepository, account, WebSearchService(), audioService)
        ChatViewModel(chatHistoryDao, toolExecutor, configManager, audioService, voiceService, AttachmentReader(context))
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentScreen == destination,
                        onClick = { currentScreen = destination },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            when (currentScreen) {
                Destination.Chat -> ChatScreen(chatViewModel)
                Destination.Calendar -> CalendarScreen(account)
                Destination.Settings -> SettingsScreen(
                    configManager = configManager,
                    signedInEmail = account?.email,
                    onSignIn = { authManager.signIn(activity) }
                )
            }
        }
    }
}
