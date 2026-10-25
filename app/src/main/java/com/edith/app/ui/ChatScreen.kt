package com.edith.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.edith.app.network.ChatMessage
import com.edith.app.utils.SpeechManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val speechManager = remember { SpeechManager(context) }
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(uiState.messages) { message ->
                Text(
                    text = "${message.role}: ${message.content}",
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
        
        if (uiState.isLoading) {
            CircularProgressIndicator()
        }

        var textInput by remember { mutableStateOf("") }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = textInput,
                onValueChange = { textInput = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message to Edith...") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = {
                speechManager.startListening { result ->
                    textInput = result
                }
            }) {
                Text("🎤")
            }
        }
        
        Button(
            onClick = {
                viewModel.sendMessage(textInput)
                textInput = ""
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Send")
        }
    }
}
