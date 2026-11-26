package com.edith.app.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The Chat tab for a platform with photo attachments, spoken replies and voice memos but no hands-free yet (K4.2: the first iPhone chat). It wires [ChatContent] to a
 * [ChatViewModel] and leaves every extra action at its default (nothing), so the buttons for them are not shown when the view model's
 * [ChatFeatures] are off. Android has its own glue (`ChatScreen` in `app/`) because it also asks for permissions, picks files and runs services.
 */
@Composable
fun SimpleChatScreen(
    vm: ChatViewModel,
    photoPicker: PhotoPicker? = null,
    microphone: MicrophoneAccess? = null,
    audioPicker: AudioPicker? = null,
    /** Edith's file cards: View, Save and Share (C16); null: the buttons do nothing. */
    fileExporter: FileExporter? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    // A photo full screen, and a file of Edith's shown as text (name to text): both are dialogs over the chat, as on Android.
    var viewingImage by rememberSaveable { mutableStateOf<String?>(null) }
    var viewingFile by remember { mutableStateOf<Pair<String, String>?>(null) }
    viewingImage?.let { ImageViewer(it) { viewingImage = null } }
    viewingFile?.let { (name, text) -> FileViewer(name, text) { viewingFile = null } }
    val files = if (fileExporter == null) FileActions() else FileActions(
        onView = { a -> a.path?.let { path -> viewingFile = a.fileName to (fileExporter.readText(path) ?: "This file can't be read.") } },
        onSave = { a -> a.path?.let { fileExporter.save(it, a.fileName) } },
        onShare = { a -> a.path?.let { fileExporter.share(it, a.fileName) } },
    )
    ChatContent(
        state = state,
        draft = vm.draft,
        permissionDenied = permissionDenied,
        recordingLevel = { vm.recordingLevel.value },
        actions = ChatActions(
            voice = VoiceActions(
                onRecord = {
                    microphone?.request { granted ->
                        permissionDenied = !granted
                        if (granted) vm.startRecording()
                    }
                },
                onStopAndSend = { vm.stopAndSendRecording() },
                onCancelRecording = { vm.cancelRecording() },
            ),
            onOpenAppSettings = { microphone?.openSettings() },
            onAttachPhoto = { photoPicker?.pick(MAX_PHOTOS) { vm.attachImages(it) } },
            onHandsFree = {
                if (state.handsFree?.on == true) vm.stopHandsFree()
                else microphone?.request { granted -> if (granted) vm.startHandsFree() else { permissionDenied = true } }
            },
            onTalk = vm::talk,
            onAttachAudio = { audioPicker?.pick { address -> address?.let(vm::attachAudio) } },
            onRemoveAttachment = vm::removeAttachment,
            onToggleAttachment = vm::toggleAttachmentPlayback,
            onOpenImage = { viewingImage = it },
            files = files,
            onDraftChange = vm::onDraftChange,
            onSend = vm::send,
            onStopReply = vm::stopReply,
            onAnswerConfirmation = vm::answerConfirmation,
            onRegenerate = vm::regenerate,
            onPlay = vm::play,
            onStopPlayback = vm::stopPlayback,
            onDismissError = vm::dismissError,
            onNewChat = vm::newChat,
            onSelect = vm::select,
            onRename = vm::rename,
            onDelete = vm::delete,
        ),
    )
}

/** How many photos one pick may add (as on Android). */
private const val MAX_PHOTOS = 4
