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
 * move over, including a second step for a code ([SignInResult.NeedsCode]). A service that signs in on a web page (Alexa) opens it in Safari and takes the address it ends on.
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
        } else if (service.pageSignIn != null) {
            PageSignInBox(service.pageSignIn!!, busy) { busy = it }
        } else {
            Text("This service has no sign-in form.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Sign-in on a service's own web page (Alexa: Amazon's), the way the Android "Use my browser" path does it: the page opens in Safari, the owner signs in
 * there, and the page ends on an address that will not load (expected); the owner copies that address and pastes it here. Amazon's password goes to Amazon's
 * page, never through Edith.
 */
@Composable
private fun PageSignInBox(page: com.edith.app.home.PageSignIn, busy: Boolean, setBusy: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var started by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }

    Text("${page.siteName} signs in on its own page. Your password goes to ${page.siteName}, never through Edith.", style = MaterialTheme.typography.bodySmall)
    Button(enabled = !busy, onClick = {
        val start = page.begin()
        val url = platform.Foundation.NSURL.URLWithString(start.url)
        if (url == null) { note = "The sign-in address could not be opened." } else {
            platform.UIKit.UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
            started = true; note = null
        }
    }) { Text(if (started) "Open ${page.siteName}'s page again" else "Sign in with ${page.siteName}") }
    if (started) {
        Text("When the page ends on one that will not load, copy its address from Safari's address bar and paste it here.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("The address the page ended on") }, singleLine = true)
        Button(enabled = !busy && address.isNotBlank(), onClick = {
            scope.launch {
                setBusy(true); note = null
                when (val r = page.finish(address.trim(), "")) {
                    SignInResult.Done -> { address = ""; started = false }
                    is SignInResult.NeedsCode -> note = r.message
                    is SignInResult.Failed -> note = r.message
                }
                setBusy(false)
            }
        }) { Text("Finish") }
    }
    note?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
