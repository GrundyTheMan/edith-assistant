package com.edith.app.google.calendar.ui

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.time.Clock
import com.edith.app.di.viewModelFactory
import com.edith.app.google.GoogleAuth
import com.edith.app.google.GoogleState
import com.edith.app.google.calendar.CalendarClient
import com.edith.app.google.calendar.CalendarEvent
import com.edith.app.google.calendar.CalendarFormat
import com.edith.app.google.calendar.CalendarSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One day of the agenda: its heading ("Today", "Tomorrow", "Wednesday, Nov 4") and its events. */
data class AgendaDay(val date: LocalDate, val heading: String, val events: List<CalendarEvent>)

sealed interface CalendarUi {
    /** Google isn't connected: the tab explains and links to Settings. */
    data class NotConnected(val needsPermission: Boolean) : CalendarUi
    data object Loading : CalendarUi
    data class Agenda(val days: List<AgendaDay>, val refreshing: Boolean = false, val error: String? = null) : CalendarUi
    data class Failed(val message: String) : CalendarUi
}

/** The Calendar tab (C18 §3): today and the next 7 days, read-only; changes go through Edith with a confirmation. */
class CalendarViewModel(
    private val google: GoogleAuth,
    private val session: CalendarSession,
    private val client: CalendarClient,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow<CalendarUi>(CalendarUi.Loading)
    val state: StateFlow<CalendarUi> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            google.state.map { (it is GoogleState.Connected) to (it is GoogleState.NeedsConsent) }.distinctUntilChanged().collect { (connected, consent) ->
                if (connected) load() else _state.value = CalendarUi.NotConnected(consent)
            }
        }
    }

    fun refresh() {
        val s = _state.value
        if (s is CalendarUi.Agenda) _state.value = s.copy(refreshing = true)
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        if (_state.value !is CalendarUi.Agenda) _state.value = CalendarUi.Loading
        val zone = clock.zone()
        val today = ZonedDateTime.ofInstant(clock.now(), zone).toLocalDate()
        val from = today.atStartOfDay(zone)
        val to = today.plusDays(DAYS.toLong()).atStartOfDay(zone)
        val result = when (val c = session.calendars()) {
            is Outcome.Success -> client.events(c.value, from, to)
            is Outcome.Failure -> c
        }
        when (result) {
            is Outcome.Success -> _state.value = CalendarUi.Agenda(group(result.value, today))
            is Outcome.Failure -> {
                val msg = (result.error as? AppError.ToolFailed)?.detail ?: result.error.userMessage
                _state.update { s -> if (s is CalendarUi.Agenda) s.copy(refreshing = false, error = msg) else if (google.state.value is GoogleState.Connected) CalendarUi.Failed(msg) else s }
            }
        }
    }

    companion object {
        /** Today and the next 7 days. */
        const val DAYS = 8
        private val dayFormat = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)

        /** Events by day (an all-day or multi-day event appears on each day it covers), days with nothing left out. */
        fun group(events: List<CalendarEvent>, today: LocalDate): List<AgendaDay> = (0 until DAYS).mapNotNull { i ->
            val d = today.plusDays(i.toLong())
            val zone = events.firstOrNull()?.start?.zone ?: return@mapNotNull null
            val dayStart = d.atStartOfDay(zone)
            val dayEnd = dayStart.plusDays(1)
            val onDay = events.filter { it.start.isBefore(dayEnd) && it.end.isAfter(dayStart) }
                .sortedWith(compareBy({ !it.allDay }, { it.start }))
            if (onDay.isEmpty()) null else AgendaDay(d, heading(d, today), onDay)
        }

        fun heading(d: LocalDate, today: LocalDate) = when (d) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> dayFormat.format(d)
        }

        val Factory = viewModelFactory { c, _ -> CalendarViewModel(c.googleAuth, c.calendarSession, c.calendarClient, c.clock) }
    }
}

@Composable
fun CalendarScreen(onOpenSettings: () -> Unit, vm: CalendarViewModel = viewModel(factory = CalendarViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    CalendarContent(state, onOpenSettings, vm::refresh)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CalendarContent(state: CalendarUi, onOpenSettings: () -> Unit, onRefresh: () -> Unit) {
    var open by remember { mutableStateOf<CalendarEvent?>(null) }
    when (state) {
        is CalendarUi.NotConnected -> Centered {
            Text(
                if (state.needsPermission) "Edith needs your permission to see your calendar." else "Connect your Google account to see your calendar here, and to let Edith read and add events.",
                textAlign = TextAlign.Center,
            )
            Button(onClick = onOpenSettings) { Text(if (state.needsPermission) "Grant access in Settings" else "Connect Google account") }
        }
        CalendarUi.Loading -> Centered { Text("Loading your calendar…") }
        is CalendarUi.Failed -> Centered {
            Text(state.message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
            Button(onClick = onRefresh) { Text("Try again") }
        }
        is CalendarUi.Agenda -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().testTag(AGENDA_TAG)) {
                state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
                if (state.days.isEmpty()) item { Text("Nothing on your calendar for the next week.", modifier = Modifier.padding(24.dp)) }
                for (day in state.days) {
                    stickyHeader(key = day.date.toString()) {
                        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                            Text(day.heading, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                    items(day.events, key = { "${day.date}-${it.calendarId}-${it.id}" }) { e -> EventRow(e) { open = e } }
                    item { HorizontalDivider(Modifier.padding(horizontal = 16.dp)) }
                }
            }
        }
    }
    open?.let { e -> EventDetails(e) { open = null } }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

@Composable
private fun EventRow(e: CalendarEvent, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            if (e.allDay) "All day" else timeFormat.format(e.start),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            e.location?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun EventDetails(e: CalendarEvent, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(e.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(CalendarFormat.whenText(e.start, e.end, e.allDay))
                e.location?.takeIf { it.isNotBlank() }?.let { Text(it) }
                Text(e.calendarName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                e.description?.takeIf { it.isNotBlank() }?.let { Text(it.take(1_000), style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

const val AGENDA_TAG = "calendar-agenda"
