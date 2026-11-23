package spike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil3.compose.AsyncImage
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource
import kotlinx.coroutines.launch

/** K0.5: a ViewModel that outlives screens; the probes write their results here and the home screen shows them. */
class ProbeViewModel : ViewModel() {
    val lines = mutableStateListOf<String>()
    var visits by mutableStateOf(0)
}

/** The spike: a home screen with the chat, the microphone probe and the storage probes, and a second screen to prove navigation. */
@Composable
fun App(chat: OllamaChat = remember { OllamaChat(HttpClient()) }) {
    // Without a Surface the text stays black even when the phone is in dark mode, and the screen is black: black on black.
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val nav = rememberNavController()
        val probes = viewModel { ProbeViewModel() }
        // The cloud build starts the app with the probes on, so its screenshot shows what each piece did with no one tapping.
        LaunchedEffect(Unit) {
            if (!autoProbe()) return@LaunchedEffect
            probes.lines += when (val r = recordMemo(3)) {
                is RecordResult.Recorded -> "Microphone: recorded ${r.seconds} s, ${r.bytes} bytes."
                is RecordResult.Failed -> "Microphone failed: ${r.why}"
            }
            probes.lines += try { roomProbe() } catch (e: Exception) { "Room failed: ${e.message}" }
            probes.lines += try { dataStoreProbe() } catch (e: Exception) { "DataStore failed: ${e.message}" }
            probes.lines += mqttProbe()
            chatProbeUrl()?.let { probes.lines += chatProbe(chat, it) }
            nav.navigate("second")
            delay(600)
            nav.popBackStack()
            probes.lines += "Navigation and ViewModel: ${probes.visits} visit(s) to the second screen, back on the first."
        }
        // Each probe line is also printed, so the cloud build can read the results as text.
        LaunchedEffect(probes.lines.size) { probes.lines.lastOrNull()?.let { println("PROBE: $it") } }
        NavHost(nav, startDestination = "home") {
            composable("home") { Home(chat, nav, probes) }
            composable("second") { Second(nav, probes) }
        }
        }
    }
}

/** Streams one reply from the cloud build's fake server and says what arrived, piece by piece, so a hang in the reader shows up as text. */
private suspend fun chatProbe(chat: OllamaChat, url: String): String {
    val started = TimeSource.Monotonic.markNow()
    val got = StringBuilder()
    return try {
        val finished = withTimeoutOrNull(20_000) {
            chat.stream(url, "fake", "hi").collect {
                got.append(it)
                println("CHAT piece after ${started.elapsedNow()}: $it")
            }
            true
        }
        if (finished == true) "Chat: streamed \"$got\" in ${started.elapsedNow()}" else "Chat: HUNG, no end after 20 s (got so far: \"$got\")"
    } catch (e: Exception) {
        "Chat failed: ${e::class.simpleName}: ${e.message}"
    }
}

@Composable
private fun Home(chat: OllamaChat, nav: NavController, probes: ProbeViewModel) {
    var server by remember { mutableStateOf(secretGet("server") ?: "http://192.168.0.2:11434") }
    var model by remember { mutableStateOf("llama3.2") }
    var prompt by remember { mutableStateOf("Say hello in one short sentence.") }
    var reply by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().safeContentPadding().padding(16.dp).verticalScroll(rememberScrollState()),
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
                        if (reply.isEmpty()) error = "The model finished without sending any text."
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
        Button(
            enabled = !recording,
            onClick = {
                recording = true
                scope.launch {
                    probes.lines += when (val r = recordMemo(3)) {
                        is RecordResult.Recorded -> "Microphone: recorded ${r.seconds} s, ${r.bytes} bytes."
                        is RecordResult.Failed -> "Microphone failed: ${r.why}"
                    }
                    recording = false
                }
            },
        ) { Text("Record 3 s") }
        Button(onClick = { nav.navigate("second") }) { Text("Second screen") }
        if (saved) Text("Address kept in the Keychain.", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        probes.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(reply)
    }
}

@Composable
private fun Second(nav: NavController, probes: ProbeViewModel) {
    LaunchedEffect(Unit) { probes.visits += 1 }
    Column(Modifier.fillMaxSize().safeContentPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Second screen", style = MaterialTheme.typography.titleLarge)
        Icon(Icons.Filled.Check, contentDescription = "A Material icon from the multiplatform icon set", modifier = Modifier.size(32.dp))
        // Coil: an image loaded over the network through Ktor (needs the internet; shown only so the library is linked and used).
        AsyncImage(model = "https://example.com/none.png", contentDescription = "An image loaded by Coil", modifier = Modifier.size(48.dp))
        Text("Visits so far: ${probes.visits}")
        Button(onClick = { nav.popBackStack() }) { Text("Back") }
    }
}
