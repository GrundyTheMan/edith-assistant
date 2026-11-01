package com.edith.app.chat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.edith.app.chat.engine.ConfirmationRequest
import com.edith.app.data.db.MessageRole
import com.edith.app.data.db.MessageStatus
import kotlinx.coroutines.delay

/** How far Edith's action row is pulled up onto her bubble's bottom edge (the owner's "almost on top of the edge"). */
val ActionRowOverlap: Dp = 7.dp

private val Corner = 18.dp
private val SpeakerCorner = 4.dp
private val UserShape = RoundedCornerShape(topStart = Corner, topEnd = Corner, bottomEnd = SpeakerCorner, bottomStart = Corner)
private val EdithShape = RoundedCornerShape(topStart = Corner, topEnd = Corner, bottomEnd = Corner, bottomStart = SpeakerCorner)

/** One stored message: its bubble, then (Edith's) the action row, tools used, and the time (C07 §3, §5). */
@Composable
fun MessageItem(
    message: MessageUi,
    maxBubbleWidth: Dp,
    busy: Boolean,
    speaking: Boolean,
    onPlay: () -> Unit,
    onStopPlayback: () -> Unit,
    onRegenerate: () -> Unit,
) {
    val mine = message.role == MessageRole.User
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        when {
            mine -> Bubble(message.id, mine = true, maxBubbleWidth) {
                SelectionContainer { Text(message.text, color = MaterialTheme.colorScheme.onPrimary) }
            }
            message.status == MessageStatus.Failed -> FailedBubble(message, maxBubbleWidth, canRetry = message.isLatest && !busy, onRetry = onRegenerate)
            else -> {
                val stopped = message.status == MessageStatus.Cancelled
                Bubble(message.id, mine = false, maxBubbleWidth, Modifier.alpha(if (stopped) 0.7f else 1f)) {
                    SelectionContainer { MarkdownText(message.text + if (stopped) " (stopped)" else "") }
                }
                ActionRow(
                    messageId = message.id,
                    speaking = speaking,
                    showRegenerate = message.isLatestAssistant && !busy,
                    onPlay = onPlay,
                    onStopPlayback = onStopPlayback,
                    onRegenerate = onRegenerate,
                )
            }
        }
        if (message.toolsUsed.isNotEmpty()) {
            Text(
                "Used: " + message.toolsUsed.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
        Text(
            message.time,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** The owner's message from Send until it's stored (no time yet). */
@Composable
fun PendingUserItem(text: String, maxBubbleWidth: Dp) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Bubble(id = null, mine = true, maxBubbleWidth) { Text(text, color = MaterialTheme.colorScheme.onPrimary) }
    }
}

/**
 * Edith's reply while she writes it: a spinner and status label until the first words, then the text (C07 §4).
 * While a tool waits for the owner's OK, the bubble holds the confirmation card instead (C08 §6).
 */
@Composable
fun InFlightItem(inFlight: InFlight, maxBubbleWidth: Dp, onStop: () -> Unit, onAnswer: (approved: Boolean) -> Unit = {}) {
    Column(Modifier.fillMaxWidth().testTag(IN_FLIGHT_TAG), horizontalAlignment = Alignment.Start) {
        Bubble(id = null, mine = false, maxBubbleWidth) {
            if (inFlight.partialText.isNotBlank()) MarkdownText(inFlight.partialText)
            val request = inFlight.confirmation
            when {
                request != null -> ConfirmationCard(request, onAnswer, Modifier.padding(top = if (inFlight.partialText.isBlank()) 0.dp else 10.dp))
                inFlight.partialText.isBlank() -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(inFlight.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (inFlight.canStop) {
            CompactRow(Modifier.pullUpOntoBubble()) {
                CompactButton(Icons.Filled.StopCircle, "Stop reply", onClick = onStop)
            }
        }
    }
}

/** "Add to your calendar?" with its details and Confirm / Cancel (C08 §6). Nothing happens until one is tapped. */
@Composable
private fun ConfirmationCard(request: ConfirmationRequest, onAnswer: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.testTag(CONFIRMATION_TAG)) {
        Text(request.title, style = MaterialTheme.typography.titleSmall)
        request.details.forEach { (label, value) ->
            Row(Modifier.padding(top = 4.dp)) {
                Text("$label: ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onAnswer(false) }, modifier = Modifier.semantics { contentDescription = "Cancel action" }) {
                Text(request.denyLabel)
            }
            Button(onClick = { onAnswer(true) }, modifier = Modifier.semantics { contentDescription = "Confirm action" }) {
                Text(request.confirmLabel)
            }
        }
    }
}

@Composable
private fun FailedBubble(message: MessageUi, maxBubbleWidth: Dp, canRetry: Boolean, onRetry: () -> Unit) {
    Surface(
        shape = EdithShape,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.widthIn(max = maxBubbleWidth).testTag(bubbleTag(message.id)),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null, modifier = Modifier.size(18.dp).padding(top = 2.dp))
            Spacer(Modifier.width(8.dp))
            Text(message.errorMessage ?: "Edith couldn't reply.")
        }
    }
    if (canRetry) TextButton(onClick = onRetry) { Text("Retry") }
}

@Composable
private fun Bubble(id: Long?, mine: Boolean, maxBubbleWidth: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = if (mine) UserShape else EdithShape,
        color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.widthIn(max = maxBubbleWidth).then(if (id != null) Modifier.testTag(bubbleTag(id)) else Modifier),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) { content() }
    }
}

@Composable
private fun MarkdownText(markdown: String) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    val blocks = remember(markdown, linkColor, codeBackground) { renderMarkdown(markdown, linkColor, codeBackground) }
    Column {
        blocks.forEachIndexed { i, block ->
            val gap = when {
                i == 0 -> 0.dp
                block.tight -> 4.dp     // list items follow each other closely
                else -> 10.dp           // between paragraphs
            }
            if (block.marker == null) {
                Text(block.text, modifier = Modifier.padding(top = gap))
            } else {
                Row(Modifier.padding(top = gap)) {
                    Text(block.marker, modifier = Modifier.width(if (block.marker.length > 2) 26.dp else 18.dp))
                    Text(block.text)
                }
            }
        }
    }
}

/** Play / Stop / Regenerate under Edith's bubble, pulled up onto its edge (C07 §5). */
@Composable
private fun ActionRow(
    messageId: Long,
    speaking: Boolean,
    showRegenerate: Boolean,
    onPlay: () -> Unit,
    onStopPlayback: () -> Unit,
    onRegenerate: () -> Unit,
) {
    CompactRow(Modifier.pullUpOntoBubble().testTag(actionRowTag(messageId))) {
        CompactButton(Icons.AutoMirrored.Filled.VolumeUp, "Play reply", highlighted = speaking, onClick = onPlay)
        CompactButton(Icons.Filled.Stop, "Stop playback", enabled = speaking, onClick = onStopPlayback)
        if (showRegenerate) CompactButton(Icons.Filled.Refresh, "Regenerate reply", onClick = onRegenerate)
    }
}

/** A row of 32 dp buttons with 18 dp icons. The 48 dp minimum touch target is turned off for this row only (C07 §5). */
@Composable
private fun CompactRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Row(modifier.padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}

@Composable
private fun CompactButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val size = Modifier.size(32.dp)
    if (highlighted) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = size) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp))
        }
    } else {
        val tint = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = onClick, enabled = enabled, colors = tint, modifier = size) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp))
        }
    }
}

/** Draws the row [ActionRowOverlap] higher and takes that much less height, so it sits on the bubble's edge. */
private fun Modifier.pullUpOntoBubble(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val overlap = ActionRowOverlap.roundToPx()
    layout(placeable.width, (placeable.height - overlap).coerceAtLeast(0)) { placeable.place(0, -overlap) }
}

/** What the input row's voice controls do (C12). */
data class VoiceActions(
    val onRecord: () -> Unit = {},
    val onStopAndSend: () -> Unit = {},
    val onCancelRecording: () -> Unit = {},
    val onDictate: () -> Unit = {},
)

/**
 * The input row (C07 §6). Attach, Record and dictation appear when their chunks switch them on. While a voice memo
 * records (C12), the row shows the recording instead: red dot, elapsed time, level meter, Cancel and Stop & send.
 */
@Composable
fun InputRow(
    draft: String,
    canSend: Boolean,
    features: ChatFeatures,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    canRecord: Boolean = true,
    recording: RecordingUi? = null,
    level: () -> Float = { 0f },
    dictating: Boolean = false,
    voice: VoiceActions = VoiceActions(),
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (recording != null) {
            RecordingControls(recording, level, voice)
            return@Row
        }
        if (features.attach) IconButton(onClick = {}) { Icon(Icons.Filled.AttachFile, contentDescription = "Attach a file") }
        if (features.record) {
            IconButton(onClick = voice.onRecord, enabled = canRecord) {
                Icon(Icons.Filled.FiberManualRecord, contentDescription = "Record audio", tint = if (canRecord) RecordRed else LocalContentColor.current.copy(alpha = 0.38f))
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text(if (dictating) "Listening…" else "Message Edith") },
            shape = RoundedCornerShape(24.dp),
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
            trailingIcon = if (features.dictation) {
                {
                    IconButton(onClick = voice.onDictate) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = if (dictating) "Stop dictation" else "Speak a message",
                            tint = if (dictating) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                }
            } else null,
            modifier = Modifier.weight(1f).testTag(INPUT_TAG),
        )
        Spacer(Modifier.width(6.dp))
        FilledIconButton(onClick = onSend, enabled = canSend) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}

@Composable
private fun RowScope.RecordingControls(recording: RecordingUi, level: () -> Float, voice: VoiceActions) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(recording.startedAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val seconds = ((now - recording.startedAtMs) / 1_000).coerceAtLeast(0)
    IconButton(onClick = voice.onCancelRecording) { Icon(Icons.Filled.Close, contentDescription = "Cancel recording") }
    Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = RecordRed, modifier = Modifier.size(14.dp))
    Spacer(Modifier.width(8.dp))
    Text(
        "%d:%02d".format(seconds / 60, seconds % 60),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag(RECORDING_TIME_TAG),
    )
    Spacer(Modifier.width(12.dp))
    // The meter reads the level inside drawing only, so it redraws without recomposing the row.
    val track = MaterialTheme.colorScheme.surfaceVariant
    val bar = MaterialTheme.colorScheme.primary
    Canvas(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp))) {
        drawRect(track)
        drawRect(bar, size = size.copy(width = size.width * level().coerceIn(0f, 1f)))
    }
    Spacer(Modifier.width(8.dp))
    FilledIconButton(onClick = voice.onStopAndSend, colors = IconButtonDefaults.filledIconButtonColors(containerColor = RecordRed)) {
        Icon(Icons.Filled.Stop, contentDescription = "Stop and send recording")
    }
}

private val RecordRed = Color(0xFFD32F2F)

/** A line above the input row; [actionLabel] (e.g. "Allow") is optional. */
@Composable
fun ErrorLine(message: String, actionLabel: String?, onAction: () -> Unit, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (actionLabel != null) TextButton(onClick = onAction) { Text(actionLabel) }
        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss") }
    }
}

const val IN_FLIGHT_TAG = "in-flight"
const val CONFIRMATION_TAG = "confirmation"
const val INPUT_TAG = "message-input"
const val RECORDING_TIME_TAG = "recording-time"
fun bubbleTag(messageId: Long) = "bubble-$messageId"
fun actionRowTag(messageId: Long) = "action-row-$messageId"
