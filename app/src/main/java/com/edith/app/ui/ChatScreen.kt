package com.edith.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.edith.app.network.ChatMessage
import com.edith.app.utils.SpeechManager
import com.edith.app.utils.RecordingManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val speechManager = remember { SpeechManager(context) }
    val recordingManager = remember { RecordingManager(context) }
    val uiState by viewModel.uiState.collectAsState()

    var isRecording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableStateOf(0) }
    var micMessage by remember { mutableStateOf<String?>(null) }

    // What to run once the microphone permission is granted
    var pendingMicAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            pendingMicAction?.invoke()
        } else {
            micMessage = "Edith needs microphone access to hear you. You can allow it in the app's system settings."
        }
        pendingMicAction = null
    }

    fun withMicPermission(action: () -> Unit) {
        micMessage = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingMicAction = action
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun startRecording() {
        viewModel.stopSpeaking() // so Edith's voice doesn't end up in the recording
        runCatching { recordingManager.startRecording() }
            .onSuccess { isRecording = true }
            .onFailure { micMessage = "Couldn't start recording: ${it.message}" }
    }

    // Stopping sends the recording: it's transcribed and the transcript becomes the user's message
    fun stopRecordingAndSend() {
        isRecording = false
        val file = recordingManager.stopRecording() ?: return
        if (RecordingManager.durationSeconds(file) < 1) {
            file.delete()
            micMessage = "That recording was too short to send."
            return
        }
        viewModel.sendRecording(file)
    }

    LaunchedEffect(isRecording) {
        recordingSeconds = 0
        while (isRecording) {
            delay(1000)
            recordingSeconds++
        }
    }

    // Don't leave the microphone running if the user leaves the chat mid-recording
    DisposableEffect(Unit) {
        onDispose { recordingManager.stopRecording()?.delete() }
    }

    var textInput by remember { mutableStateOf("") }

    fun send() {
        if (textInput.isBlank()) return
        viewModel.sendMessage(textInput.trim())
        textInput = ""
    }

    // Keep the newest message (or the thinking indicator) in view
    val listState = rememberLazyListState()
    val itemCount = uiState.messages.size + if (uiState.isLoading) 1 else 0
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        if (uiState.messages.isEmpty() && !uiState.isLoading) {
            EmptyChat(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.messages) { message ->
                    MessageBubble(message = message)
                }
                if (uiState.isLoading) {
                    item { ThinkingBubble(label = uiState.loadingLabel) }
                }
            }
        }

        uiState.error?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        if (isRecording) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.FiberManualRecord,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Recording %d:%02d · tap stop to send".format(recordingSeconds / 60, recordingSeconds % 60),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        micMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        Surface(tonalElevation = 2.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { if (isRecording) stopRecordingAndSend() else withMicPermission { startRecording() } },
                    enabled = isRecording || !uiState.isLoading
                ) {
                    if (isRecording) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop and send recording", tint = MaterialTheme.colorScheme.error)
                    } else {
                        Icon(Icons.Filled.FiberManualRecord, contentDescription = "Record audio")
                    }
                }

                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message Edith") },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    trailingIcon = {
                        IconButton(onClick = {
                            withMicPermission {
                                viewModel.stopSpeaking()
                                speechManager.startListening { result ->
                                    textInput = result
                                }
                            }
                        }) {
                            Icon(Icons.Filled.Mic, contentDescription = "Speak a message")
                        }
                    }
                )

                Spacer(modifier = Modifier.width(4.dp))

                FilledIconButton(
                    onClick = { send() },
                    enabled = textInput.isNotBlank() && !uiState.isLoading
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun EmptyChat(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "Ask Edith anything — your schedule, your inbox, or what's on your mind.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    // Tool results and other system notes aren't part of the conversation proper
    if (message.role == "system") {
        Text(
            text = message.content,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
        )
        return
    }

    val isUser = message.role == "user"
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    // The corner nearest the speaker's side is squared off, like a speech tail
    val shape = RoundedCornerShape(
        topStart = 18.dp,
        topEnd = 18.dp,
        bottomStart = if (isUser) 18.dp else 4.dp,
        bottomEnd = if (isUser) 4.dp else 18.dp
    )
    val maxBubbleWidth = (LocalConfiguration.current.screenWidthDp * 0.78f).dp

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = bubbleColor,
            contentColor = textColor,
            shape = shape,
            modifier = Modifier.widthIn(max = maxBubbleWidth)
        ) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun ThinkingBubble(label: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
