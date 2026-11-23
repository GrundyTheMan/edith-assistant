package com.edith.app.accounts.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import com.edith.app.core.time.javaNow
import com.edith.app.core.time.javaZone
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edith.app.accounts.Account
import com.edith.app.accounts.AccountDirectory
import com.edith.app.accounts.AccountState
import com.edith.app.accounts.sources.CalendarMerge
import com.edith.app.accounts.sources.MergedEvent
import com.edith.app.accounts.sources.Readable
import com.edith.app.accounts.sources.Reads
import com.edith.app.accounts.tools.CalendarFormat
import com.edith.app.core.time.Clock
import com.edith.app.di.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One day of the agenda: its heading ("Today", "Tomorrow", "Wednesday, Nov 4") and its events. */
data class AgendaDay(val date: LocalDate, val heading: String, val events: List<MergedEvent>)

/** The state of the Calendar tab's Agenda view (C22). */
sealed interface CalendarUi {
    /** No account can be read: the tab explains and links to Settings. [needsSignIn]: there are accounts, but none works now. */
    data class NotConnected(val needsSignIn: Boolean) : CalendarUi
    data object Loading : CalendarUi
    /**
     * The agenda. [showAccount]: more than one account, so each event names its account. [problems]: accounts that
     * couldn't be read, one line each (C22 §6).
     */
    data class Agenda(
        val days: List<AgendaDay>,
        val refreshing: Boolean = false,
        val error: String? = null,
        val showAccount: Boolean = false,
        val problems: List<String> = emptyList(),
    ) : CalendarUi
    data class Failed(val message: String) : CalendarUi
}

/**
 * The Calendar tab (C18 §3; every account since C22): today and the next 7 days, merged by time, read-only; changes go
 * through Edith with a confirmation.
 */
class CalendarViewModel(
    private val dir: AccountDirectory,
    accounts: StateFlow<List<Pair<Account, AccountState>>>,
    loaded: StateFlow<Boolean>,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow<CalendarUi>(CalendarUi.Loading)
    val state: StateFlow<CalendarUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Reload when an account comes, goes, or changes between usable and not (not on every recheck).
            combine(loaded, accounts) { isLoaded, list -> isLoaded to list.map { (a, s) -> a.id to (s::class to a.label) } }
                .distinctUntilChanged()
                .collect { (isLoaded, _) -> if (isLoaded) decide() }
        }
    }

    fun refresh() {
        val s = _state.value
        if (s is CalendarUi.Agenda) _state.value = s.copy(refreshing = true)
        viewModelScope.launch { load() }
    }

    private suspend fun decide() {
        val withCalendar = dir.entries().filter { it.calendar != null }
        when {
            withCalendar.isEmpty() -> _state.value = CalendarUi.NotConnected(needsSignIn = false)
            withCalendar.all { it.state is AccountState.NeedsSignIn } -> _state.value = CalendarUi.NotConnected(needsSignIn = true)
            withCalendar.all { it.state == AccountState.Checking } -> _state.value = CalendarUi.Loading
            else -> load()
        }
    }

    private suspend fun load() {
        if (_state.value !is CalendarUi.Agenda) _state.value = CalendarUi.Loading
        val zone = clock.javaZone()
        val today = ZonedDateTime.ofInstant(clock.javaNow(), zone).toLocalDate()
        val from = today.atStartOfDay(zone).toInstant()
        val to = today.plusDays(DAYS.toLong()).atStartOfDay(zone).toInstant()
        val targets = dir.entries().filter { it.calendar != null && it.state != AccountState.Checking }
            .map { Readable(it.account, it.state, requireNotNull(it.calendar)) }
        val read = Reads.readAll(targets) { it.events(from, to) }
        _state.value = if (read.results.isEmpty() && read.failures.isNotEmpty()) {
            CalendarUi.Failed(read.failureText())
        } else {
            CalendarUi.Agenda(
                group(CalendarMerge.merge(read.results), today),
                showAccount = dir.entries().count { it.calendar != null } > 1,
                problems = read.failures.map { it.line },
            )
        }
    }

    companion object {
        /** Today and the next 7 days. */
        const val DAYS = 8
        private val dayFormat = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)

        /** Events by day (an all-day or multi-day event appears on each day it covers), days with nothing left out. */
        fun group(events: List<MergedEvent>, today: LocalDate): List<AgendaDay> = (0 until DAYS).mapNotNull { i ->
            val d = today.plusDays(i.toLong())
            val zone = events.firstOrNull()?.event?.start?.zone ?: return@mapNotNull null
            val dayStart = d.atStartOfDay(zone)
            val dayEnd = dayStart.plusDays(1)
            val onDay = events.filter { it.event.start.isBefore(dayEnd) && it.event.end.isAfter(dayStart) }
                .sortedWith(compareBy({ !it.event.allDay }, { it.event.start }))
            if (onDay.isEmpty()) null else AgendaDay(d, heading(d, today), onDay)
        }

        fun heading(d: LocalDate, today: LocalDate) = when (d) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> dayFormat.format(d)
        }

        val Factory = viewModelFactory { c, _ -> CalendarViewModel(c.accounts, c.accounts.accounts, c.accounts.loaded, c.clock) }
    }
}

/** The Calendar tab's two views (C35 §5): a switch instead of a fifth tab. */
enum class CalendarView(val label: String) { Agenda("Agenda"), Todo("To-do") }

/** The Calendar tab: **Agenda | To-do** at the top (C35 §5). The To-do view loads only once it's picked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(onOpenSettings: () -> Unit) {
    var view by rememberSaveable { mutableStateOf(CalendarView.Agenda) }
    Column(Modifier.fillMaxSize()) {
        CalendarViewSwitch(view) { view = it }
        Box(Modifier.weight(1f)) {
            when (view) {
                CalendarView.Agenda -> AgendaScreen(onOpenSettings)
                CalendarView.Todo -> TodoScreen(onOpenSettings)
            }
        }
    }
}

/** The segmented control. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarViewSwitch(view: CalendarView, onChange: (CalendarView) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(VIEW_SWITCH_TAG)) {
        CalendarView.entries.forEachIndexed { i, v ->
            SegmentedButton(
                selected = view == v,
                onClick = { onChange(v) },
                shape = SegmentedButtonDefaults.itemShape(i, CalendarView.entries.size),
                modifier = Modifier.testTag(VIEW_SWITCH_TAG + "-" + v.name),
            ) { Text(v.label) }
        }
    }
}

@Composable
fun AgendaScreen(onOpenSettings: () -> Unit, vm: CalendarViewModel = viewModel(factory = CalendarViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    CalendarContent(state, onOpenSettings, vm::refresh)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CalendarContent(state: CalendarUi, onOpenSettings: () -> Unit, onRefresh: () -> Unit) {
    var open by remember { mutableStateOf<MergedEvent?>(null) }
    when (state) {
        is CalendarUi.NotConnected -> Centered {
            Text(
                if (state.needsSignIn) "Your accounts need signing in again before Edith can see your calendar." else "Add an account to see your calendar here, and to let Edith read and add events.",
                textAlign = TextAlign.Center,
            )
            Button(onClick = onOpenSettings) { Text(if (state.needsSignIn) "Sign in again in Settings" else "Add an account") }
        }
        CalendarUi.Loading -> Centered { Text("Loading your calendar…") }
        is CalendarUi.Failed -> Centered {
            Text(state.message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
            Button(onClick = onRefresh) { Text("Try again") }
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
        is CalendarUi.Agenda -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().testTag(AGENDA_TAG)) {
                state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
                items(state.problems) { line -> ProblemBanner(line, onOpenSettings) }
                if (state.days.isEmpty()) item { Text("Nothing on your calendar for the next week.", modifier = Modifier.padding(24.dp)) }
                for (day in state.days) {
                    stickyHeader(key = day.date.toString()) {
                        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                            Text(day.heading, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                    items(day.events, key = { "${day.date}-${it.account.id}-${it.event.calendarId}-${it.event.id}" }) { m ->
                        EventRow(m, state.showAccount) { open = m }
                    }
                    item { HorizontalDivider(Modifier.padding(horizontal = 16.dp)) }
                }
            }
        }
    }
    open?.let { m -> EventDetails(m) { open = null } }
}

/** One account that couldn't be read, with a way to Settings (C22 §6). */
@Composable
private fun ProblemBanner(line: String, onOpenSettings: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

/** "work", or "personal, work" for an invite in both. */
private fun labels(m: MergedEvent) = (listOf(m.account) + m.alsoIn.map { it.first }).joinToString(", ") { it.label }

@Composable
private fun EventRow(m: MergedEvent, showAccount: Boolean, onClick: () -> Unit) {
    val e = m.event
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            if (e.allDay) "All day" else timeFormat.format(e.start),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            e.location?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (showAccount) Text(labels(m), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EventDetails(m: MergedEvent, onClose: () -> Unit) {
    val e = m.event
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(e.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(CalendarFormat.whenText(e.start, e.end, e.allDay))
                e.location?.takeIf { it.isNotBlank() }?.let { Text(it) }
                Text("${e.calendarName} · ${labels(m)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                e.description?.takeIf { it.isNotBlank() }?.let { Text(it.take(1_000), style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

const val AGENDA_TAG = "calendar-agenda"
const val VIEW_SWITCH_TAG = "calendar-view"
