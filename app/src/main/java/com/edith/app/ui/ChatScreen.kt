package com.edith.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.edith.app.network.ChatMessage
import com.edith.app.utils.SpeechManager
import com.edith.app.utils.RecordingManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val speechManager = remember { SpeechManager(context) }
    val recordingManager = remember { RecordingManager(context) }
    val uiState by viewModel.uiState.collectAsState()

    var isRecording by remember { mutableStateOf(false) }
    var recordedFilePath by remember { mutableStateOf<String?>(null) }

    fun startRecording() {
        recordingManager.startRecording()
        isRecording = true
        recordedFilePath = null // Clear previous
    }

    fun stopRecording() {
        val file = recordingManager.stopRecording()
        if (file != null) {
            // Using a mock URI for the prototype.
            recordedFilePath = "mock_uri_for_${file.name}" 
            isRecording = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(uiState.messages) { message ->
                MessageBubble(message = message)
            }
        }
        
        if (uiState.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // --- Recording Controls ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { startRecording() }, enabled = !isRecording) {
                Text("🎙️ Record")
            }
            Button(onClick = { stopRecording() }, enabled = isRecording) {
                Text("🛑 Stop")
            }
            
            // Trigger tool if a recording is available and user asks for analysis
            if (recordedFilePath != null) {
                Button(
                    onClick = { 
                        // Simulation: User asks "Analyze that recording"
                        // We simulate that command being sent.
                        viewModel.sendMessage("Analyze this recording.")
                        recordedFilePath = null // Reset after triggering
                    },
                    modifier = Modifier.width(120.dp)
                ) {
                    Text("Analyze")
                }
            }
        }
        // --- End Recording Controls ---

        var textInput by remember { mutableStateOf("") }

        // Text Input Area
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = textInput,
                onValueChange = { textInput = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message to Edith...") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            // Voice Input button remains
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

@Composable
fun MessageBubble(message: ChatMessage) {
    // Determine alignment and color based on role
    val isUser = message.role == "user"
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = message.content,
                    color = textColor
                )
                Text(
                    text = if (isUser) "You" else "Edith",
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}