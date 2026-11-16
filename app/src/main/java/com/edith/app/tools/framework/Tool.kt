package com.edith.app.tools.framework

import com.edith.app.chat.engine.ConfirmationRequest
import com.edith.app.core.dispatchers.AppDispatchers
import com.edith.app.core.time.Clock
import com.edith.app.ollama.OllamaFunctionSpec
import com.edith.app.ollama.OllamaTool
import com.edith.app.settings.Settings
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Something Edith can do or look up during a reply (C08 §1). Only real, working tools are registered — never one
 * that returns placeholder data (L6), and nothing desktop-only (L7).
 */
interface Tool {
    val spec: ToolSpec
    /** Short present-tense label for the thinking bubble, e.g. "Checking the weather…". */
    val statusLabel: String
    /** How the "Used: …" line names it, e.g. "weather". */
    val displayName: String
    /** The status label for this call, e.g. "Reading espn.com…" (default [statusLabel]). */
    fun statusLabelFor(args: ToolArgs): String = statusLabel
    /** An extra line for the system prompt's tool section while this tool is offered (C08 §4). */
    val guidance: String? get() = null
    /** The most of a result the model is sent; longer results are cut (C08 §5). A page's text needs more (C10 §2). */
    val maxResultChars: Int get() = ToolTurnRunner.RESULT_MAX_CHARS
    /**
     * How long [execute] may take, when it needs longer than the loop's usual limit (C35: ranking to-dos is a model
     * call of its own). Null: the usual limit.
     */
    val timeoutMs: Long? get() = null
    /** Integration this tool belongs to (null = built in). Tools of disconnected integrations aren't offered. */
    val integrationId: String? get() = null
    /**
     * Non-null when the call changes something outside the app and needs the owner's OK first (D11). Null when it
     * doesn't, or when [execute] will only explain why it can't go ahead (C22: "ask the owner which account").
     * Suspends because the answer may need a read first (C22: which account has a calendar of that name).
     */
    suspend fun confirmation(args: ToolArgs): ConfirmationRequest? = null
    /** Non-null when this call needs a runtime permission (C11), asked with Android's dialog before [execute]. */
    fun permission(args: ToolArgs): PermissionNeed? = null
    suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult
}

/** A runtime permission ([permission], e.g. `ACCESS_COARSE_LOCATION`) and what the model is told if it's denied. */
data class PermissionNeed(val permission: String, val deniedResult: String)

/** A tool as the model sees it: its name, description and parameters. */
data class ToolSpec(val name: String, val description: String, val parameters: List<ToolParam> = emptyList()) {
    /** Ollama's tool JSON (03-ollama-reference §2): `{"type":"function","function":{name, description, parameters}}`. */
    fun toOllama(): OllamaTool = OllamaTool(
        function = OllamaFunctionSpec(
            name = name,
            description = description,
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", JsonObject(parameters.associate { it.name to it.toJsonSchema() }))
                put("required", JsonArray(parameters.filter { it.required }.map { JsonPrimitive(it.name) }))
            },
        ),
    )
}

/** One parameter of a tool, with the other names the model may use for it. */
data class ToolParam(
    val name: String,
    val type: ParamType,
    val description: String,
    val required: Boolean = false,
    val enumValues: List<String>? = null,
    /** Other keys a model might use for this argument ("place" for "location"); nothing beyond these is guessed. */
    val aliases: List<String> = emptyList(),
) {
    fun toJsonSchema(): JsonObject = buildJsonObject {
        put("type", type.jsonType)
        put("description", if (type == ParamType.DateTime) "$description (ISO-8601 local time, e.g. 2026-10-30T12:00:00)" else description)
        enumValues?.let { values -> put("enum", JsonArray(values.map { JsonPrimitive(it) })) }
    }
}

/** The JSON type of a tool parameter. */
enum class ParamType(val jsonType: String) {
    String("string"), Integer("integer"), Number("number"), Boolean("boolean"),
    /** An ISO-8601 string. */
    DateTime("string"),
}

/** What a tool gives back: text for the model, or an error. */
sealed interface ToolResult {
    /** What the model reads; plain and factual. */
    val text: kotlin.String

    /** [images]: pictures the owner sees with the reply (C25: a camera snapshot); the model sees them only if it can. */
    data class Ok(override val text: kotlin.String, val images: List<ToolImage> = emptyList()) : ToolResult
    /** Says plainly what failed, so the model tells the owner instead of guessing. */
    data class Error(override val text: kotlin.String) : ToolResult
}

/** A JPEG a tool brought back, and the file name it's kept under. */
class ToolImage(val fileName: kotlin.String, val jpeg: ByteArray) {
    override fun toString() = "ToolImage($fileName, ${jpeg.size} bytes)"
}

/** What a running tool is given: the clock, the settings and the dispatchers. */
data class ToolContext(val clock: Clock, val settings: Settings, val dispatchers: AppDispatchers)

/** An outside service tools belong to (Google, later smart-home). Its tools are offered only while it's connected (L6). */
interface Integration {
    val id: kotlin.String
    val status: StateFlow<IntegrationStatus>
    /** Told to the model while this integration isn't connected, so it says so instead of guessing (L6). */
    val notConnectedNote: kotlin.String? get() = null
    /**
     * Told to the model while it's connected (C17: "Google: connected as …"). [tools] are this integration's tools,
     * so the line can say what she can actually do with it, and not promise more (L6).
     */
    fun connectedNote(tools: List<Tool>): kotlin.String? = null
}

/** Whether an integration (Google, the smart home…) is connected, so its tools are offered. */
enum class IntegrationStatus { Connected, NotConnected }
