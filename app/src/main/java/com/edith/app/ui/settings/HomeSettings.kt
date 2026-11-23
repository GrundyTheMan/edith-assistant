package com.edith.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import com.edith.app.alerts.AlertCatalog
import com.edith.app.alerts.AlertKind
import com.edith.app.alerts.AlertPrefs
import com.edith.app.alerts.MemoryAlertPrefs
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.edith.app.home.ServiceState
import com.edith.app.home.SignInForm
import com.edith.app.home.SignInResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edith.app.di.viewModelFactory
import com.edith.app.home.CommandSource
import com.edith.app.home.Home
import com.edith.app.home.LoggedCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.edith.app.core.time.formatPattern
import kotlinx.datetime.TimeZone
import kotlin.time.Instant
import java.util.Locale

/** One service in Settings → Home: its name, where it stands, and how it signs in (C25 §7, C26–C31). */
data class HomeServiceRow(val id: String, val name: String, val state: ServiceState, val form: SignInForm?, val removeHint: String? = null, val pageSite: String? = null)

/** One service's alert switches (C33): each kind it can tell apart, and whether it is on. */
data class AlertServiceRow(val id: String, val name: String, val kinds: List<Pair<AlertKind, Boolean>>)

/** What Settings → Home shows. */
data class HomeSettingsUi(
    val services: List<HomeServiceRow> = emptyList(),
    /** Debug builds: the Demo home switch exists. */
    val hasDemo: Boolean = false,
    val demo: Boolean = false,
    /** Hidden devices: (key, name). */
    val hidden: List<Pair<String, String>> = emptyList(),
    val log: List<LoggedCommand> = emptyList(),
    /** Camera alerts (C33): the services that can alert and are signed in, with their switches. */
    val alerts: List<AlertServiceRow> = emptyList(),
    /** What the paired Jarvis says about sharing the printer (C39): "Jarvis: sharing the printer (name)", "…not sharing…", "…not paired". */
    val printerJarvis: String = "",
)

/** Settings → Home (C25 §7). */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeSettingsViewModel(
    private val home: Home,
    log: kotlinx.coroutines.flow.Flow<List<LoggedCommand>>,
    private val alertPrefs: AlertPrefs = MemoryAlertPrefs(),
    private val printerJarvisLine: () -> String = { "" },
) : ViewModel() {
    private val services = home.services.flatMapLatest { list ->
        if (list.isEmpty()) flowOf(emptyList())
        else combine(list.map { it.state }) { states ->
            list.mapIndexed { i, s -> HomeServiceRow(s.backend.id, s.backend.displayName, states[i], s.form, s.removeHint, s.pageSignIn?.siteName) }
        }
    }

    val state: StateFlow<HomeSettingsUi> = combine(services, home.choices, log, alertPrefs.choices) { s, c, l, a ->
        HomeSettingsUi(
            services = s,
            hasDemo = home.hasDemo, demo = c.demo,
            hidden = c.hidden.map { it to (c.hiddenNames[it] ?: it.substringAfter(':')) }.sortedBy { it.second.lowercase() },
            log = l,
            printerJarvis = printerJarvisLine(),
            alerts = s.filter { it.state == ServiceState.Connected && it.id in AlertCatalog.services }
                .map { r -> AlertServiceRow(r.id, r.name, AlertCatalog.kindsOf(r.id).map { it to a.enabled(r.id, it) }) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeSettingsUi(hasDemo = home.hasDemo))

    fun setAlert(serviceId: String, kind: AlertKind, on: Boolean) {
        viewModelScope.launch { alertPrefs.set(serviceId, kind, on) }
    }

    fun setDemo(on: Boolean) {
        viewModelScope.launch { home.setDemo(on) }
    }

    fun show(key: String, name: String) {
        viewModelScope.launch { home.setHidden(key, name, false) }
    }

    /** Signs in to [serviceId] with what the owner typed; [done] gets the answer on the main thread. */
    fun signIn(serviceId: String, values: Map<String, String>, done: (SignInResult) -> Unit) {
        viewModelScope.launch {
            val service = home.services.value.firstOrNull { it.backend.id == serviceId }
            done(guarded { service?.signIn(values) ?: SignInResult.Failed("That service isn't available.") })
            home.refresh()   // the Home tab and Edith see the new devices at once
        }
    }

    /**
     * Runs a service's sign-in off the main thread, where Android refuses network reads (`NetworkOnMainThreadException` took the whole app
     * down on the owner's phone), and turns any surprise into a message instead of a crash. Only the exception's type is logged.
     */
    private suspend fun guarded(block: suspend () -> SignInResult): SignInResult = try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        com.edith.app.core.log.Log.w(com.edith.app.core.log.LogTag.Home, "A sign-in failed unexpectedly: ${e.javaClass.name} at ${e.stackTrace.firstOrNull()}")
        SignInResult.Failed("Edith hit an unexpected problem signing in (${e.javaClass.simpleName}).")
    }

    /** Starts a sign-in on the service's own page (Alexa); null when the service has none. */
    fun beginPage(serviceId: String): com.edith.app.home.PageSignInStart? =
        home.services.value.firstOrNull { it.backend.id == serviceId }?.pageSignIn?.begin()

    /** Finishes it with the address the page ended on and the web view's cookies (empty from a browser). */
    fun finishPage(serviceId: String, address: String, cookies: String, done: (SignInResult) -> Unit) {
        viewModelScope.launch {
            val page = home.services.value.firstOrNull { it.backend.id == serviceId }?.pageSignIn
            done(guarded { page?.finish(address, cookies) ?: SignInResult.Failed("That service isn't available.") })
            home.refresh()
        }
    }

    fun disconnect(serviceId: String) {
        viewModelScope.launch {
            home.services.value.firstOrNull { it.backend.id == serviceId }?.disconnect()
            home.refresh()
        }
    }

    companion object {
        /** The status line under the printer: what the paired Jarvis says about sharing it. */
        internal fun jarvisPrinterLine(connection: com.edith.app.jarvis.JarvisConnection?): String = when {
            connection == null -> "Jarvis: not paired"
            connection.printerInfo == null -> "Jarvis: not sharing the printer"
            else -> "Jarvis: sharing the printer (${connection.printerInfo?.name.orEmpty()})"
        }

        val Factory = viewModelFactory { c, _ -> HomeSettingsViewModel(c.home, c.homeCommandLog.recent, c.alertPrefs) { jarvisPrinterLine(c.accounts.jarvisConnection()) } }
    }
}

@Composable
fun HomeSection(vm: HomeSettingsViewModel = viewModel(factory = HomeSettingsViewModel.Factory)) {
    val ui by vm.state.collectAsStateWithLifecycle()
    HomeSettingsContent(ui, vm::setDemo, vm::show, vm::signIn, vm::disconnect, vm::beginPage, vm::finishPage, vm::setAlert)
}

@Composable
fun HomeSettingsContent(
    ui: HomeSettingsUi,
    onDemo: (Boolean) -> Unit,
    onShow: (String, String) -> Unit,
    onSignIn: (serviceId: String, values: Map<String, String>, done: (SignInResult) -> Unit) -> Unit = { _, _, _ -> },
    onDisconnect: (serviceId: String) -> Unit = {},
    onBeginPage: (serviceId: String) -> com.edith.app.home.PageSignInStart? = { null },
    onFinishPage: (serviceId: String, address: String, cookies: String, done: (SignInResult) -> Unit) -> Unit = { _, _, _, _ -> },
    onAlert: (serviceId: String, kind: AlertKind, on: Boolean) -> Unit = { _, _, _ -> },
) {
    // C33: alerts show as notifications, which Android 13 and later let through only after the owner allows them.
    val context = androidx.compose.ui.platform.LocalContext.current
    val requestNotifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
    fun askForNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    var showLog by remember { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf<HomeServiceRow?>(null) }
    var disconnecting by remember { mutableStateOf<HomeServiceRow?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Home", style = MaterialTheme.typography.titleMedium)
        Text(
            "Edith controls your lights, locks, thermostat and garage, and shows your cameras, from this phone. " +
                "Unlocking a door and opening the garage always need a tap on the screen.",
            style = MaterialTheme.typography.bodyMedium,
        )
        ui.services.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag(SERVICE_ROW_TAG + s.id)) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(
                        when (s.state) {
                            ServiceState.Connected -> "Connected"
                            ServiceState.NotConnected -> "Not connected"
                            ServiceState.NeedsSignIn -> "Needs signing in again"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (s.state == ServiceState.NeedsSignIn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (s.state) {
                    ServiceState.Connected -> TextButton(onClick = { disconnecting = s }, modifier = Modifier.testTag(DISCONNECT_TAG + s.id)) { Text("Disconnect") }
                    ServiceState.NotConnected -> if (s.form != null || s.pageSite != null) Button(onClick = { signingIn = s }, modifier = Modifier.testTag(SIGN_IN_TAG + s.id)) { Text("Sign in") }
                    ServiceState.NeedsSignIn -> Row {
                        if (s.form != null || s.pageSite != null) Button(onClick = { signingIn = s }, modifier = Modifier.testTag(SIGN_IN_TAG + s.id)) { Text("Sign in again") }
                        TextButton(onClick = { disconnecting = s }) { Text("Disconnect") }
                    }
                }
            }
        }
        if (ui.services.any { it.id == "printer" }) {
            Text(
                "To see and control the printer away from home, pair Edith with Jarvis (Settings → Accounts → Work) and, in Jarvis, switch on Let paired phones see the printer.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(ui.printerJarvis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("printer-jarvis-line"))
        }
        if (ui.alerts.isNotEmpty()) {
            Text("Alerts", style = MaterialTheme.typography.labelLarge)
            Text(
                "A notification when a doorbell rings or a camera sees something. All off until you switch them on. While any is on, " +
                    "Edith checks every 45 seconds, even with the app closed, and shows a small notification saying so. A doorbell ring comes with its picture.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ui.alerts.forEach { row ->
                Text(row.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                row.kinds.forEach { (kind, on) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(kind.label, modifier = Modifier.weight(1f))
                        Switch(
                            checked = on,
                            onCheckedChange = { turnOn -> if (turnOn) askForNotifications(); onAlert(row.id, kind, turnOn) },
                            modifier = Modifier.testTag(ALERT_TAG + row.id + "_" + kind.name),
                        )
                    }
                }
            }
        }
        if (ui.hasDemo) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Demo home", style = MaterialTheme.typography.bodyLarge)
                    Text("Debug builds only: a made-up house to try the Home tab and Edith's home tools.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = ui.demo, onCheckedChange = onDemo, modifier = Modifier.testTag(DEMO_TAG))
            }
        }
        if (ui.hidden.isNotEmpty()) {
            Text("Hidden devices", style = MaterialTheme.typography.labelLarge)
            ui.hidden.forEach { (key, name) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(name, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onShow(key, name) }) { Text("Show again") }
                }
            }
        }
        TextButton(onClick = { showLog = true }) { Text("Command log (${ui.log.size})") }
    }
    if (showLog) CommandLogDialog(ui.log) { showLog = false }
    signingIn?.let { s ->
        val form = s.form
        if (form != null) SignInDialog(form, { values, done -> onSignIn(s.id, values, done) }) { signingIn = null }
        else PageSignInDialog(s.name, s.pageSite.orEmpty(), { onBeginPage(s.id) }, { address, cookies, done -> onFinishPage(s.id, address, cookies, done) }) { signingIn = null }
    }
    disconnecting?.let { s ->
        AlertDialog(
            onDismissRequest = { disconnecting = null },
            title = { Text("Disconnect ${s.name}?") },
            text = { Text("Edith forgets the sign-in and stops showing ${s.name}'s devices." + (s.removeHint?.let { "\n\n$it" } ?: "")) },
            confirmButton = { TextButton(onClick = { onDisconnect(s.id); disconnecting = null }, modifier = Modifier.testTag(DISCONNECT_CONFIRM_TAG)) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { disconnecting = null }) { Text("Cancel") } },
        )
    }
}

/**
 * The sign-in form every home service shares (C26 §1): the boxes its [SignInForm] names, typed by the owner. The
 * password is masked (with a show toggle) and never shown again. A service that wants a 2FA code answers
 * [SignInResult.NeedsCode], and the form then shows its [SignInField.onlyWhenAsked] boxes.
 */
@Composable
private fun SignInDialog(form: SignInForm, onSignIn: (Map<String, String>, (SignInResult) -> Unit) -> Unit, onClose: () -> Unit) {
    var values by remember { mutableStateOf(mapOf<String, String>()) }
    var showSecret by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var codeAsked by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val fields = form.fields.filter { !it.onlyWhenAsked || codeAsked }
    AlertDialog(
        onDismissRequest = { if (!working) onClose() },
        title = { Text("Sign in to ${form.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(form.intro, style = MaterialTheme.typography.bodyMedium)
                fields.forEach { f ->
                    OutlinedTextField(
                        value = values[f.key].orEmpty(), onValueChange = { values = values + (f.key to it); message = null },
                        singleLine = true, enabled = !working, label = { Text(f.label) },
                        visualTransformation = if (f.secret && !showSecret) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = when { f.email -> KeyboardType.Email; f.secret -> KeyboardType.Password; else -> KeyboardType.Text },
                            autoCorrectEnabled = false,
                        ),
                        trailingIcon = if (f.secret) ({ TextButton(onClick = { showSecret = !showSecret }) { Text(if (showSecret) "Hide" else "Show") } }) else null,
                        modifier = Modifier.fillMaxWidth().testTag(FIELD_TAG + f.key),
                    )
                }
                if (working) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Signing in…")
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(MESSAGE_TAG)) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    working = true
                    onSignIn(values) { result ->
                        working = false
                        when (result) {
                            SignInResult.Done -> onClose()
                            is SignInResult.NeedsCode -> { codeAsked = true; message = result.message }
                            is SignInResult.Failed -> message = result.message
                        }
                    }
                },
                enabled = !working && fields.filter { !it.onlyWhenAsked && !it.optional }.all { !values[it.key].isNullOrBlank() },
                modifier = Modifier.testTag(SUBMIT_TAG),
            ) { Text("Sign in") }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !working) { Text("Cancel") } },
    )
}


/** The owner's audit view (C20 §5): every command Edith sent, newest first. */
@Composable
private fun CommandLogDialog(log: List<LoggedCommand>, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Command log") },
        text = {
            if (log.isEmpty()) Text("No commands yet.")
            else LazyColumn(Modifier.heightIn(max = 480.dp).testTag(LOG_TAG), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(log, key = { it.id }) { e ->
                    Column {
                        Text("${e.deviceName}: ${e.action.replace('_', ' ')}${e.value?.let { " $it" } ?: ""}", fontWeight = FontWeight.Medium)
                        Text(
                            "${formatPattern(Instant.fromEpochMilliseconds(e.at), TimeZone.currentSystemDefault(), "MMM d, h:mm:ss a", "en-US")} · " +
                                (if (e.source == CommandSource.HomeTab) "Home tab" else "Chat") + " · " +
                                (if (e.ok) e.result else "Failed: ${e.result}"),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (e.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

const val DEMO_TAG = "home-demo"
const val ALERT_TAG = "home-alert-"
const val LOG_TAG = "home-log"
const val SERVICE_ROW_TAG = "home-service-"
const val SIGN_IN_TAG = "home-signin-"
const val DISCONNECT_TAG = "home-disconnect-"
const val DISCONNECT_CONFIRM_TAG = "home-disconnect-confirm"
const val FIELD_TAG = "home-field-"
const val SUBMIT_TAG = "home-submit"
const val MESSAGE_TAG = "home-message"
