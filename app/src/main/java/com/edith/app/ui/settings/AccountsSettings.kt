package com.edith.app.ui.settings

import android.app.Activity
import android.app.PendingIntent
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.edith.app.accounts.Account
import com.edith.app.accounts.AccountState
import com.edith.app.accounts.Accounts
import com.edith.app.accounts.Provider
import com.edith.app.accounts.sources.CalendarInfo
import com.edith.app.accounts.tasks.TasksAccess
import com.edith.app.di.viewModelFactory
import com.edith.app.google.GoogleAccounts
import com.edith.app.icloud.ICloudAccounts
import com.edith.app.microsoft.MicrosoftAccounts
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Settings → Accounts (C22 §5; replaces C17's Settings → Google account). Signing in runs in [appScope] (on the main
 * thread), not the ViewModel's scope: a browser sign-in waits minutes for the owner and must survive leaving this
 * screen (C17b).
 */
class AccountsViewModel(private val accounts: Accounts, private val appScope: CoroutineScope) : ViewModel() {
    val list: StateFlow<List<Pair<Account, AccountState>>> = accounts.accounts
    val message: StateFlow<String?> = accounts.message

    /** True while Edith waits for the browser (the "Finish signing in" dialog). */
    val waitingForBrowser: StateFlow<Boolean> = accounts.browserWaiting

    /** What Add account offers for Google: null when it can be added, else why not. */
    val googleNotSetUp: String? = GoogleAccounts.NOT_SET_UP.takeIf { accounts.google.notSetUp }

    /** What Add account offers for Microsoft (C23): null when it can be added, else why not. */
    val microsoftNotSetUp: String? = MicrosoftAccounts.NOT_SET_UP.takeIf { accounts.microsoft?.isConfigured != true }

    /** The provider the browser sign-in is for, for the waiting dialog. */
    val waitingFor: StateFlow<String?> = accounts.browserWaitingFor

    /** Whether **Use a browser instead** can be offered (this build has the Desktop client ID). */
    val offerBrowser: Boolean = accounts.google.browserAvailable

    fun addGoogle(activity: Activity, useBrowser: Boolean) {
        appScope.launch(Dispatchers.Main.immediate) { accounts.addGoogle(activity, useBrowser) }
    }

    /** Add account → iCloud, or Sign in again with a new password (C24 §1). [done] gets the check's answer. */
    fun addICloud(address: String, password: String, done: (ICloudAnswer) -> Unit) {
        viewModelScope.launch {
            val message = accounts.addICloud(address, password)
            done(ICloudAnswer(added = message == null || message == ICloudAccounts.CALENDAR_UNAVAILABLE, message = message))
        }
    }

    fun addMicrosoft(activity: Activity) {
        appScope.launch(Dispatchers.Main.immediate) { accounts.addMicrosoft(activity) }
    }

    fun grant(id: String, activity: Activity, show: (PendingIntent) -> Unit) {
        appScope.launch(Dispatchers.Main.immediate) { accounts.grant(id, activity, show) }
    }

    /** C35 §2: the accounts whose to-dos need permission. */
    val tasksAccess: StateFlow<Map<String, TasksAccess>> = accounts.tasksAccess

    /** **To-dos: Grant access** (C35 §2). */
    fun grantTasks(id: String, activity: Activity, show: (PendingIntent) -> Unit) {
        appScope.launch(Dispatchers.Main.immediate) { accounts.grantTasks(id, activity, show) }
    }

    fun onConsentResult(data: android.content.Intent?) {
        viewModelScope.launch { accounts.onConsentResult(data) }
    }

    /** The account's writable calendars, the current default marked, for the Default calendar choice. */
    fun calendarsOf(id: String, done: (List<CalendarInfo>) -> Unit) {
        viewModelScope.launch { done(accounts.writableCalendars(id)) }
    }

    fun setDefaultCalendar(id: String, calendarId: String) {
        viewModelScope.launch { accounts.setDefaultCalendar(id, calendarId) }
    }

    fun rename(id: String, label: String, onResult: (Accounts.RenameResult) -> Unit) {
        viewModelScope.launch { onResult(accounts.rename(id, label)) }
    }

    fun disconnect(id: String) {
        appScope.launch { accounts.disconnect(id) }
    }

    fun paste(address: String) = accounts.pasteBrowserAddress(address)

    fun cancel() = accounts.cancelBrowserSignIn()

    companion object {
        val Factory = viewModelFactory { c, _ -> AccountsViewModel(c.accounts, c.appScope) }
    }
}

@Composable
fun AccountsSection(vm: AccountsViewModel) {
    val list by vm.list.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val tasksAccess by vm.tasksAccess.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.onConsentResult(result.data)
    }
    AccountsContent(
        accounts = list,
        message = message,
        googleNotSetUp = vm.googleNotSetUp,
        offerBrowser = vm.offerBrowser,
        onAddGoogle = { browser -> activity?.let { vm.addGoogle(it, browser) } },
        microsoftNotSetUp = vm.microsoftNotSetUp,
        onAddMicrosoft = { activity?.let { vm.addMicrosoft(it) } },
        onAddICloud = vm::addICloud,
        onGrant = { id -> activity?.let { a -> vm.grant(id, a) { consent.launch(IntentSenderRequest.Builder(it.intentSender).build()) } } },
        tasksNeedPermission = tasksAccess.filterValues { it == TasksAccess.NeedsPermission }.keys,
        onGrantTasks = { id -> activity?.let { a -> vm.grantTasks(id, a) { consent.launch(IntentSenderRequest.Builder(it.intentSender).build()) } } },
        onRename = vm::rename,
        loadCalendars = vm::calendarsOf,
        onSetDefaultCalendar = vm::setDefaultCalendar,
        onDisconnect = vm::disconnect,
    )
}

/**
 * C17b §3: the "Finish signing in" dialog, over the whole app (MainActivity hosts it), not only in Settings. After
 * Edith was killed while the owner was in the browser she reopens on Chat, and the owner must still see where to
 * paste the address.
 */
@Composable
fun BrowserSignInDialogHost(vm: AccountsViewModel) {
    val waiting by vm.waitingForBrowser.collectAsStateWithLifecycle()
    val waitingFor by vm.waitingFor.collectAsStateWithLifecycle()
    val provider = if (waitingFor == "microsoft") Provider.Microsoft else Provider.Google
    if (waiting) BrowserWaitingDialog(provider = provider, onPaste = vm::paste, onCancel = vm::cancel)
}

/** The words for an account's state in Settings. */
fun stateText(s: AccountState): String = when (s) {
    AccountState.Checking -> "Checking…"
    AccountState.Connected -> "Connected"
    is AccountState.NeedsSignIn -> s.reason.replaceFirstChar { it.uppercase() }
    is AccountState.Error -> s.message
}

/** Stateless Settings → Accounts, so it can be tested without a ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsContent(
    accounts: List<Pair<Account, AccountState>>,
    message: String?,
    googleNotSetUp: String?,
    offerBrowser: Boolean,
    onAddGoogle: (useBrowser: Boolean) -> Unit,
    onGrant: (id: String) -> Unit,
    onRename: (id: String, label: String, onResult: (Accounts.RenameResult) -> Unit) -> Unit,
    loadCalendars: (id: String, done: (List<CalendarInfo>) -> Unit) -> Unit = { _, _ -> },
    onSetDefaultCalendar: (id: String, calendarId: String) -> Unit = { _, _ -> },
    onDisconnect: (id: String) -> Unit,
    microsoftNotSetUp: String? = MicrosoftAccounts.NOT_SET_UP,
    onAddMicrosoft: () -> Unit = {},
    onAddICloud: (address: String, password: String, done: (ICloudAnswer) -> Unit) -> Unit = { _, _, _ -> },
    /** C35 §2: accounts whose to-dos need permission, each with **To-dos: Grant access** under it. */
    tasksNeedPermission: Set<String> = emptySet(),
    onGrantTasks: (id: String) -> Unit = {},
) {
    var open by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    // C24: the iCloud dialog, for a new account ("") or a given address (Sign in again).
    var icloudFor by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Accounts", style = MaterialTheme.typography.titleMedium)
        Text(
            "Edith reads the calendars and email of every account here, and can add events and write email. Every change and every email she sends is shown to you first, and only goes ahead when you confirm.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (accounts.isEmpty()) Text("No accounts yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(ACCOUNTS_EMPTY_TAG))
        for ((account, state) in accounts) {
            AccountRow(account, state) { open = account.id }
            if (account.id in tasksNeedPermission && state == AccountState.Connected) {
                TextButton(onClick = { onGrantTasks(account.id) }, modifier = Modifier.padding(start = 40.dp).testTag(TASKS_GRANT_TAG + account.id)) {
                    Text("To-dos: Grant access")
                }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(ACCOUNTS_MESSAGE_TAG)) }
        Button(onClick = { adding = true }) { Text("Add account") }
    }

    accounts.firstOrNull { it.first.id == open }?.let { (account, state) ->
        AccountDialog(
            account, state,
            onGrant = {
                open = null
                if (account.provider == Provider.ICloud && state is AccountState.NeedsSignIn) icloudFor = account.address else onGrant(account.id)
            },
            onRename = { label, done -> onRename(account.id, label, done) },
            loadCalendars = { done -> loadCalendars(account.id, done) },
            onSetDefaultCalendar = { calendarId -> onSetDefaultCalendar(account.id, calendarId) },
            onDisconnect = { open = null; onDisconnect(account.id) },
            onClose = { open = null },
        )
    }

    if (adding) {
        ModalBottomSheet(onDismissRequest = { adding = false }) {
            Column(Modifier.padding(bottom = 16.dp).navigationBarsPadding()) {
                Text("Add account", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                ListItem(
                    headlineContent = { Text("Google") },
                    supportingContent = { Text(googleNotSetUp ?: "Gmail and Google Calendar") },
                    leadingContent = { ProviderBadge(Provider.Google) },
                    modifier = Modifier.testTag(ADD_GOOGLE_TAG).let { m ->
                        if (googleNotSetUp == null) m.clickable { adding = false; onAddGoogle(false) } else m
                    },
                )
                if (offerBrowser && googleNotSetUp == null) {
                    TextButton(onClick = { adding = false; onAddGoogle(true) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("Google, using a browser instead")
                    }
                }
                ListItem(
                    headlineContent = { Text("Microsoft") },
                    supportingContent = { Text(microsoftNotSetUp ?: "Outlook.com, Hotmail and Microsoft 365") },
                    leadingContent = { ProviderBadge(Provider.Microsoft) },
                    modifier = Modifier.testTag(ADD_MICROSOFT_TAG).let { m ->
                        if (microsoftNotSetUp == null) m.clickable { adding = false; onAddMicrosoft() } else m
                    },
                )
                ListItem(
                    headlineContent = { Text("iCloud") },
                    supportingContent = { Text("iCloud Mail and Calendar, with an app-specific password") },
                    leadingContent = { ProviderBadge(Provider.ICloud) },
                    modifier = Modifier.testTag(ADD_ICLOUD_TAG).clickable { adding = false; icloudFor = "" },
                )
            }
        }
    }

    icloudFor?.let { address ->
        ICloudDialog(fixedAddress = address.ifEmpty { null }, onAdd = onAddICloud, onClose = { icloudFor = null })
    }
}

/** What the iCloud dialog's check answered (C24 §1): [added], and a message to show (the reason, or "mail only"). */
data class ICloudAnswer(val added: Boolean, val message: String?)

/**
 * C24 §1: the iCloud address and an app-specific password, which the owner types or pastes here themselves. The
 * password is masked (with a show toggle), checked before anything is saved, and never shown again. [fixedAddress]:
 * Sign in again for that account, with a new password.
 */
@Composable
private fun ICloudDialog(
    fixedAddress: String?,
    onAdd: (address: String, password: String, done: (ICloudAnswer) -> Unit) -> Unit,
    onClose: () -> Unit,
) {
    var address by remember { mutableStateOf(fixedAddress.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var addedWithNote by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!checking) onClose() },
        title = { Text(if (fixedAddress == null) "Add iCloud" else "Sign in to iCloud again") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!addedWithNote) {
                    Text(
                        "Make an app-specific password at account.apple.com → Sign-In and Security → App-Specific Passwords, " +
                            "and paste it here. Not your Apple Account password. It lets Edith use this account's mail and calendars.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = address, onValueChange = { address = it; message = null }, singleLine = true,
                        enabled = fixedAddress == null && !checking, label = { Text("iCloud email") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth().testTag(ICLOUD_ADDRESS_TAG),
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it; message = null }, singleLine = true,
                        enabled = !checking, label = { Text("App-specific password") }, placeholder = { Text("xxxx-xxxx-xxxx-xxxx") },
                        visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false,
                        ),
                        trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") } },
                        modifier = Modifier.fillMaxWidth().testTag(ICLOUD_PASSWORD_TAG),
                    )
                    if (checking) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Checking with iCloud…")
                    }
                }
                message?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(ICLOUD_MESSAGE_TAG),
                        color = if (addedWithNote) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (addedWithNote) {
                TextButton(onClick = onClose) { Text("OK") }
            } else {
                TextButton(
                    onClick = {
                        checking = true
                        onAdd(address, password) { answer ->
                            checking = false
                            when {
                                answer.added && answer.message == null -> onClose()
                                answer.added -> { addedWithNote = true; message = answer.message }
                                else -> message = answer.message
                            }
                        }
                    },
                    enabled = !checking && address.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.testTag(ICLOUD_ADD_TAG),
                ) { Text(if (fixedAddress == null) "Add" else "Sign in") }
            }
        },
        dismissButton = { if (!addedWithNote) TextButton(onClick = onClose, enabled = !checking) { Text("Cancel") } },
    )
}

@Composable
private fun AccountRow(account: Account, state: AccountState, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp).testTag(ACCOUNT_ROW_TAG + account.id),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProviderBadge(account.provider)
        Column(Modifier.weight(1f)) {
            Text(account.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (!account.label.equals(account.address, ignoreCase = true)) Text(account.address, style = MaterialTheme.typography.bodySmall)
            Text(
                stateText(state),
                style = MaterialTheme.typography.bodySmall,
                color = if (state is AccountState.Connected || state == AccountState.Checking) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        if (state == AccountState.Checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

/** The provider's initial in a circle ("G"): enough to tell accounts apart without a brand logo. */
@Composable
private fun ProviderBadge(provider: Provider) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(36.dp).semantics { contentDescription = provider.displayName }) {
        Box(contentAlignment = Alignment.Center) { Text(provider.displayName.take(1), fontWeight = FontWeight.Bold) }
    }
}

/** Tapping an account: Rename, Grant access / Sign in again / Try again (when needed), Disconnect (confirmed). */
@Composable
private fun AccountDialog(
    account: Account,
    state: AccountState,
    onGrant: () -> Unit,
    onRename: (String, (Accounts.RenameResult) -> Unit) -> Unit,
    loadCalendars: ((List<CalendarInfo>) -> Unit) -> Unit = {},
    onSetDefaultCalendar: (String) -> Unit = {},
    onDisconnect: () -> Unit,
    onClose: () -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf(account.label) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(account.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${account.provider.displayName} · ${account.address}", style = MaterialTheme.typography.bodyMedium)
                Text(stateText(state), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(ACCOUNT_STATUS_TAG))
                if (renaming) {
                    OutlinedTextField(
                        value = label, onValueChange = { label = it; renameError = null }, singleLine = true,
                        label = { Text("Name") }, isError = renameError != null,
                        supportingText = { Text(renameError ?: "What you call it, e.g. work or personal") },
                        modifier = Modifier.fillMaxWidth().testTag(ACCOUNT_LABEL_TAG),
                    )
                    Button(onClick = {
                        onRename(label) { r ->
                            when (r) {
                                Accounts.RenameResult.Done -> renaming = false
                                Accounts.RenameResult.Blank -> renameError = "Give it a name."
                                Accounts.RenameResult.Taken -> renameError = "Another account already has that name."
                            }
                        }
                    }) { Text("Save name") }
                } else {
                    OutlinedButton(onClick = { renaming = true }) { Text("Rename") }
                }
                DefaultCalendarChoice(state is AccountState.Connected, loadCalendars, onSetDefaultCalendar)
                when (state) {
                    is AccountState.NeedsSignIn -> Button(onClick = onGrant) { Text(if (state.viaBrowser || account.provider == Provider.ICloud) "Sign in again" else "Grant access") }
                    is AccountState.Error -> Button(onClick = onGrant) { Text("Try again") }
                    else -> Unit
                }
                TextButton(onClick = { confirmDisconnect = true }) { Text("Disconnect") }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect ${account.label}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Edith won't see its email or calendar.")
                    if (account.provider == Provider.Microsoft) MicrosoftRemoveAccess()
                    if (account.provider == Provider.ICloud) Text(ICloudAccounts.REVOKE_HINT, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { confirmDisconnect = false; onDisconnect() }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Settings → Accounts → an account → **Default calendar** (C24, any provider): where a new event goes when the owner
 * doesn't name a calendar. iCloud's server-side default isn't the iPhone's (that one is a setting on the phone, which
 * no app can read), so the owner picks it here once.
 */
@Composable
private fun DefaultCalendarChoice(
    connected: Boolean,
    loadCalendars: ((List<CalendarInfo>) -> Unit) -> Unit,
    onSetDefault: (String) -> Unit,
) {
    if (!connected) return
    var calendars by remember { mutableStateOf<List<CalendarInfo>?>(null) }
    var choosing by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { loadCalendars { calendars = it } }
    val list = calendars ?: return
    if (list.size < 2) return   // nothing to choose between
    val current = list.firstOrNull { it.isDefault } ?: list.first()
    TextButton(onClick = { choosing = !choosing }, modifier = Modifier.testTag(DEFAULT_CALENDAR_TAG)) {
        Text("Default calendar: ${current.name}")
    }
    if (choosing) {
        Column(Modifier.padding(start = 8.dp)) {
            for (c in list) {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        onSetDefault(c.id)
                        calendars = list.map { it.copy(isDefault = it.id == c.id) }
                        choosing = false
                    }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(selected = c.id == current.id, onClick = null)
                    Text(c.name, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/**
 * C23 §1: Microsoft has no way for an app to remove its own access, so Disconnect deletes the token on the phone and
 * says where the owner can remove Edith at Microsoft.
 */
@Composable
private fun MicrosoftRemoveAccess() {
    val uri = LocalUriHandler.current
    Text("To remove Edith at Microsoft too:", style = MaterialTheme.typography.bodyMedium)
    TextButton(onClick = { runCatching { uri.openUri(MicrosoftAccounts.PERSONAL_PERMISSIONS) } }, modifier = Modifier.testTag(MICROSOFT_PERSONAL_LINK_TAG)) {
        Text("Personal account: account.live.com/consent/Manage")
    }
    TextButton(onClick = { runCatching { uri.openUri(MicrosoftAccounts.WORK_PERMISSIONS) } }) {
        Text("Work or school: myapplications.microsoft.com")
    }
}

/** C17b §3: shown while Edith waits for the browser; closes by itself when the loopback gets the code. */
@Composable
fun BrowserWaitingDialog(provider: Provider, onPaste: (String) -> Unit, onCancel: () -> Unit) {
    var address by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},   // only Cancel stops waiting
        title = { Text("Finish signing in to ${provider.displayName} in your browser") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Sign in and allow access. You'll come back here automatically. If the browser ends on a page that " +
                        "won't load, copy its address (it starts with ${if (provider == Provider.Microsoft) "http://localhost" else "http://127.0.0.1"}) and paste it here.",
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Paste the address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(BROWSER_PASTE_TAG),
                )
                CircularProgressIndicator()
            }
        },
        confirmButton = { TextButton(onClick = { onPaste(address) }, enabled = address.isNotBlank()) { Text("Use this address") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

const val ACCOUNTS_EMPTY_TAG = "accounts_empty"
const val ACCOUNTS_MESSAGE_TAG = "accounts_message"
const val ACCOUNT_ROW_TAG = "account_row_"
const val TASKS_GRANT_TAG = "account_tasks_grant_"
const val ACCOUNT_STATUS_TAG = "account_status"
const val ACCOUNT_LABEL_TAG = "account_label"
const val DEFAULT_CALENDAR_TAG = "default_calendar"
const val ADD_GOOGLE_TAG = "add_google"
const val ADD_MICROSOFT_TAG = "add_microsoft"
const val ADD_ICLOUD_TAG = "add_icloud"
const val ICLOUD_ADDRESS_TAG = "icloud_address"
const val ICLOUD_PASSWORD_TAG = "icloud_password"
const val ICLOUD_ADD_TAG = "icloud_add"
const val ICLOUD_MESSAGE_TAG = "icloud_message"
const val MICROSOFT_PERSONAL_LINK_TAG = "microsoft_personal_link"
const val BROWSER_PASTE_TAG = "browser_paste_address"
