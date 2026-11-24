package com.edith.app.chat.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Everything the chat screen can do, so [ChatContent] can be tested without a ViewModel. */
data class ChatActions(
    val onDraftChange: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onStopReply: () -> Unit = {},
    val onAnswerConfirmation: (approved: Boolean) -> Unit = {},
    val onRegenerate: () -> Unit = {},
    val onPlay: (MessageUi) -> Unit = {},
    val onStopPlayback: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onAllowLocalNetwork: () -> Unit = {},
    val onNewChat: () -> Unit = {},
    val onSelect: (Long) -> Unit = {},
    val onRename: (Long, String) -> Unit = { _, _ -> },
    val onDelete: (Long) -> Unit = {},
    val voice: VoiceActions = VoiceActions(),
    /** Android's settings page for Edith (the microphone was refused and Android won't ask again). */
    val onOpenAppSettings: () -> Unit = {},
    /** The top bar's hands-free button (C14): on or off. */
    val onHandsFree: () -> Unit = {},
    /** Attachments (C15): the paperclip's two pickers, ✕ on a chip, ▶/■ on an audio attachment, a photo full screen. */
    val onAttachPhoto: () -> Unit = {},
    val onAttachAudio: () -> Unit = {},
    val onRemoveAttachment: (Long) -> Unit = {},
    val onToggleAttachment: (AttachmentUi) -> Unit = {},
    val onOpenImage: (String) -> Unit = {},
    /** Edith's file cards (C16): View, Save, Share. */
    val files: FileActions = FileActions(),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ChatContent(
    state: ChatUiState,
    draft: String,
    actions: ChatActions,
    permissionDenied: Boolean = false,
    recordingLevel: () -> Float = { 0f },
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var deleting by remember { mutableStateOf<ConversationItem?>(null) }

    // Back closes the conversation list rather than leaving the app.
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawer(
                conversations = state.conversations,
                onNewChat = { actions.onNewChat(); scope.launch { drawerState.close() } },
                onSelect = { actions.onSelect(it); scope.launch { drawerState.close() } },
                onRename = { renaming = it.id to it.title },
                onDelete = { deleting = it },
            )
        },
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            TopAppBar(
                title = {
                    Text(
                        state.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClickLabel = "Rename chat") {
                            state.conversationId?.let { renaming = it to state.title }
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Conversations")
                    }
                },
                actions = {
                    state.handsFree?.let { hf ->
                        IconToggleButton(
                            checked = hf.on,
                            onCheckedChange = { actions.onHandsFree() },
                            enabled = hf.on || state.canStartHandsFree,
                            colors = IconButtonDefaults.iconToggleButtonColors(checkedContentColor = MaterialTheme.colorScheme.primary),
                        ) {
                            Icon(
                                if (hf.on) Icons.Filled.Hearing else Icons.Outlined.Hearing,
                                contentDescription = if (hf.on) "Turn hands-free off" else "Turn hands-free on",
                            )
                        }
                    }
                    IconButton(onClick = actions.onNewChat) { Icon(Icons.Filled.Add, contentDescription = "New chat") }
                },
            )
            state.handsFree?.status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(HANDS_FREE_STATUS_TAG),
                )
            }
            MessageList(state, actions, Modifier.weight(1f))
            state.error?.let { error ->
                ErrorLine(
                    message = error.message,
                    actionLabel = when {
                        error.opensAppSettings -> "Open settings"
                        !error.needsLocalNetworkPermission -> null
                        permissionDenied -> "Open settings"
                        else -> "Allow"
                    },
                    onAction = if (error.opensAppSettings) actions.onOpenAppSettings else actions.onAllowLocalNetwork,
                    onDismiss = actions.onDismissError,
                )
            }
            state.sendHint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }
            PendingAttachments(state.pending, actions.onRemoveAttachment)
            InputRow(
                draft = draft,
                canSend = state.canSend(draft),
                features = state.features,
                onDraftChange = actions.onDraftChange,
                onSend = actions.onSend,
                canRecord = state.canRecord,
                recording = state.recording,
                level = recordingLevel,
                dictating = state.dictating,
                voice = actions.voice,
                micFree = state.micFree,
                onAttachPhoto = actions.onAttachPhoto,
                onAttachAudio = actions.onAttachAudio,
            )
        }
    }

    renaming?.let { (id, title) ->
        RenameDialog(title, onSave = { actions.onRename(id, it); renaming = null }, onDismiss = { renaming = null })
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this chat?") },
            text = { Text("\"${item.title}\" and all its messages will be deleted. This can't be undone.") },
            confirmButton = { TextButton(onClick = { actions.onDelete(item.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/**
 * The messages, newest at the bottom. The list is laid out bottom-up, so a reply that grows while streaming stays
 * in view on its own. Whether to follow new items is decided by where the owner last *scrolled* to, never by
 * items being added (two can arrive at once on Send): within about a screen of the bottom, new items scroll into
 * view; further up, a "New" button appears instead of yanking them down (C07 §2). Sending always goes to the bottom.
 */
@Composable
private fun MessageList(state: ChatUiState, actions: ChatActions, modifier: Modifier) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var followBottom by rememberSaveable { mutableStateOf(true) }
    var hasUnseen by remember { mutableStateOf(false) }

    // Re-decide "follow the bottom" only while a scroll is happening (the owner's drag or our own scroll).
    LaunchedEffect(listState) {
        var wasScrolling = false
        snapshotFlow { Triple(listState.isScrollInProgress, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .collect { (scrolling, index, offset) ->
                val scrollJustMoved = scrolling || wasScrolling   // also the position where a fling came to rest
                wasScrolling = scrolling
                if (!scrollJustMoved) return@collect
                val screen = listState.layoutInfo.viewportSize.height
                followBottom = index == 0 && offset < screen || index == 1 && offset < screen / 2
                if (followBottom) hasUnseen = false
            }
    }

    // What's at the bottom: changes when a message is added, a reply starts or ends, or regenerate replaces one.
    val bottomKey = when {
        state.inFlight != null -> "in-flight"
        state.pendingUserText != null -> "pending"
        else -> state.messages.lastOrNull()?.let { "${it.id}:${it.status}" }
    }
    LaunchedEffect(state.conversationId) { listState.scrollToItem(0); followBottom = true; hasUnseen = false }
    LaunchedEffect(state.pendingUserText) {
        if (state.pendingUserText != null) { followBottom = true; hasUnseen = false; listState.animateScrollToItem(0) }
    }
    LaunchedEffect(bottomKey) {
        if (bottomKey == null) return@LaunchedEffect
        if (followBottom) listState.animateScrollToItem(0) else hasUnseen = true
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.78f
        if (state.loaded && state.messages.isEmpty() && state.inFlight == null && state.pendingUserText == null) {
            Text(
                "Ask Edith anything — your schedule, the weather, or what's on your mind.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
            modifier = Modifier.fillMaxSize().testTag(MESSAGE_LIST_TAG),
        ) {
            state.inFlight?.let { inFlight ->
                item(key = "in-flight") { InFlightItem(inFlight, maxBubbleWidth, actions.onStopReply, actions.onAnswerConfirmation) }
            }
            state.pendingUserText?.let { text -> item(key = "pending") { PendingUserItem(text, maxBubbleWidth) } }
            items(state.messages.asReversed(), key = { it.id }) { message ->
                MessageItem(
                    message = message,
                    maxBubbleWidth = maxBubbleWidth,
                    busy = state.busy,
                    speaking = state.speakingMessageId == message.id,
                    onPlay = { actions.onPlay(message) },
                    onStopPlayback = actions.onStopPlayback,
                    onRegenerate = actions.onRegenerate,
                    playingAttachmentId = state.playingAttachmentId,
                    onToggleAttachment = actions.onToggleAttachment,
                    onOpenImage = actions.onOpenImage,
                    fileActions = actions.files,
                )
            }
        }
        if (hasUnseen && !followBottom) {
            ExtendedFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                icon = { Icon(Icons.Filled.ArrowDownward, contentDescription = null) },
                text = { Text("New") },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).testTag(NEW_BELOW_TAG),
            )
        }
    }
}

/** The conversation list (C07 §8): newest first; tap to open, long-press or ⋮ to rename or delete. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationDrawer(
    conversations: List<ConversationItem>,
    onNewChat: () -> Unit,
    onSelect: (Long) -> Unit,
    onRename: (ConversationItem) -> Unit,
    onDelete: (ConversationItem) -> Unit,
) {
    ModalDrawerSheet {
        Text("Conversations", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp))
        NavigationDrawerItem(
            label = { Text("Start a new chat") },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            selected = false,
            onClick = onNewChat,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.testTag(DRAWER_LIST_TAG)) {
            items(conversations, key = { it.id }) { item ->
                var menuOpen by remember { mutableStateOf(false) }
                Surface(
                    color = if (item.isCurrent) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
                        .combinedClickable(onClick = { onSelect(item.id) }, onLongClick = { menuOpen = true }),
                ) {
                    Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            item.preview?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        if (item.isReplying) {
                            CircularProgressIndicator(Modifier.size(14.dp).testTag("replying-${item.id}"), strokeWidth = 2.dp)
                        } else {
                            Text(item.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Options for ${item.title}")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename(item) })
                                DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete(item) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(if (current == ChatUiState.NEW_CHAT_TITLE) "" else current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename chat") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

const val MESSAGE_LIST_TAG = "message-list"
const val DRAWER_LIST_TAG = "conversation-list"
const val NEW_BELOW_TAG = "new-messages-below"
const val HANDS_FREE_STATUS_TAG = "hands-free-status"
