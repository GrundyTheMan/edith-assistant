package com.edith.app.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The Chat tab for a platform with no attachments, voice or hands-free yet (K4.2: the first iPhone chat). It wires [ChatContent] to a
 * [ChatViewModel] and leaves every extra action at its default (nothing), so the buttons for them are not shown when the view model's
 * [ChatFeatures] are off. Android has its own glue (`ChatScreen` in `app/`) because it also asks for permissions, picks files and runs services.
 */
@Composable
fun SimpleChatScreen(vm: ChatViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    ChatContent(
        state = state,
        draft = vm.draft,
        actions = ChatActions(
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
