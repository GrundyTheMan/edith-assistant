package com.edith.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.edith.app.home.Home
import com.edith.app.home.HomeService
import com.edith.app.home.ServiceState
import com.edith.app.home.SignInResult
import kotlinx.coroutines.launch

/**
 * The smart-home services on the iPhone's More tab (K4.9): each registered service shows its state and, from its own [HomeService.form], the boxes to
 * connect it (the printer's address, and an API key if it has one); connected, it can be disconnected. The same form drives every service as they
 * move over, including a second step for a code ([SignInResult.NeedsCode]). Services that sign in on a web page (Alexa) are not offered here yet.
 */
@Composable
fun HomeServicesSection(home: Home) {
    val services by home.services.collectAsState()
    Text("Smart home", style = MaterialTheme.typography.titleLarge)
    if (services.isEmpty()) Text("No home service is available yet.")
    services.forEach { ServiceCard(it) }
}

@Composable
private fun ServiceCard(service: HomeService) {
    val scope = rememberCoroutineScope()
    val state by service.state.collectAsState()
    val form = service.form
    val values = remember { mutableStateMapOf<String, String>() }
    var asked by remember { mutableStateOf<String?>(null) }   // a service asked for a code: its message
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(form?.title ?: service.backend.displayName, style = MaterialTheme.typography.titleMedium)
        Text(
            when (state) {
                ServiceState.Connected -> "Connected"
                ServiceState.NeedsSignIn -> "Needs signing in again"
                ServiceState.NotConnected -> "Not connected"
            },
        )
        if (state == ServiceState.Connected) {
            OutlinedButton(enabled = !busy, onClick = { scope.launch { busy = true; service.disconnect(); busy = false } }) { Text("Disconnect") }
            service.removeHint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        } else if (form != null) {
            Text(form.intro, style = MaterialTheme.typography.bodySmall)
            form.fields.filter { !it.onlyWhenAsked || asked != null }.forEach { field ->
                OutlinedTextField(
                    values[field.key].orEmpty(), { values[field.key] = it }, Modifier.fillMaxWidth(),
                    label = { Text(field.label) }, singleLine = true,
                    visualTransformation = if (field.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                )
            }
            asked?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Button(
                enabled = !busy && form.fields.filter { !it.optional && (!it.onlyWhenAsked || asked != null) }.all { values[it.key].orEmpty().isNotBlank() },
                onClick = {
                    scope.launch {
                        busy = true; note = null
                        when (val r = service.signIn(values.toMap())) {
                            SignInResult.Done -> { values.clear(); asked = null }
                            is SignInResult.NeedsCode -> asked = r.message
                            is SignInResult.Failed -> note = r.message
                        }
                        busy = false
                    }
                },
            ) { Text(if (asked != null) "Send code" else "Connect") }
            note?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } else {
            Text("This service signs in on a web page, which the iPhone does not do yet.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
