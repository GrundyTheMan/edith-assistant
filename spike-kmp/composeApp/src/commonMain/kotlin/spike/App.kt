package spike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The spike's one screen: where the server is, what to ask, and the reply as it streams in. The same code runs on the desktop and the iPhone. */
@Composable
fun App(chat: OllamaChat = remember { OllamaChat(HttpClient()) }) {
    MaterialTheme {
        var server by remember { mutableStateOf(secretGet("server") ?: "http://192.168.0.2:11434") }
        var model by remember { mutableStateOf("llama3.2") }
        var prompt by remember { mutableStateOf("Say hello in one short sentence.") }
        var reply by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var saved by remember { mutableStateOf(false) }
        var memo by remember { mutableStateOf<String?>(null) }
        var recording by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val recordNow: () -> Unit = {
            recording = true
            memo = "Recording for 3 seconds…"
            scope.launch {
                memo = when (val r = recordMemo(3)) {
                    is RecordResult.Recorded -> "Recorded ${r.seconds} s: ${r.bytes} bytes."
                    is RecordResult.Failed -> "Recording failed: ${r.why}"
                }
                recording = false
            }
        }
        // The cloud build starts the app with the probe on, so its screenshot shows what the microphone code did.
        LaunchedEffect(Unit) { if (autoProbe()) recordNow() }

        Column(
            Modifier.fillMaxSize().safeContentPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Edith (shared-code spike)", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(server, { server = it; saved = false }, label = { Text("Ollama address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(prompt, { prompt = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
            Button(
                enabled = !busy && prompt.isNotBlank(),
                onClick = {
                    saved = secretPut("server", server)
                    reply = ""
                    error = null
                    busy = true
                    scope.launch {
                        try {
                            chat.stream(server, model, prompt).collect { reply += it }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "Couldn't reach the server."
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Answering…" else "Send") }
            Button(enabled = !recording, onClick = recordNow) { Text("Record 3 s") }
            memo?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (saved) Text("Address kept in the Keychain.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(reply, modifier = Modifier.verticalScroll(rememberScrollState()))
        }
    }
}
