package com.edith.app.accounts.ui

import android.app.Activity
import android.app.PendingIntent
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edith.app.accounts.Account
import com.edith.app.accounts.AccountDirectory
import com.edith.app.accounts.AccountState
import com.edith.app.accounts.sources.AccountErrors
import com.edith.app.accounts.sources.Readable
import com.edith.app.accounts.sources.Reads
import com.edith.app.accounts.tasks.TaskItem
import com.edith.app.accounts.tasks.TaskList
import com.edith.app.accounts.tasks.TaskSource
import com.edith.app.accounts.tasks.TasksAccess
import com.edith.app.accounts.tools.TaskFormat
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.core.time.Clock
import com.edith.app.di.viewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/** One list in the To-do view: "My Tasks · personal" and its tasks in Google's order. */
data class TodoSection(val account: Account, val list: TaskList, val title: String, val tasks: List<TaskItem>)

sealed interface TodoUi {
    /** No account has to-dos: the view explains and links to Settings. */
    data object NotConnected : TodoUi
    data object Loading : TodoUi
    /**
     * The lists. [needsPermission]: accounts whose to-dos need **Grant access** (one banner each). [problems]: other
     * accounts that couldn't be read, one line each.
     */
    data class Lists(
        val sections: List<TodoSection>,
        val today: LocalDate,
        val showDone: Boolean = false,
        val refreshing: Boolean = false,
        val needsPermission: List<Account> = emptyList(),
        val problems: List<String> = emptyList(),
    ) : TodoUi
    data class Failed(val message: String) : TodoUi
}

/** What the view tells the owner after a tap: "Ticked off …" with Undo, or why it didn't work. */
sealed interface TodoEvent {
    data class Ticked(val section: TodoSection, val task: TaskItem, val done: Boolean) : TodoEvent
    data class Failed(val message: String) : TodoEvent
}

/**
 * The Calendar tab's **To-do** view (C35 §5): every account's lists, read-only apart from the tick box. The owner's
 * own tap ticks a task off directly (D11 covers what Edith changes); adding, editing and reordering go through Edith.
 * The view never ranks (trap 4).
 */
class TodoViewModel(
    private val dir: AccountDirectory,
    accounts: StateFlow<List<Pair<Account, AccountState>>>,
    tasksAccess: StateFlow<Map<String, TasksAccess>>,
    loaded: StateFlow<Boolean>,
    private val clock: Clock,
    /** **To-dos: Grant access** for an account (Accounts.grantTasks), and the consent screen's answer. */
    private val grant: suspend (id: String, Activity, (PendingIntent) -> Unit) -> Unit = { _, _, _ -> },
    private val consentResult: suspend (android.content.Intent?) -> Unit = {},
    /** Where signing in runs: it outlives this screen (C17b). */
    private val appScope: CoroutineScope? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<TodoUi>(TodoUi.Loading)
    val state: StateFlow<TodoUi> = _state.asStateFlow()

    private val _events = Channel<TodoEvent>(Channel.BUFFERED)
    val events: Flow<TodoEvent> = _events.receiveAsFlow()

    private var showDone = false

    init {
        viewModelScope.launch {
            // Reload when an account comes, goes, changes between usable and not, or its to-dos gain or lose permission.
            combine(loaded, accounts, tasksAccess) { isLoaded, list, access ->
                Triple(isLoaded, list.map { (a, s) -> a.id to (s::class to a.label) }, access.mapValues { it.value == TasksAccess.NeedsPermission })
            }.distinctUntilChanged().collect { (isLoaded, _, _) -> if (isLoaded) decide() }
        }
    }

    fun refresh() {
        val s = _state.value
        if (s is TodoUi.Lists) _state.value = s.copy(refreshing = true)
        viewModelScope.launch { load() }
    }

    /**
     * The view was opened again (the Agenda | To-do switch, or another tab): what Edith changed in the meantime
     * isn't shown until it reloads. The first open already loads, so only a view that has lists reloads.
     */
    fun refreshIfShown() {
        if (_state.value is TodoUi.Lists) refresh()
    }

    fun setShowDone(show: Boolean) {
        showDone = show
        refresh()
    }

    /** The tick box: the owner's tap is the confirmation. The view updates at once and goes back if Google says no. */
    fun toggle(section: TodoSection, task: TaskItem) {
        val done = !task.done
        val before = _state.value
        edit { t -> if (t.id == task.id) (if (done && !showDone) null else t.copy(done = done)) else t }
        viewModelScope.launch {
            val source = sourceOf(section.account.id)
            val r = source?.complete(section.list.id, task.id, done) ?: Outcome.Failure(AppError.GoogleNotConnected)
            when (r) {
                is Outcome.Success -> _events.send(TodoEvent.Ticked(section, task, done))
                is Outcome.Failure -> {
                    Log.w(LogTag.Accounts, "To-do view: couldn't tick a task in ${section.account.id}: ${Reads.why(r.error)}")
                    _state.value = before
                    _events.send(TodoEvent.Failed("Couldn't change \"${task.title}\": ${Reads.why(r.error)}"))
                }
            }
        }
    }

    /** **Undo** on the snackbar: puts the task back as it was. */
    fun undo(e: TodoEvent.Ticked) {
        viewModelScope.launch {
            when (val r = sourceOf(e.section.account.id)?.complete(e.section.list.id, e.task.id, !e.done)) {
                is Outcome.Failure -> _events.send(TodoEvent.Failed("Couldn't undo: ${Reads.why(r.error)}"))
                else -> Unit
            }
            load()
        }
    }

    fun grantTasks(id: String, activity: Activity, show: (PendingIntent) -> Unit) {
        (appScope ?: viewModelScope).launch(Dispatchers.Main.immediate) { grant(id, activity, show) }
    }

    fun onConsentResult(data: android.content.Intent?) {
        viewModelScope.launch { consentResult(data) }
    }

    private fun sourceOf(accountId: String): TaskSource? = dir.entries().firstOrNull { it.account.id == accountId }?.tasks

    private fun edit(change: (TaskItem) -> TaskItem?) {
        val s = _state.value as? TodoUi.Lists ?: return
        _state.value = s.copy(sections = s.sections.map { sec -> sec.copy(tasks = sec.tasks.mapNotNull(change)) })
    }

    private suspend fun decide() {
        val withTasks = dir.entries().filter { it.tasks != null }
        when {
            withTasks.isEmpty() -> _state.value = TodoUi.NotConnected
            withTasks.all { it.state == AccountState.Checking } -> _state.value = TodoUi.Loading
            else -> load()
        }
    }

    private suspend fun load() {
        if (_state.value !is TodoUi.Lists) _state.value = TodoUi.Loading
        val entries = dir.entries().filter { it.tasks != null && it.state != AccountState.Checking }
        val includeDone = showDone
        val read = Reads.readAll(entries.map { Readable(it.account, it.state, requireNotNull(it.tasks)) }) { src -> readAll(src, includeDone) }
        val showAccount = dir.entries().count { it.tasks != null } > 1
        val after = dir.entries().associateBy { it.account.id }
        val needsPermission = read.failures.filter { it.why == AccountErrors.TASKS_NEED_PERMISSION || after[it.account.id]?.tasksAccess == TasksAccess.NeedsPermission }
        val problems = read.failures - needsPermission.toSet()
        val sections = read.results.flatMap { (account, lists) -> lists.map { (l, tasks) -> TodoSection(account, l, TaskFormat.listName(l, account, showAccount), tasks) } }
        _state.value = if (sections.isEmpty() && problems.isNotEmpty() && needsPermission.isEmpty()) {
            TodoUi.Failed(problems.joinToString("\n") { it.line })
        } else {
            TodoUi.Lists(sections, clock.now().atZone(clock.zone()).toLocalDate(), includeDone, needsPermission = needsPermission.map { it.account }, problems = problems.map { it.line })
        }
    }

    private suspend fun readAll(src: TaskSource, includeDone: Boolean): Outcome<List<Pair<TaskList, List<TaskItem>>>> {
        val lists = when (val r = src.lists()) { is Outcome.Success -> r.value; is Outcome.Failure -> return r }
        val out = mutableListOf<Pair<TaskList, List<TaskItem>>>()
        for (l in lists) {
            when (val r = src.tasks(l.id, includeDone)) { is Outcome.Success -> out += l to r.value; is Outcome.Failure -> return r }
        }
        return Outcome.Success(out)
    }

    companion object {
        val Factory = viewModelFactory { c, _ ->
            TodoViewModel(
                c.accounts, c.accounts.accounts, c.accounts.tasksAccess, c.accounts.loaded, c.clock,
                grant = { id, a, show -> c.accounts.grantTasks(id, a, show) },
                consentResult = { c.accounts.onConsentResult(it) },
                appScope = c.appScope,
            )
        }
    }
}

@Composable
fun TodoScreen(onOpenSettings: () -> Unit, vm: TodoViewModel = viewModel(factory = TodoViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val activity = LocalActivity.current
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result -> vm.onConsentResult(result.data) }
    LaunchedEffect(vm) { vm.refreshIfShown() }
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            snackbar.currentSnackbarData?.dismiss()   // a newer tick replaces the last one's Undo
            launch {
                when (e) {
                    is TodoEvent.Ticked -> {
                        val text = if (e.done) "Ticked off \"${e.task.title}\"" else "\"${e.task.title}\" is not done"
                        // C35 §5: Undo for 5 seconds (Material's Short is 4 s and Long 10 s).
                        val r = withTimeoutOrNull(UNDO_MS) { snackbar.showSnackbar(text, actionLabel = "Undo", duration = SnackbarDuration.Indefinite) }
                        if (r == SnackbarResult.ActionPerformed) vm.undo(e)
                    }
                    is TodoEvent.Failed -> snackbar.showSnackbar(e.message, duration = SnackbarDuration.Short)
                }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        TodoContent(
            state, onOpenSettings, vm::refresh, vm::toggle, vm::setShowDone,
            onGrant = { id -> activity?.let { a -> vm.grantTasks(id, a) { consent.launch(IntentSenderRequest.Builder(it.intentSender).build()) } } },
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/** Stateless To-do view, so it can be tested without a ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoContent(
    state: TodoUi,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onToggle: (TodoSection, TaskItem) -> Unit,
    onShowDone: (Boolean) -> Unit,
    onGrant: (accountId: String) -> Unit,
) {
    when (state) {
        TodoUi.NotConnected -> TodoCentered {
            Text("Add a Google account to see your to-dos here, and to let Edith read and change them.", textAlign = TextAlign.Center)
            Button(onClick = onOpenSettings) { Text("Add an account") }
        }
        TodoUi.Loading -> TodoCentered { Text("Loading your to-dos…") }
        is TodoUi.Failed -> TodoCentered {
            Text(state.message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
            Button(onClick = onRefresh) { Text("Try again") }
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
        is TodoUi.Lists -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().testTag(TODO_TAG)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Show done", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(checked = state.showDone, onCheckedChange = onShowDone, modifier = Modifier.testTag(SHOW_DONE_TAG))
                    }
                }
                items(state.needsPermission, key = { "grant-${it.id}" }) { a -> GrantBanner(a) { onGrant(a.id) } }
                items(state.problems) { line -> TodoProblem(line, onOpenSettings) }
                if (state.sections.isEmpty() && state.needsPermission.isEmpty()) item { Text("No to-do lists.", modifier = Modifier.padding(24.dp)) }
                for (section in state.sections) {
                    item(key = "h-${section.account.id}-${section.list.id}") {
                        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                            Text(section.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                    if (section.tasks.isEmpty()) item(key = "e-${section.account.id}-${section.list.id}") {
                        Text("Nothing to do.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    val open = section.tasks.filter { !it.done }.map { it.id }.toSet()
                    items(section.tasks, key = { "t-${section.account.id}-${section.list.id}-${it.id}" }) { t ->
                        TaskRow(t, indent = t.parent != null && t.parent in open, today = state.today) { onToggle(section, t) }
                    }
                    item(key = "d-${section.account.id}-${section.list.id}") { HorizontalDivider(Modifier.padding(horizontal = 16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(t: TaskItem, indent: Boolean, today: LocalDate, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (indent) 40.dp else 4.dp, end = 16.dp).testTag(TASK_ROW_TAG + t.id),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = t.done, onCheckedChange = { onToggle() }, modifier = Modifier.testTag(TASK_CHECK_TAG + t.id))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textDecoration = if (t.done) TextDecoration.LineThrough else null,
                color = if (t.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            t.due?.let { d ->
                val overdue = !t.done && d.isBefore(today)
                Text(
                    (if (d == today) "Due today" else "Due ${TaskFormat.day(d)}") + if (overdue) " · overdue" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            t.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** An account whose to-dos need permission (C35 §5), in C22's banner style. */
@Composable
private fun GrantBanner(account: Account, onGrant: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag(GRANT_TAG + account.id)) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${account.label}: to-dos need your permission.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            TextButton(onClick = onGrant) { Text("Grant access") }
        }
    }
}

@Composable
private fun TodoProblem(line: String, onOpenSettings: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
    }
}

@Composable
private fun TodoCentered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
    }
}

const val UNDO_MS = 5_000L
const val TODO_TAG = "calendar-todo"
const val SHOW_DONE_TAG = "todo-show-done"
const val TASK_ROW_TAG = "todo-task-"
const val TASK_CHECK_TAG = "todo-check-"
const val GRANT_TAG = "todo-grant-"
