package com.edith.app.accounts.tasks

import com.edith.app.accounts.Account
import com.edith.app.core.error.Outcome
import java.time.Instant
import java.time.LocalDate

/** One to-do list in an account (C35): Google Tasks' task list. */
data class TaskList(val id: String, val title: String)

/**
 * One to-do (C35). [due] is a day only: Google Tasks keeps no time of day ([com.edith.app.google.tasks.TaskDue]).
 * [position] is Google's sort key within the list and the parent: it compares as a string (trap 3).
 */
data class TaskItem(
    val id: String,
    val listId: String,
    val title: String,
    val notes: String? = null,
    val due: LocalDate? = null,
    val done: Boolean = false,
    val completedAt: Instant? = null,
    /** The task this is a subtask of. */
    val parent: String? = null,
    val position: String = "",
    val accountId: String = "",
)

/** What to change on a task: only the non-null fields are sent; an empty [notes] or [clearDue] clears that field. */
data class TaskPatch(
    val title: String? = null,
    val notes: String? = null,
    val due: LocalDate? = null,
    val clearDue: Boolean = false,
) {
    val isEmpty: Boolean get() = title == null && notes == null && due == null && !clearDue
}

/**
 * Whether Edith may use an account's to-dos (C35 §2). Separate from the account's state, so calendar and email keep
 * working while the Tasks scope is missing. [Unknown] until a Tasks call finds out: Edith never asks every account
 * at startup (trap 2).
 */
enum class TasksAccess { Unknown, Granted, NeedsPermission }

/** One account's to-dos (C35 §1): Google Tasks now; Microsoft To Do or iCloud Reminders could be later chunks. */
interface TaskSource {
    val account: Account
    /** The account's lists, Google's default ("My Tasks") first. */
    suspend fun lists(): Outcome<List<TaskList>>
    /** A list's tasks in Google's order ([TaskOrder.sort]); done ones only when [includeDone]. */
    suspend fun tasks(listId: String, includeDone: Boolean = false): Outcome<List<TaskItem>>
    /** Adds a task at the top of [listId] (under [parent] when given). */
    suspend fun insert(listId: String, title: String, notes: String?, due: LocalDate?, parent: String? = null): Outcome<TaskItem>
    suspend fun patch(listId: String, taskId: String, patch: TaskPatch): Outcome<TaskItem>
    /** Ticks a task off ([done]) or marks it not done. */
    suspend fun complete(listId: String, taskId: String, done: Boolean): Outcome<TaskItem>
    /** Puts [taskId] after [previous] among [parent]'s children (the top level without one); first without [previous]. */
    suspend fun move(listId: String, taskId: String, parent: String?, previous: String?): Outcome<TaskItem>
    suspend fun delete(listId: String, taskId: String): Outcome<Unit>
}

/** Google's order for a list's tasks, as Google's own apps show them (Jarvis's `sortTasks`). */
object TaskOrder {
    /**
     * Open tasks by [TaskItem.position] with each subtask right after its parent, then done ones, the most recently
     * ticked off first. A subtask whose parent isn't open is shown at the top level.
     */
    fun sort(tasks: List<TaskItem>): List<TaskItem> {
        val open = tasks.filter { !it.done }
        val ids = open.map { it.id }.toSet()
        val top = open.filter { it.parent == null || it.parent !in ids }.sortedBy { it.position }
        val out = mutableListOf<TaskItem>()
        for (t in top) {
            out += t
            out += open.filter { it.parent == t.id }.sortedBy { it.position }
        }
        return out + tasks.filter { it.done }.sortedByDescending { it.completedAt ?: Instant.EPOCH }
    }

    /**
     * The open tasks [task] can be moved among: the ones with the same parent, in order (including [task]). Null for
     * a subtask whose parent isn't open (shown at the top level, but Google keeps it under that parent).
     */
    fun siblingsOf(tasks: List<TaskItem>, task: TaskItem): List<TaskItem>? {
        val open = tasks.filter { !it.done }
        if (task.parent != null && open.none { it.id == task.parent }) return null
        return open.filter { it.parent == task.parent }.sortedBy { it.position }
    }
}
