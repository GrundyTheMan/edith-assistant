package com.edith.app.ui.settings

import android.app.Activity
import android.app.PendingIntent
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Settings → Google account (C17 §3, C17b §3). Signing in runs in [appScope] (on the main thread), not the
 * ViewModel's scope: a browser sign-in waits minutes for the owner and must survive leaving this screen.
 */
class GoogleSettingsViewModel(private val google: GoogleAuth, private val appScope: CoroutineScope) : ViewModel() {
    val state: StateFlow<GoogleState> = google.state

    /** True while Edith waits for the browser (the "Finish signing in" dialog). */
    val waitingForBrowser: StateFlow<Boolean> = google.browserWaiting

    /** Whether **Use a browser instead** can be offered (this build has the Desktop client ID). */
    val offerBrowser: Boolean = google.browserAvailable

    fun signIn(activity: Activity) {
        appScope.launch(Dispatchers.Main.immediate) { google.signIn(activity) }
    }

    fun signInWithBrowser(activity: Activity) {
        appScope.launch(Dispatchers.Main.immediate) { google.signInWithBrowser(activity) }
    }

    fun paste(address: String) = google.pasteBrowserAddress(address)

    fun cancel() = google.cancelBrowserSignIn()

    fun onConsentResult(data: android.content.Intent?) {
        viewModelScope.launch { google.onConsentResult(data) }
    }

    /** Grant access / Try again: Google's consent screen, or the browser again (C17b), in one tap. */
    fun grant(activity: Activity, show: (PendingIntent) -> Unit) {
        appScope.launch(Dispatchers.Main.immediate) { google.grant(activity, show) }
    }

    fun disconnect() {
        viewModelScope.launch { google.disconnect() }
    }

    companion object {
        val Factory = viewModelFactory { c, _ -> GoogleSettingsViewModel(c.googleAuth, c.appScope) }
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
        onGrant = { activity?.let { a -> vm.grant(a) { consent.launch(IntentSenderRequest.Builder(it.intentSender).build()) } } },
        onDisconnect = vm::disconnect,
        offerBrowser = vm.offerBrowser,
        onUseBrowser = { activity?.let(vm::signInWithBrowser) },
    )
}

/**
 * C17b §3: the "Finish signing in" dialog, over the whole app (MainActivity hosts it), not only in Settings. After
 * Edith was killed while the owner was in the browser she reopens on Chat, and the owner must still see where to
 * paste the address.
 */
@Composable
fun BrowserSignInDialogHost(vm: GoogleSettingsViewModel) {
    val waiting by vm.waitingForBrowser.collectAsStateWithLifecycle()
    if (waiting) BrowserWaitingDialog(onPaste = vm::paste, onCancel = vm::cancel)
}

@Composable
fun GoogleContent(
    state: GoogleState,
    onSignIn: () -> Unit,
    onGrant: () -> Unit,
    onDisconnect: () -> Unit,
    offerBrowser: Boolean = false,
    onUseBrowser: () -> Unit = {},
) {
    var confirmDisconnect by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Google account", style = MaterialTheme.typography.titleMedium)
        Text(
            "With access, Edith can read and change your calendar, and read, draft and send email. Every change and every email she sends is shown to you first, and only goes ahead when you confirm.",
            style = MaterialTheme.typography.bodyMedium,
        )
        val status = when (state) {
            GoogleState.NotSetUp -> "Not set up in this build: the Google project's Web and Desktop client IDs are missing (docs/google-setup.md)."
            GoogleState.SignedOut -> "Not connected"
            is GoogleState.Checking -> "Checking ${state.email}…"
            is GoogleState.Connected -> "Connected as ${state.email}"
            is GoogleState.NeedsConsent -> if (state.viaBrowser) {
                "Google needs you to sign in again (about once a week while the Google project is in Testing)"
            } else {
                "${state.email} needs your permission"
            }
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
        if (offerBrowser && (state == GoogleState.SignedOut || state is GoogleState.Error)) {
            TextButton(onClick = onUseBrowser) { Text("Use a browser instead") }
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

/** C17b §3: shown while Edith waits for the browser; closes by itself when the loopback gets the code. */
@Composable
fun BrowserWaitingDialog(onPaste: (String) -> Unit, onCancel: () -> Unit) {
    var address by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},   // only Cancel stops waiting
        title = { Text("Finish signing in in your browser") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Sign in and allow access. You'll come back here automatically. If the browser ends on a page that " +
                        "won't load, copy its address (it starts with http://127.0.0.1) and paste it here.",
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Paste the address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(GOOGLE_PASTE_TAG),
                )
                CircularProgressIndicator()
            }
        },
        confirmButton = { TextButton(onClick = { onPaste(address) }, enabled = address.isNotBlank()) { Text("Use this address") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

const val GOOGLE_STATUS_TAG = "google_status"
const val GOOGLE_PASTE_TAG = "google_paste_address"
