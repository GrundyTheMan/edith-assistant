package com.edith.app.accounts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.edith.app.accounts.Account
import com.edith.app.accounts.AccountDirectory
import com.edith.app.accounts.AccountState
import com.edith.app.accounts.mail.MailRanker
import com.edith.app.accounts.mail.MailRanking
import com.edith.app.accounts.mail.MailRating
import com.edith.app.accounts.sources.MailMessage
import com.edith.app.accounts.sources.MailQuery
import com.edith.app.accounts.sources.MailSummary
import com.edith.app.accounts.sources.OutgoingMail
import com.edith.app.accounts.sources.Readable
import com.edith.app.accounts.sources.Reads
import com.edith.app.core.error.Outcome
import com.edith.app.core.time.Clock
import com.edith.app.core.time.formatPattern
import com.edith.app.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.days

/** One email in the ranked list: the summary, its account and how much it matters. */
data class MailRow(val summary: MailSummary, val account: Account, val rating: MailRating) {
    val key: String get() = MailRanking.key(summary)
}

/** The state of the Mail tab (K7.1). */
sealed interface MailUi {
    /** No account can be read: the tab explains and links to Settings. [needsSignIn]: there are accounts, but none works now. */
    data class NotConnected(val needsSignIn: Boolean) : MailUi
    data object Loading : MailUi
    data class Failed(val message: String) : MailUi

    /**
     * The ranked inbox, most important first. [accounts]: the accounts that have mail, for the filter; [onlyAccountId]: the one picked, or null for all.
     * [note]: how the ranking went ("Ranked by your model", "Estimated from signals: …"); [ranking]: the model is still working.
     */
    data class Inbox(
        val rows: List<MailRow>,
        val accounts: List<Account>,
        val onlyAccountId: String? = null,
        val importantOnly: Boolean = false,
        val note: String? = null,
        val ranking: Boolean = false,
        val refreshing: Boolean = false,
        val problems: List<String> = emptyList(),
        val zone: kotlinx.datetime.TimeZone,
    ) : MailUi {
        val showAccount: Boolean get() = accounts.size > 1
    }
}

/** An email being read: [message] is null while it loads or when it could not be read ([error]). */
data class MailReader(val row: MailRow, val message: MailMessage? = null, val error: String? = null, val canSend: Boolean = true)

/**
 * The Mail tab (K7.1, the owner's ask of 2026-11-27): every account's recent mail in one list, ranked by how much it matters. The ranking is the local model's
 * (Jarvis's prompt over Edith's own Ollama call), shown at once as an estimate from signals and replaced as the model's ratings arrive; ratings are kept so an
 * email is rated once. Reading and replying go through the account's own [com.edith.app.accounts.sources.MailSource]; a reply is saved as a draft or sent after
 * the owner has seen the whole email on a confirmation (D5, D11).
 */
class MailViewModel(
    private val dir: AccountDirectory,
    accounts: StateFlow<List<Pair<Account, AccountState>>>,
    loaded: StateFlow<Boolean>,
    private val clock: Clock,
    private val settings: SettingsRepository,
    private val ranker: MailRanker,
) : ViewModel() {
    private val _state = MutableStateFlow<MailUi>(MailUi.Loading)
    val state: StateFlow<MailUi> = _state.asStateFlow()
    private val _reader = MutableStateFlow<MailReader?>(null)
    val reader: StateFlow<MailReader?> = _reader.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var summaries: List<Pair<Account, MailSummary>> = emptyList()
    private var ratings: Map<String, MailRating> = emptyMap()
    private var note: String? = null
    private var ranking = false
    private var problems: List<String> = emptyList()
    private var only: String? = null
    private var important = false
    private var rankJob: Job? = null

    init {
        viewModelScope.launch {
            combine(loaded, accounts) { isLoaded, list -> isLoaded to list.map { (a, s) -> a.id to (s::class to a.label) } }
                .distinctUntilChanged()
                .collect { (isLoaded, _) -> if (isLoaded) decide() }
        }
    }

    fun refresh() {
        val s = _state.value
        if (s is MailUi.Inbox) _state.value = s.copy(refreshing = true)
        viewModelScope.launch { load() }
    }

    fun filterAccount(accountId: String?) { only = accountId; publish() }
    fun filterImportant(on: Boolean) { important = on; publish() }
    fun dismissNotice() { _notice.value = null }

    private suspend fun decide() {
        val withMail = dir.entries().filter { it.mail != null }
        when {
            withMail.isEmpty() -> _state.value = MailUi.NotConnected(needsSignIn = false)
            withMail.all { it.state is AccountState.NeedsSignIn } -> _state.value = MailUi.NotConnected(needsSignIn = true)
            withMail.all { it.state == AccountState.Checking } -> _state.value = MailUi.Loading
            else -> load()
        }
    }

    private suspend fun load() {
        rankJob?.cancel()
        if (_state.value !is MailUi.Inbox) _state.value = MailUi.Loading
        val now = clock.now()
        val targets = dir.entries().filter { it.mail != null && it.state != AccountState.Checking }.map { Readable(it.account, it.state, requireNotNull(it.mail)) }
        val read = Reads.readAll(targets) { it.search(MailQuery(after = now - WINDOW_DAYS.days), PER_ACCOUNT) }
        if (read.results.isEmpty() && read.failures.isNotEmpty()) {
            _state.value = MailUi.Failed(read.failureText())
            return
        }
        summaries = read.results.flatMap { (account, found) -> found.messages.map { account to it.copy(accountId = it.accountId.ifBlank { account.id }) } }
        problems = read.failures.map { it.line }
        val s = settings.current()
        val model = s.model
        ratings = if (model == null) emptyMap() else ranker.saved(summaries.map { it.second }, model)
        note = if (model == null) "No model chosen in Settings, so this is an estimate from signals (unread, sent to you, new)." else null
        ranking = false
        publish()
        if (model != null) rank(model, s.serverUrl)
    }

    private fun rank(model: String, serverUrl: String) {
        val own = summaries.associate { (a, m) -> MailRanking.key(m) to a.address }
        val todo = summaries.map { it.second }.filter { MailRanking.key(it) !in ratings }
            .sortedByDescending { m -> estimate(m).score }
            .take(MAX_RATED)
        if (todo.isEmpty()) { note = "Ranked by $model."; publish(); return }
        ranking = true
        note = "Ranking with $model…"
        publish()
        rankJob = viewModelScope.launch {
            val failure = ranker.rate(todo, { own[MailRanking.key(it)].orEmpty() }, serverUrl, model) { got ->
                ratings = ratings + got
                publish()
            }
            ranking = false
            note = if (failure == null) "Ranked by $model." else "Couldn't reach your model (${Reads.why(failure)}): showing estimates from signals."
            publish()
        }
    }

    private fun estimate(m: MailSummary): MailRating {
        val address = summaries.firstOrNull { it.second.accountId == m.accountId }?.first?.address.orEmpty()
        return MailRanking.estimate(m, address, clock.now())
    }

    private fun publish() {
        val accounts = summaries.map { it.first }.distinctBy { it.id }
        val rows = summaries
            .filter { (a, _) -> only == null || a.id == only }
            .map { (a, m) -> MailRow(m, a, ratings[MailRanking.key(m)] ?: estimate(m)) }
            .filter { !important || it.rating.score >= IMPORTANT }
            .sortedWith(compareByDescending<MailRow> { it.rating.score }.thenByDescending { it.summary.date })
        _state.value = MailUi.Inbox(rows, accounts, only, important, note, ranking, problems = problems, zone = clock.zone())
    }

    fun open(row: MailRow) {
        val source = dir.entries().firstOrNull { it.account.id == row.account.id }?.mail
        if (source == null) {
            _reader.value = MailReader(row, error = "This account can't be read now.", canSend = false)
            return
        }
        _reader.value = MailReader(row, canSend = source.canSend)
        viewModelScope.launch {
            _reader.value = when (val result = source.read(row.summary.id)) {
                is Outcome.Success -> MailReader(row, message = result.value, canSend = source.canSend)
                is Outcome.Failure -> MailReader(row, error = "Couldn't open this email: ${Reads.why(result.error)}", canSend = source.canSend)
            }
        }
    }

    fun close() { _reader.value = null }

    /** The reply the reader's Reply button starts with: to the sender, "Re:" the subject. */
    fun replyTo(row: MailRow): OutgoingMail = OutgoingMail(
        to = MailRanking.addressOf(row.summary.from),
        subject = row.summary.subject.let { if (it.startsWith("Re:", ignoreCase = true)) it else "Re: $it" },
        body = "",
        inReplyTo = row.summary.messageIdHeader,
        threadId = row.summary.threadId,
        replyToId = row.summary.id,
    )

    fun saveDraft(row: MailRow, mail: OutgoingMail) = act(row, "Draft saved.") { it.draft(mail) }

    /** Only after the owner has seen the whole email on the confirmation. */
    fun send(row: MailRow, mail: OutgoingMail) = act(row, "Sent.") { it.send(mail) }

    private fun act(row: MailRow, done: String, call: suspend (com.edith.app.accounts.sources.MailSource) -> Outcome<Unit>) {
        val source = dir.entries().firstOrNull { it.account.id == row.account.id }?.mail ?: run { _notice.value = "This account can't be used now."; return }
        viewModelScope.launch {
            _notice.value = when (val r = call(source)) {
                is Outcome.Success -> done
                is Outcome.Failure -> "That didn't work: ${Reads.why(r.error)}"
            }
        }
    }

    companion object {
        /** How far back the list reaches, and the most emails read from one account. */
        const val WINDOW_DAYS = 14
        const val PER_ACCOUNT = 40
        /** The most emails the model rates in one go (newest signals first); the rest stay estimated. */
        const val MAX_RATED = 60
        /** A score at or above this is "important". */
        const val IMPORTANT = 7
    }
}

/** The Mail tab over its view model: the ranked list, the reader and the reply. */
@Composable
fun MailScreenFrame(vm: MailViewModel, onOpenSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val reader by vm.reader.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    MailContent(
        state, reader, notice, onOpenSettings,
        onRefresh = vm::refresh, onOpen = vm::open, onClose = vm::close,
        onFilterAccount = vm::filterAccount, onFilterImportant = vm::filterImportant,
        replyTo = vm::replyTo, onSaveDraft = vm::saveDraft, onSend = vm::send, onDismissNotice = vm::dismissNotice,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailContent(
    state: MailUi,
    reader: MailReader?,
    notice: String?,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (MailRow) -> Unit,
    onClose: () -> Unit,
    onFilterAccount: (String?) -> Unit,
    onFilterImportant: (Boolean) -> Unit,
    replyTo: (MailRow) -> OutgoingMail,
    onSaveDraft: (MailRow, OutgoingMail) -> Unit,
    onSend: (MailRow, OutgoingMail) -> Unit,
    onDismissNotice: () -> Unit,
) {
    when (state) {
        is MailUi.NotConnected -> MailCentered {
            Text(
                if (state.needsSignIn) "Your accounts need signing in again before Edith can show your mail." else "Add an account to see your mail here, ranked by what matters.",
                textAlign = TextAlign.Center,
            )
            Button(onClick = onOpenSettings) { Text(if (state.needsSignIn) "Sign in again in Settings" else "Add an account") }
        }
        MailUi.Loading -> MailCentered { Text("Loading your mail…") }
        is MailUi.Failed -> MailCentered {
            Text(state.message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
            Button(onClick = onRefresh) { Text("Try again") }
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
        is MailUi.Inbox -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                MailFilters(state, onFilterAccount, onFilterImportant)
                state.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
                state.problems.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) }
                LazyColumn(Modifier.fillMaxSize().testTag(MAIL_LIST_TAG)) {
                    if (state.rows.isEmpty()) item { Text(if (state.importantOnly) "Nothing important in the last ${MailViewModel.WINDOW_DAYS} days." else "No mail in the last ${MailViewModel.WINDOW_DAYS} days.", modifier = Modifier.padding(24.dp)) }
                    items(state.rows, key = { it.key }) { row ->
                        MailRowView(row, state.showAccount, state.zone) { onOpen(row) }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
    reader?.let { MailReaderDialog(it, state.zoneOrDefault(), replyTo, onSaveDraft, onSend, onClose) }
    notice?.let {
        AlertDialog(onDismissRequest = onDismissNotice, text = { Text(it) }, confirmButton = { TextButton(onClick = onDismissNotice) { Text("OK") } })
    }
}

private fun MailUi.zoneOrDefault() = (this as? MailUi.Inbox)?.zone ?: com.edith.app.core.time.SystemClock.zone()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MailFilters(state: MailUi.Inbox, onAccount: (String?) -> Unit, onImportant: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = state.importantOnly, onClick = { onImportant(!state.importantOnly) }, label = { Text("Important") })
        if (state.accounts.size > 1) {
            FilterChip(selected = state.onlyAccountId == null, onClick = { onAccount(null) }, label = { Text("All") })
            state.accounts.take(3).forEach { a ->
                FilterChip(selected = state.onlyAccountId == a.id, onClick = { onAccount(a.id) }, label = { Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
            }
        }
    }
}

@Composable
private fun MailCentered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
    }
}

/** The colour for a score: red from 8, amber from 5, grey below. */
private fun scoreColor(score: Int): Color = when {
    score >= 8 -> Color(0xFFC62828)
    score >= 5 -> Color(0xFFEF6C00)
    else -> Color(0xFF757575)
}

@Composable
private fun MailRowView(row: MailRow, showAccount: Boolean, zone: kotlinx.datetime.TimeZone, onClick: () -> Unit) {
    val m = row.summary
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(scoreColor(row.rating.score)), contentAlignment = Alignment.Center) {
            Text(row.rating.score.toString(), color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    MailRanking.nameOf(m.from), style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (m.unread) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(formatPattern(m.date, zone, "MMM d, h:mm a", "en-US"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(m.subject.ifBlank { "(no subject)" }, style = MaterialTheme.typography.bodyMedium, fontWeight = if (m.unread) FontWeight.Medium else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (row.rating.reason.isNotBlank()) {
                Text(
                    row.rating.reason, style = MaterialTheme.typography.bodySmall, fontStyle = if (row.rating.estimated) FontStyle.Italic else FontStyle.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (showAccount) Text(row.account.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }
    }
}

@Composable
private fun MailReaderDialog(
    reader: MailReader,
    zone: kotlinx.datetime.TimeZone,
    replyTo: (MailRow) -> OutgoingMail,
    onSaveDraft: (MailRow, OutgoingMail) -> Unit,
    onSend: (MailRow, OutgoingMail) -> Unit,
    onClose: () -> Unit,
) {
    var composing by remember(reader.row.key) { mutableStateOf<OutgoingMail?>(null) }
    var confirming by remember(reader.row.key) { mutableStateOf<OutgoingMail?>(null) }
    val m = reader.row.summary
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(m.subject.ifBlank { "(no subject)" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("From ${m.from}", style = MaterialTheme.typography.bodySmall)
                m.to?.let { Text("To $it", style = MaterialTheme.typography.bodySmall) }
                reader.message?.cc?.takeIf { it.isNotBlank() }?.let { Text("Cc $it", style = MaterialTheme.typography.bodySmall) }
                Text(
                    "${formatPattern(m.date, zone, "EEE, MMM d, h:mm a", "en-US")} · ${reader.row.account.label} · ${reader.row.rating.score} of 10" +
                        (if (reader.row.rating.reason.isNotBlank()) ": ${reader.row.rating.reason}" else ""),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                when {
                    reader.error != null -> Text(reader.error, color = MaterialTheme.colorScheme.error)
                    reader.message == null -> Text("Opening…")
                    else -> Text(reader.message.body.ifBlank { "(no text)" })
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
        confirmButton = { if (reader.message != null) TextButton(onClick = { composing = replyTo(reader.row) }) { Text("Reply") } },
    )
    composing?.let { draft ->
        var body by remember(draft) { mutableStateOf(draft.body) }
        AlertDialog(
            onDismissRequest = { composing = null },
            title = { Text("Reply to ${MailRanking.nameOf(m.from)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("To ${draft.to} · ${draft.subject}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp), label = { Text("Your reply") })
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { composing = null }) { Text("Cancel") }
                    TextButton(enabled = body.isNotBlank(), onClick = { onSaveDraft(reader.row, draft.copy(body = body)); composing = null }) { Text("Save draft") }
                }
            },
            confirmButton = {
                if (reader.canSend) TextButton(enabled = body.isNotBlank(), onClick = { confirming = draft.copy(body = body); composing = null }) { Text("Review and send") }
            },
        )
    }
    confirming?.let { mail ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Send this email?") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("From ${reader.row.account.address}", style = MaterialTheme.typography.bodySmall)
                    Text("To ${mail.to}", style = MaterialTheme.typography.bodySmall)
                    Text(mail.subject, fontWeight = FontWeight.Medium)
                    HorizontalDivider()
                    Text(mail.body)
                }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Don't send") } },
            confirmButton = { Button(onClick = { onSend(reader.row, mail); confirming = null }) { Text("Send") } },
        )
    }
}

const val MAIL_LIST_TAG = "mail-list"
