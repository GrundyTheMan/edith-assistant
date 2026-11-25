package com.edith.app.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The Chat tab for a platform with photo attachments, spoken replies and voice memos but no hands-free yet (K4.2: the first iPhone chat). It wires [ChatContent] to a
 * [ChatViewModel] and leaves every extra action at its default (nothing), so the buttons for them are not shown when the view model's
 * [ChatFeatures] are off. Android has its own glue (`ChatScreen` in `app/`) because it also asks for permissions, picks files and runs services.
 */
@Composable
fun SimpleChatScreen(vm: ChatViewModel, photoPicker: PhotoPicker? = null, microphone: MicrophoneAccess? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
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
            onRemoveAttachment = vm::removeAttachment,
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
