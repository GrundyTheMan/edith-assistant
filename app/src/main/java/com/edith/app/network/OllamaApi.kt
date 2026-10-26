package com.edith.app.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

// Every request uses the same context size: Ollama reloads a model when it changes, and the
// model's own default (up to 262K for gemma4) is so large Ollama unloads the voice model to fit it
private const val CONTEXT_LENGTH = 16384

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    // Hidden reasoning before replying; off by default, as it adds several seconds per reply
    val think: Boolean = false,
    val options: Map<String, Any> = mapOf("num_ctx" to CONTEXT_LENGTH)
)

data class ChatMessage(
    val role: String,
    val content: String,
    // Base64 media for multimodal models; Ollama takes audio (WAV/MP3) in this field too
    val images: List<String>? = null
)

data class ChatResponse(
    val model: String,
    val message: ChatMessage,
    val done: Boolean,
    val tool_calls: List<ToolCall>? = null
)

data class ToolCall(
    val function: String,
    val arguments: Map<String, String>
)

data class ModelList(val models: List<ModelTag>)

data class ModelTag(val name: String)

data class ModelDetailsRequest(val model: String)

// capabilities is e.g. ["completion", "vision", "audio", "tools", "thinking"]
data class ModelDetails(val capabilities: List<String>? = null)

interface OllamaApi {
    @POST("/api/chat")
    suspend fun generateChat(@Body request: ChatRequest): ChatResponse

    /** Models installed on the server. */
    @GET("/api/tags")
    suspend fun listModels(): ModelList

    @POST("/api/show")
    suspend fun showModel(@Body request: ModelDetailsRequest): ModelDetails
}
