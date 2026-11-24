package com.edith.app.google.tasks

import com.edith.app.accounts.tasks.TaskItem
import com.edith.app.accounts.tasks.TaskList
import com.edith.app.accounts.tasks.TaskOrder
import com.edith.app.accounts.tasks.TaskPatch
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.error.AppError
import com.edith.app.core.error.Outcome
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.google.GoogleAccountAuth
import com.edith.app.google.GoogleCall
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.ParametersBuilder
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * One Google account's to-dos over the Google Tasks REST API (C35 §1), with a token for the Tasks scope from its
 * [GoogleAccountAuth] for each call. Due dates go through [TaskDue] only.
 */
class TasksClient(
    private val http: Lazy<HttpClient>,
    private val auth: GoogleAccountAuth,
    private val dispatchers: AppDispatchers,
    private val baseUrl: String = BASE_URL,
    /** Stamped on every task, so a change goes to the account it came from (C22). */
    private val accountId: String = "",
) {
    /** Every list, in Google's order (the default list first). */
    suspend fun lists(): Outcome<List<TaskList>> = paged { token, page ->
        get(token, url("users/@me/lists") {
            append("maxResults", "100")
            page?.let { append("pageToken", it) }
        }) { body ->
            val dto = json.decodeFromString<ListsDto>(body)
            dto.items.map { TaskList(it.id, it.title?.trim()?.takeIf { t -> t.isNotEmpty() } ?: "(untitled list)") } to dto.nextPageToken
        }
    }

    /** A list's tasks in Google's order; done ones only when [includeDone]. Deleted and empty tasks are left out. */
    suspend fun tasks(listId: String, includeDone: Boolean): Outcome<List<TaskItem>> = when (
        val r = paged { token, page ->
            get(token, url("lists/${enc(listId)}/tasks") {
                append("maxResults", "100")
                // showCompleted is true by default; tasks cleared in Google's own apps are "hidden" (Jarvis).
                append("showCompleted", includeDone.toString())
                append("showHidden", includeDone.toString())
                page?.let { append("pageToken", it) }
            }) { body ->
                val dto = json.decodeFromString<TasksDto>(body)
                dto.items.mapNotNull { it.toTask(listId) } to dto.nextPageToken
            }
        }
    ) {
        is Outcome.Success -> Outcome.Success(TaskOrder.sort(r.value))
        is Outcome.Failure -> r
    }

    /** A new task, at the top of the list (or of [parent]'s subtasks), as in Google's own apps. */
    suspend fun insert(listId: String, title: String, notes: String?, due: LocalDate?, parent: String?): Outcome<TaskItem> {
        val body = buildJsonObject {
            put("title", title.trim())
            notes?.trim()?.takeIf { it.isNotEmpty() }?.let { put("notes", it) }
            due?.let { put("due", TaskDue.toGoogle(it)) }
        }
        return one { token ->
            send(token, "POST", url("lists/${enc(listId)}/tasks") { parent?.let { append("parent", it) } }, body.toString()) { parseTask(it, listId) }
        }
    }

    /** Only the changed fields. An empty `notes` clears the notes; [TaskPatch.clearDue] sends `due: null`. */
    suspend fun patch(listId: String, taskId: String, patch: TaskPatch): Outcome<TaskItem> {
        val body = buildJsonObject {
            patch.title?.let { put("title", it.trim()) }
            patch.notes?.let { n -> if (n.isBlank()) put("notes", JsonNull) else put("notes", n.trim()) }
            when {
                patch.clearDue -> put("due", JsonNull)
                patch.due != null -> put("due", TaskDue.toGoogle(requireNotNull(patch.due)))
            }
        }
        return one { token -> send(token, "PATCH", taskUrl(listId, taskId), body.toString()) { parseTask(it, listId) } }
    }

    /** Ticks a task off, or reopens it (Google fills in `completed` itself; reopening must clear it). */
    suspend fun complete(listId: String, taskId: String, done: Boolean): Outcome<TaskItem> {
        val body = if (done) JsonObject(mapOf("status" to JsonPrimitive("completed")))
        else JsonObject(mapOf("status" to JsonPrimitive("needsAction"), "completed" to JsonNull))
        return one { token -> send(token, "PATCH", taskUrl(listId, taskId), body.toString()) { parseTask(it, listId) } }
    }

    /** `POST …/move`: after [previous] among [parent]'s children, first without it. A subtask must name its parent. */
    suspend fun move(listId: String, taskId: String, parent: String?, previous: String?): Outcome<TaskItem> = one { token ->
        val u = url("lists/${enc(listId)}/tasks/${enc(taskId)}/move") {
            parent?.let { append("parent", it) }
            previous?.let { append("previous", it) }
        }
        send(token, "POST", u, "") { parseTask(it, listId) }
    }

    suspend fun delete(listId: String, taskId: String): Outcome<Unit> = call { token ->
        send(token, "DELETE", taskUrl(listId, taskId), null) { }
    }

    // --- HTTP -------------------------------------------------------------------------------------------------

    private fun parseTask(body: String, listId: String): TaskItem? = json.decodeFromString<TaskDto>(body).toTask(listId, keepEmpty = true)

    private suspend fun one(block: suspend (String) -> GoogleCall<TaskItem?>): Outcome<TaskItem> = when (val r = call(block)) {
        is Outcome.Success -> r.value?.let { Outcome.Success(it) } ?: Outcome.Failure(AppError.ToolFailed(TOOL, "Google Tasks sent a task Edith couldn't read."))
        is Outcome.Failure -> r
    }

    /** Every page of a listing, each page asked with a fresh token if needed. */
    private suspend fun <T> paged(page: suspend (token: String, pageToken: String?) -> GoogleCall<Pair<List<T>, String?>>): Outcome<List<T>> {
        val out = mutableListOf<T>()
        var next: String? = null
        var pages = 0
        do {
            val pt = next
            when (val r = call { token -> page(token, pt) }) {
                is Outcome.Success -> { out += r.value.first; next = r.value.second }
                is Outcome.Failure -> return r
            }
        } while (next != null && ++pages < MAX_PAGES)
        return Outcome.Success(out)
    }

    private suspend fun <T> call(block: suspend (String) -> GoogleCall<T>): Outcome<T> =
        auth.tasksAuthorized { token -> withContext(dispatchers.io) { block(token) } }

    private suspend fun <T> get(token: String, url: Url, parse: (String) -> T): GoogleCall<T> = execute(token, HttpMethod.Get, url, null, parse)

    private suspend fun <T> send(token: String, method: String, url: Url, body: String?, parse: (String) -> T): GoogleCall<T> =
        execute(token, HttpMethod.parse(method), url, body, parse)

    private suspend fun <T> execute(token: String, method: HttpMethod, url: Url, body: String?, parse: (String) -> T): GoogleCall<T> = try {
        val response = http.value.request(url) {
            this.method = method
            header(HttpHeaders.Authorization, "Bearer $token")
            if (body != null) setBody(TextContent(body, JSON_TYPE))
        }
        handle(response.status.value, response.bodyAsText(), parse)
    } catch (e: java.io.IOException) {
        Log.w(LogTag.Google, "Tasks request failed", e)
        GoogleCall.Failed(AppError.ToolFailed(TOOL, "Couldn't reach Google Tasks. Check the phone's connection."))
    } catch (e: kotlinx.serialization.SerializationException) {
        Log.w(LogTag.Google, "Couldn't read Google Tasks' answer", e)
        GoogleCall.Failed(AppError.ToolFailed(TOOL, "Google Tasks sent something Edith couldn't read."))
    }

    private fun <T> handle(code: Int, body: String, parse: (String) -> T): GoogleCall<T> {
        return when {
            code in 200..299 -> GoogleCall.Ok(parse(body))
            code == 401 -> GoogleCall.Unauthorized
            apiDisabled(code, body) -> {
                Log.w(LogTag.Google, "Tasks: the Google Tasks API is turned off in the Cloud project")
                GoogleCall.Failed(AppError.ToolFailed(TOOL, API_DISABLED))
            }
            code == 403 && (body.contains("insufficientPermissions") || body.contains("ACCESS_TOKEN_SCOPE_INSUFFICIENT")) -> GoogleCall.MissingScope
            code == 404 || code == 410 -> GoogleCall.Failed(AppError.ToolFailed(TOOL, GONE))
            else -> {
                Log.w(LogTag.Google, "Tasks HTTP ${code}")
                GoogleCall.Failed(AppError.ToolFailed(TOOL, "Google Tasks said no (HTTP ${code})."))
            }
        }
    }

    private fun url(path: String, query: ParametersBuilder.() -> Unit): Url =
        URLBuilder(baseUrl.trimEnd('/') + "/" + path).apply { parameters.query() }.build()

    private fun taskUrl(listId: String, taskId: String) = url("lists/${enc(listId)}/tasks/${enc(taskId)}") {}

    private fun enc(id: String) = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    private fun TaskDto.toTask(listId: String, keepEmpty: Boolean = false): TaskItem? {
        if (deleted == true) return null
        val t = title?.trim().orEmpty()
        val n = notes?.trim()?.takeIf { it.isNotEmpty() }
        // Google's apps leave empty tasks behind when a new row is abandoned (Jarvis's mapTask).
        if (!keepEmpty && t.isEmpty() && n == null) return null
        return TaskItem(
            id = id ?: return null,
            listId = listId,
            title = t.ifEmpty { "(no title)" },
            notes = n,
            due = TaskDue.fromGoogle(due),
            done = status == "completed",
            completedAt = completed?.let { runCatching { Instant.parse(it) }.getOrNull() },
            parent = parent?.takeIf { it.isNotEmpty() },
            position = position.orEmpty(),
            accountId = accountId,
        )
    }

    companion object {
        const val BASE_URL = "https://tasks.googleapis.com/tasks/v1"
        const val TOOL = "tasks"
        const val API_DISABLED = "The Google Tasks API isn't turned on in your Google Cloud project — see docs/google-setup.md."
        const val GONE = "That task or list isn't in Google Tasks any more; it may have been changed elsewhere."
        private const val MAX_PAGES = 20
        private val JSON_TYPE = ContentType.Application.Json.withCharset(Charsets.UTF_8)
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        /** Google's "API not enabled" answer (`accessNotConfigured` / `SERVICE_DISABLED`): setup, not a sign-in problem. */
        fun apiDisabled(code: Int, body: String): Boolean = code == 403 && (
            body.contains("accessNotConfigured") || body.contains("SERVICE_DISABLED") ||
                body.contains("has not been used in project") || body.contains("it is disabled")
            )
    }
}

@Serializable private class ListsDto(val items: List<ListDto> = emptyList(), val nextPageToken: String? = null)
@Serializable private class ListDto(val id: String, val title: String? = null)
@Serializable private class TasksDto(val items: List<TaskDto> = emptyList(), val nextPageToken: String? = null)
@Serializable private class TaskDto(
    val id: String? = null,
    val title: String? = null,
    val notes: String? = null,
    val due: String? = null,
    val status: String? = null,
    val completed: String? = null,
    val parent: String? = null,
    val position: String? = null,
    val deleted: Boolean? = null,
)
