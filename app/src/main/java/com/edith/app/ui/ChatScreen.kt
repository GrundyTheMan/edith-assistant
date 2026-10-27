package com.edith.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.edith.app.utils.Attachment
import com.edith.app.utils.AttachmentKind
import com.edith.app.utils.ReplyFormat
import com.edith.app.utils.ReplyPart
import com.edith.app.utils.SpeechManager
import com.edith.app.utils.RecordingManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val speechManager = remember { SpeechManager(context) }
    val recordingManager = remember { RecordingManager(context) }
    val uiState by viewModel.uiState.collectAsState()
    val speakingText by viewModel.speakingText.collectAsState()

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
        if (textInput.isBlank() && uiState.pendingAttachments.isEmpty()) return
        viewModel.sendMessage(textInput.trim())
        textInput = ""
    }

    var attachMenuOpen by remember { mutableStateOf(false) }
    // Android's photo picker needs no storage permission
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.addAttachment(it, AttachmentKind.IMAGE) }
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.addAttachment(it, AttachmentKind.AUDIO) }
    }

    // Keep the newest message (or the thinking indicator) in view
    val listState = rememberLazyListState()
    val itemCount = uiState.messages.size + if (uiState.isLoading) 1 else 0
    // Keyed on the last message too: regenerating a reply replaces it without changing the count
    LaunchedEffect(itemCount, uiState.messages.lastOrNull()?.content) {
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
                itemsIndexed(uiState.messages) { index, message ->
                    Column {
                        MessageBubble(message = message, findAttachment = viewModel::liveAttachment)
                        if (message.role == "assistant") {
                            ReplyActions(
                                isSpeaking = speakingText == message.content,
                                // Only the latest reply can be regenerated; later messages depend on older ones
                                canRegenerate = index == uiState.messages.lastIndex && !uiState.isLoading,
                                onPlay = { viewModel.speak(message.content) },
                                onStop = { viewModel.stopSpeaking() },
                                onRegenerate = { viewModel.regenerateLastReply() }
                            )
                        }
                    }
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

        if (uiState.pendingAttachments.isNotEmpty()) {
            PendingAttachmentsRow(uiState.pendingAttachments, onRemove = viewModel::removeAttachment)
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
                    leadingIcon = {
                        Box {
                            IconButton(onClick = { attachMenuOpen = true }, enabled = !uiState.isLoading) {
                                Icon(Icons.Filled.AttachFile, contentDescription = "Attach a file")
                            }
                            DropdownMenu(expanded = attachMenuOpen, onDismissRequest = { attachMenuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Photo") },
                                    leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                                    onClick = {
                                        attachMenuOpen = false
                                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Audio file") },
                                    leadingIcon = { Icon(Icons.Filled.AudioFile, contentDescription = null) },
                                    onClick = {
                                        attachMenuOpen = false
                                        pickAudio.launch(arrayOf("audio/*"))
                                    }
                                )
                            }
                        }
                    },
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
                    enabled = (textInput.isNotBlank() || uiState.pendingAttachments.isNotEmpty()) && !uiState.isLoading
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
fun MessageBubble(message: ChatEntry, findAttachment: (Int) -> Attachment?) {
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
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isUser) {
                    message.attachments.forEach { AttachmentView(it) }
                    if (message.content.isNotBlank()) {
                        Text(text = message.content, style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    ReplyContent(message.content, findAttachment)
                }
            }
        }
    }
}

/** Edith's reply: text, plus any files she wrote and attachments she sent back. */
@Composable
private fun ReplyContent(reply: String, findAttachment: (Int) -> Attachment?) {
    ReplyFormat.parse(reply).forEach { part ->
        when (part) {
            is ReplyPart.Text -> Text(text = part.text, style = MaterialTheme.typography.bodyLarge)
            is ReplyPart.File -> WrittenFileCard(part.file)
            is ReplyPart.SentAttachment -> findAttachment(part.number)?.let { AttachmentView(it) }
                ?: NoLongerAvailableNote("Attachment ${part.number} is no longer available")
            is ReplyPart.FileNotKept -> NoLongerAvailableNote("${part.name} · only kept while the app was open")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class) // LocalMinimumInteractiveComponentEnforcement
@Composable
private fun ReplyActions(
    isSpeaking: Boolean,
    canRegenerate: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onRegenerate: () -> Unit
) {
    // Material pads icon buttons out to 48dp, which leaves a gap under the bubble; keep this row compact
    CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
        ReplyActionButtons(isSpeaking, canRegenerate, onPlay, onStop, onRegenerate)
    }
}

@Composable
private fun ReplyActionButtons(
    isSpeaking: Boolean,
    canRegenerate: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onRegenerate: () -> Unit
) {
    // Pulled up so the icons sit right at the bubble's bottom edge, and the
    // space it frees is given back so the next message isn't pushed down
    val pullUp = 7.dp
    Row(
        modifier = Modifier
            .padding(start = 4.dp)
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val shift = pullUp.roundToPx()
                layout(placeable.width, placeable.height - shift) {
                    placeable.place(0, -shift)
                }
            }
    ) {
        val buttonModifier = Modifier.size(32.dp)
        val iconModifier = Modifier.size(18.dp)
        // Highlighted while this reply is being spoken
        val playColors = if (isSpeaking) {
            IconButtonDefaults.filledTonalIconButtonColors()
        } else {
            IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onPlay, colors = playColors, modifier = buttonModifier) {
            Icon(Icons.Filled.VolumeUp, contentDescription = "Play reply", modifier = iconModifier)
        }
        IconButton(
            onClick = onStop,
            enabled = isSpeaking,
            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = buttonModifier
        ) {
            Icon(Icons.Filled.Stop, contentDescription = "Stop playback", modifier = iconModifier)
        }
        if (canRegenerate) {
            IconButton(
                onClick = onRegenerate,
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = buttonModifier
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Regenerate reply", modifier = iconModifier)
            }
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
