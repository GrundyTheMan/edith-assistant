package com.edith.app.google.tasks

import com.edith.app.accounts.Account
import com.edith.app.accounts.tasks.TaskItem
import com.edith.app.accounts.tasks.TaskList
import com.edith.app.accounts.tasks.TaskPatch
import com.edith.app.accounts.tasks.TaskSource
import com.edith.app.core.error.Outcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate

/**
 * One Google account's to-dos (C35) over [TasksClient]. The list of lists is remembered for a minute, so a turn with
 * several tool calls asks once; the tasks themselves are always read fresh.
 */
class GoogleTaskSource(
    private val accountOf: () -> Account,
    private val client: TasksClient,
    private val nowMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : TaskSource {
    override val account: Account get() = accountOf()

    private val lock = Mutex()
    @kotlin.concurrent.Volatile private var cached: List<TaskList>? = null
    @kotlin.concurrent.Volatile private var fetchedAtMs = 0L

    override suspend fun lists(): Outcome<List<TaskList>> = lock.withLock {
        cached?.takeIf { nowMs() - fetchedAtMs < CACHE_MS }?.let { return@withLock Outcome.Success(it) }
        client.lists().also { if (it is Outcome.Success) { cached = it.value; fetchedAtMs = nowMs() } }
    }

    override suspend fun tasks(listId: String, includeDone: Boolean): Outcome<List<TaskItem>> = client.tasks(listId, includeDone)

    override suspend fun insert(listId: String, title: String, notes: String?, due: LocalDate?, parent: String?) =
        client.insert(listId, title, notes, due, parent)

    override suspend fun patch(listId: String, taskId: String, patch: TaskPatch) = client.patch(listId, taskId, patch)

    override suspend fun complete(listId: String, taskId: String, done: Boolean) = client.complete(listId, taskId, done)

    override suspend fun move(listId: String, taskId: String, parent: String?, previous: String?) = client.move(listId, taskId, parent, previous)

    override suspend fun delete(listId: String, taskId: String) = client.delete(listId, taskId)

    companion object {
        const val CACHE_MS = 60_000L
    }
}
