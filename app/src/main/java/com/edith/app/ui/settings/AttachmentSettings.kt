package com.edith.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.edith.app.attachments.Retention
import com.edith.app.di.viewModelFactory
import com.edith.app.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/** What Settings shows for attachments. */
data class AttachmentSettingsUi(
    val keep: Int = 10,
    val usage: Retention.Usage? = null,
    /** A lower number that would delete files, waiting for the owner's OK. */
    val confirmKeep: Pair<Int, Int>? = null,   // new value to files deleted
    val confirmDeleteAll: Boolean = false,
    val notice: String? = null,
)

/** Settings → Attachments (C16 §2): how many to keep each way, what's stored now, and Delete all. */
class AttachmentSettingsViewModel(private val settings: SettingsRepository, private val retention: Retention) : ViewModel() {
    private val _state = MutableStateFlow(AttachmentSettingsUi())
    val state: StateFlow<AttachmentSettingsUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.settings.collect { s -> _state.update { it.copy(keep = s.keepRecentAttachments) } }
        }
        refreshUsage()
    }

    private fun refreshUsage() {
        viewModelScope.launch { _state.update { it.copy(usage = retention.usage()) } }
    }

    /** A higher number is saved at once; a lower one that would delete files asks first. */
    fun requestKeep(keep: Int) {
        val value = keep.coerceIn(0, MAX)
        viewModelScope.launch {
            val removed = retention.wouldRemove(value)
            if (removed > 0 && value < _state.value.keep) _state.update { it.copy(confirmKeep = value to removed) }
            else save(value)
        }
    }

    fun confirmKeep() {
        val (value, _) = _state.value.confirmKeep ?: return
        _state.update { it.copy(confirmKeep = null) }
        viewModelScope.launch { save(value) }
    }

    fun cancelKeep() = _state.update { it.copy(confirmKeep = null) }

    private suspend fun save(value: Int) {
        settings.update { it.copy(keepRecentAttachments = value) }
        val removed = retention.prune(value)
        _state.update { it.copy(notice = if (removed > 0) "Deleted $removed older attachment${if (removed == 1) "" else "s"}." else null) }
        refreshUsage()
    }

    fun askDeleteAll() = _state.update { it.copy(confirmDeleteAll = true) }
    fun cancelDeleteAll() = _state.update { it.copy(confirmDeleteAll = false) }

    fun deleteAll() {
        _state.update { it.copy(confirmDeleteAll = false) }
        viewModelScope.launch {
            val n = retention.deleteAll()
            _state.update { it.copy(notice = "Deleted $n attachment${if (n == 1) "" else "s"}.") }
            refreshUsage()
        }
    }

    companion object {
        const val MAX = 50
        val Factory = viewModelFactory { c, _ -> AttachmentSettingsViewModel(c.settingsRepository, c.retention) }
    }
}

@Composable
fun AttachmentSettingsSection(vm: AttachmentSettingsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    AttachmentsContent(state, vm::requestKeep, vm::confirmKeep, vm::cancelKeep, vm::askDeleteAll, vm::deleteAll, vm::cancelDeleteAll)
}

@Composable
fun AttachmentsContent(
    state: AttachmentSettingsUi,
    onKeep: (Int) -> Unit,
    onConfirmKeep: () -> Unit,
    onCancelKeep: () -> Unit,
    onAskDeleteAll: () -> Unit,
    onDeleteAll: () -> Unit,
    onCancelDeleteAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Attachments", style = MaterialTheme.typography.titleMedium)
        var dragging by remember { mutableStateOf<Float?>(null) }
        val keep = dragging?.roundToInt() ?: state.keep
        Text(
            if (keep == 0) "Keep: none (deleted when Edith closes)" else "Keep the most recent $keep each way",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = dragging ?: state.keep.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { onKeep(it.roundToInt()) }; dragging = null },
            valueRange = 0f..AttachmentSettingsViewModel.MAX.toFloat(),
            steps = AttachmentSettingsViewModel.MAX - 1,
            modifier = Modifier.testTag(KEEP_ATTACHMENTS_TAG).semantics { contentDescription = "Attachments to keep" },
        )
        Text(
            "Keeps the most recent $keep attachments you sent and $keep Edith sent, on this phone only. Older ones are deleted.",
            style = MaterialTheme.typography.bodySmall,
        )
        state.usage?.let { Text(usageLine(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(ATTACHMENT_USAGE_TAG)) }
        OutlinedButton(onClick = onAskDeleteAll, enabled = (state.usage?.files ?: 0) > 0) { Text("Delete all attachments now") }
        state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }

    state.confirmKeep?.let { (value, removed) ->
        AlertDialog(
            onDismissRequest = onCancelKeep,
            title = { Text(if (value == 0) "Keep no attachments?" else "Keep $value each way?") },
            text = { Text("This will delete $removed older attachment${if (removed == 1) "" else "s"}.") },
            confirmButton = { TextButton(onClick = onConfirmKeep) { Text("Delete") } },
            dismissButton = { TextButton(onClick = onCancelKeep) { Text("Cancel") } },
        )
    }
    if (state.confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = onCancelDeleteAll,
            title = { Text("Delete all attachments?") },
            text = { Text("Every photo, audio file and file Edith wrote is deleted from this phone. The chats keep their names.") },
            confirmButton = { TextButton(onClick = onDeleteAll) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = onCancelDeleteAll) { Text("Cancel") } },
        )
    }
}

/** "12 files, 8.4 MB". */
fun usageLine(u: Retention.Usage): String {
    val size = when {
        u.bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", u.bytes / 1_000_000.0)
        u.bytes >= 1_000 -> "${u.bytes / 1_000} KB"
        else -> "${u.bytes} B"
    }
    return if (u.files == 0) "No attachments stored" else "${u.files} file${if (u.files == 1) "" else "s"}, $size"
}

const val KEEP_ATTACHMENTS_TAG = "keep_attachments"
const val ATTACHMENT_USAGE_TAG = "attachment_usage"
