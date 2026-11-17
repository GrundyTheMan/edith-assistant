package com.edith.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.edith.app.jarvis.JarvisAccounts

const val ADD_JARVIS_TAG = "add_jarvis"
const val JARVIS_ADDRESS_TAG = "jarvis_address"
const val JARVIS_FIND_TAG = "jarvis_find"
const val JARVIS_FINGERPRINT_TAG = "jarvis_fingerprint"
const val JARVIS_CODE_TAG = "jarvis_code"
const val JARVIS_PAIR_TAG = "jarvis_pair"
const val JARVIS_MESSAGE_TAG = "jarvis_message"

/**
 * C38 §1: pairing with Jarvis in two steps. First the PC's address (the Tailscale one works at home and away); Edith
 * shows the certificate fingerprint she found, and the owner checks it against Jarvis's screen. Then the code Jarvis
 * shows, which Edith proves without sending it. [fixedAddress]: pair the same PC again.
 */
@Composable
internal fun JarvisDialog(
    fixedAddress: String?,
    onFind: (address: String, done: (JarvisAccounts.Probe) -> Unit) -> Unit,
    onPair: (base: String, fingerprint: String, code: String, done: (String?) -> Unit) -> Unit,
    onClose: () -> Unit,
) {
    var address by remember { mutableStateOf(fixedAddress.orEmpty()) }
    var found by remember { mutableStateOf<JarvisAccounts.Probe.Found?>(null) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Pair with Jarvis") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val f = found
                if (f == null) {
                    Text(
                        "Your work mail, calendar and to-dos live in Outlook on your PC. Jarvis can share them with Edith, read-only " +
                            "(Edith can also save an email draft). In Jarvis: Settings → Edith, switch it on, then type the address it shows here. " +
                            "The Tailscale address works at home and away.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = address, onValueChange = { address = it; message = null }, singleLine = true, enabled = !busy,
                        label = { Text("Jarvis address") }, placeholder = { Text("100.64.0.1:47821") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().testTag(JARVIS_ADDRESS_TAG),
                    )
                } else {
                    Text("Check that Jarvis shows the same fingerprint (Settings → Edith):", style = MaterialTheme.typography.bodyMedium)
                    Text(f.groups, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(JARVIS_FINGERPRINT_TAG))
                    Text("If it matches, tap Pair a phone in Jarvis and type its code:", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = code, onValueChange = { code = it.uppercase(); message = null }, singleLine = true, enabled = !busy,
                        label = { Text("Pairing code") }, placeholder = { Text("ABCDE-FGHJK") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().testTag(JARVIS_CODE_TAG),
                    )
                }
                if (busy) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(if (found == null) "Looking for Jarvis…" else "Pairing…")
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(JARVIS_MESSAGE_TAG)) }
            }
        },
        confirmButton = {
            val f = found
            if (f == null) {
                TextButton(
                    onClick = {
                        busy = true
                        onFind(address) { probe ->
                            busy = false
                            when (probe) {
                                is JarvisAccounts.Probe.Found -> found = probe
                                is JarvisAccounts.Probe.Failed -> message = probe.message
                            }
                        }
                    },
                    enabled = !busy && address.isNotBlank(),
                    modifier = Modifier.testTag(JARVIS_FIND_TAG),
                ) { Text("Find Jarvis") }
            } else {
                TextButton(
                    onClick = {
                        busy = true
                        onPair(f.base, f.fingerprint, code) { problem ->
                            busy = false
                            if (problem == null) onClose() else message = problem
                        }
                    },
                    enabled = !busy && code.filter { it.isLetterOrDigit() }.length >= JarvisAccounts.CODE_LENGTH,
                    modifier = Modifier.testTag(JARVIS_PAIR_TAG),
                ) { Text("Pair") }
            }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Cancel") } },
    )
}
