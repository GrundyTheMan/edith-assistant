package com.edith.app.printer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * The owner's yes (C39 part 7.2): Jarvis's own question, a confirm button in Jarvis's own words (red for something dangerous), and a plain
 * button that says "Keep going" (or "Keep printing" when the confirm says Cancel). Never "OK" or "Yes", and the two never look alike.
 * It closes itself after ten seconds (the controller does that); dismissing it sends nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfirmSheet(sheet: ConfirmUi, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("confirm-sheet")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(sheet.question, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("confirm-question"))
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth().testTag("confirm-yes"),
                colors = if (sheet.danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
            ) { Text(sheet.confirm) }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().testTag("confirm-no")) { Text(sheet.keepLabel) }
        }
    }
}
