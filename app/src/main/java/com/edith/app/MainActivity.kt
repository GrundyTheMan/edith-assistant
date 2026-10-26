package com.edith.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
import com.edith.app.utils.ConfigManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speechManager = SpeechManager(this)
        database = AppDatabase.getDatabase(this)
        authManager = AuthManager(this)
        
        setContent {
            EdithTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(database.chatHistoryDao(), authManager)
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
    }
}

@Composable
fun MainScreen(chatHistoryDao: ChatHistoryDao, authManager: AuthManager) {
    var currentScreen by remember { mutableStateOf("chat") }
    val context = LocalContext.current
    val activity = context as Activity
    val configManager = remember { ConfigManager(context) }

    val authState by authManager.authState.collectAsState()

    Row(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(8.dp)) {
            Button(onClick = { currentScreen = "chat" }) {
                Text("Chat")
            }
            Button(onClick = { currentScreen = "calendar" }) {
                Text("Calendar")
            }
            Button(onClick = { currentScreen = "settings" }) {
                Text("Settings")
            }
            Button(onClick = { authManager.signIn(activity) }) {
                Text("Login")
            }
        }

        Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val account = authState
            if (account == null) {
                Text("Please login to continue.")
            } else {
                val chatViewModel = remember(account) {
                    val googleRepository = GoogleRepository(NetworkClient.getGoogleApi(), account)
                    val toolExecutor = ToolExecutor(googleRepository, account, WebSearchService(), AudioService(configManager))
                    ChatViewModel(chatHistoryDao, toolExecutor, configManager)
                }
                when (currentScreen) {
                    "chat" -> ChatScreen(chatViewModel)
                    "calendar" -> CalendarScreen(account)
                    "settings" -> SettingsScreen(configManager)
                }
            }
        }
    }
}
