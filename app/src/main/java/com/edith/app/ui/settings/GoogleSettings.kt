package com.edith.app.ui.settings

import android.app.Activity
import android.app.PendingIntent
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.edith.app.di.viewModelFactory
import com.edith.app.google.GoogleAuth
import com.edith.app.google.GoogleState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Settings → Google account (C17 §3). */
class GoogleSettingsViewModel(private val google: GoogleAuth) : ViewModel() {
    val state: StateFlow<GoogleState> = google.state

    fun signIn(activity: Activity) {
        viewModelScope.launch { google.signIn(activity) }
    }

    fun onConsentResult(data: android.content.Intent?) {
        viewModelScope.launch { google.onConsentResult(data) }
    }

    /**
     * Grant access / Try again: shows Google's consent screen. If Edith has none in hand (a cancelled consent used it
     * up, or an error), she asks Google again first and shows the screen it returns, so one tap is enough.
     */
    fun grant(show: (PendingIntent) -> Unit) {
        viewModelScope.launch {
            val intent = google.consentIntent ?: run { google.start(); google.consentIntent }
            intent?.let(show)
        }
    }

    fun disconnect() {
        viewModelScope.launch { google.disconnect() }
    }

    companion object {
        val Factory = viewModelFactory { c, _ -> GoogleSettingsViewModel(c.googleAuth) }
    }
}

@Composable
fun GoogleSection(vm: GoogleSettingsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.onConsentResult(result.data)
    }
    GoogleContent(
        state = state,
        onSignIn = { activity?.let(vm::signIn) },
        onGrant = { vm.grant { consent.launch(IntentSenderRequest.Builder(it.intentSender).build()) } },
        onDisconnect = vm::disconnect,
    )
}

@Composable
fun GoogleContent(state: GoogleState, onSignIn: () -> Unit, onGrant: () -> Unit, onDisconnect: () -> Unit) {
    var confirmDisconnect by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Google account", style = MaterialTheme.typography.titleMedium)
        Text(
            "With access, Edith can read and add calendar events, and read your email and write drafts. She never sends email.",
            style = MaterialTheme.typography.bodyMedium,
        )
        val status = when (state) {
            GoogleState.NotSetUp -> "Not set up in this build: the Google project's Web client ID is missing (docs/google-setup.md)."
            GoogleState.SignedOut -> "Not connected"
            is GoogleState.Checking -> "Checking ${state.email}…"
            is GoogleState.Connected -> "Connected as ${state.email}"
            is GoogleState.NeedsConsent -> "${state.email} needs your permission"
            is GoogleState.Error -> state.message
        }
        Text(
            status,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state is GoogleState.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag(GOOGLE_STATUS_TAG),
        )
        when (state) {
            GoogleState.NotSetUp -> Unit
            is GoogleState.Checking -> CircularProgressIndicator()
            GoogleState.SignedOut -> Button(onClick = onSignIn) { Text("Sign in with Google") }
            is GoogleState.Connected -> OutlinedButton(onClick = { confirmDisconnect = true }) { Text("Disconnect") }
            is GoogleState.NeedsConsent -> {
                Button(onClick = onGrant) { Text("Grant access") }
                TextButton(onClick = { confirmDisconnect = true }) { Text("Disconnect") }
            }
            is GoogleState.Error -> if (state.email == null) {
                Button(onClick = onSignIn) { Text("Sign in with Google") }
            } else {
                Button(onClick = onGrant) { Text("Try again") }
                TextButton(onClick = { confirmDisconnect = true }) { Text("Disconnect") }
            }
        }
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect Google?") },
            text = { Text("Edith loses access to your calendar and email until you sign in again.") },
            confirmButton = { TextButton(onClick = { confirmDisconnect = false; onDisconnect() }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
        )
    }
}

const val GOOGLE_STATUS_TAG = "google_status"
