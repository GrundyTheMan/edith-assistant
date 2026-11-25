package com.edith.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.edith.app.accounts.AccountState
import com.edith.app.accounts.IosAccounts
import com.edith.app.accounts.PlatformActivity
import com.edith.app.jarvis.JarvisAccounts
import kotlinx.coroutines.launch

/**
 * The iPhone's accounts page (K4.6): the connected accounts and what state each is in, adding Google through the system's sign-in sheet, and pairing the
 * work account with Jarvis on the PC (find the PC, compare the fingerprint with the one Jarvis shows, type its code). Plain on purpose: it is
 * how the sign-in seams get used before a designed Settings exists.
 */
@Composable
fun AccountsScreen(accounts: IosAccounts) {
    val scope = rememberCoroutineScope()
    val list by accounts.accounts.collectAsState()
    val message by accounts.message.collectAsState()
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Accounts", style = MaterialTheme.typography.headlineSmall)
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (list.isEmpty()) Text("No account is connected yet.")
        list.forEach { (account, state) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(account.label, style = MaterialTheme.typography.titleMedium)
                Text("${account.provider.displayName}: ${account.address}", style = MaterialTheme.typography.bodySmall)
                Text(describe(state), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state is AccountState.NeedsSignIn || state is AccountState.Error) {
                        Button(enabled = !busy, onClick = { scope.launch { busy = true; accounts.grant(account.id, PlatformActivity()) {}; busy = false } }) { Text("Sign in again") }
                    }
                    OutlinedButton(enabled = !busy, onClick = { scope.launch { busy = true; accounts.disconnect(account.id); busy = false } }) { Text("Disconnect") }
                }
            }
            HorizontalDivider()
        }

        val google = accounts.google
        Button(enabled = !busy && google?.isConfigured == true, onClick = { scope.launch { busy = true; accounts.addGoogle(); busy = false } }) { Text("Add a Google account") }
        if (google?.isConfigured != true) Text("Google isn't set up in this build (the iPhone client ID is missing).", style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        WorkPairing(accounts, busy) { busy = it }
    }
}

@Composable
private fun WorkPairing(accounts: IosAccounts, busy: Boolean, setBusy: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<JarvisAccounts.Probe.Found?>(null) }
    var code by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }

    Text("Work account (through Jarvis on your PC)", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("PC address") }, singleLine = true)
    Button(enabled = !busy && address.isNotBlank(), onClick = {
        scope.launch {
            setBusy(true); note = null; found = null
            when (val r = accounts.probeJarvis(address.trim())) {
                is JarvisAccounts.Probe.Found -> found = r
                is JarvisAccounts.Probe.Failed -> note = r.message
            }
            setBusy(false)
        }
    }) { Text("Find my PC") }

    found?.let { f ->
        Text("Jarvis should show this fingerprint: ${f.groups}")
        OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text("Pairing code from Jarvis") }, singleLine = true)
        Button(enabled = !busy && code.isNotBlank(), onClick = {
            scope.launch {
                setBusy(true)
                note = accounts.addJarvis(f.base, f.fingerprint, code.trim(), "iPhone")
                if (note == null) { found = null; code = ""; address = "" }
                setBusy(false)
            }
        }) { Text("Pair") }
    }
    note?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

private fun describe(state: AccountState): String = when (state) {
    AccountState.Checking -> "Checking the connection…"
    AccountState.Connected -> "Connected"
    is AccountState.NeedsSignIn -> state.reason
    is AccountState.Error -> state.message
}
