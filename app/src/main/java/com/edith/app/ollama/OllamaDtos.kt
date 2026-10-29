package com.edith.app.ollama

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** C03: Ollama client DTOs mirroring 03-ollama-reference exactly. */
object OllamaDtos {
    /** JSON settings for every Ollama request and response. */
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** Sent as `num_ctx` on every request: a different value makes Ollama reload the model (lessons L9). */
    const val CONTEXT_LENGTH = 16384
}

@Serializable data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean,
    val think: JsonElement = ThinkPolicy.forModel(model),
    val options: OllamaOptions = OllamaOptions(),
    val tools: List<OllamaTool>? = null,
    @SerialName("keep_alive") val keepAlive: String? = null,
)

@Serializable data class OllamaOptions(@SerialName("num_ctx") val numCtx: Int = OllamaDtos.CONTEXT_LENGTH)

@Serializable data class OllamaMessage(
    val role: String,
    val content: String = "",
    val images: List<String>? = null,
    @SerialName("tool_calls") val toolCalls: List<OllamaToolCall>? = null,
    @SerialName("tool_name") val toolName: String? = null,
)

@Serializable data class OllamaToolCall(val id: String? = null, val function: OllamaFunctionCall)

@Serializable data class OllamaFunctionCall(
    val name: String,
    @SerialName("arguments") val arguments: JsonObject = JsonObject(emptyMap()),
    val index: Int? = null,
)

@Serializable data class OllamaTool(val type: String = "function", val function: OllamaFunctionSpec)

@Serializable data class OllamaFunctionSpec(
    val name: String,
    val description: String,
    @SerialName("parameters") val parameters: JsonObject = JsonObject(emptyMap()),
)

@Serializable data class OllamaChatChunk(
    val model: String = "",
    val message: OllamaMessage? = null,
    val done: Boolean = false,
    @SerialName("done_reason") val doneReason: String? = null,
    @SerialName("total_duration") val totalDuration: Long? = null,
    @SerialName("eval_count") val evalCount: Int? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
    val error: String? = null,
)

@Serializable data class OllamaTags(val models: List<OllamaModelTag>)

@Serializable data class OllamaModelTag(
    val name: String,
    val size: Long? = null,
    @SerialName("modified_at") val modifiedAt: String? = null,
)

@Serializable data class OllamaShow(
    @SerialName("capabilities") val capabilities: List<String> = emptyList(),
    @SerialName("model_info") val modelInfo: JsonObject? = null,
)

object ThinkPolicy {
    fun forModel(model: String): JsonElement = if (model.startsWith("gpt-oss")) JsonPrimitive("low") else JsonPrimitive(false)
}
