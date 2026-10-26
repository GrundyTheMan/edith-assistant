package com.edith.app.network

import okhttp3.ResponseBody
import retrofit2.http.Body
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

// Ollama's OpenAI-compatible text-to-speech request
data class SpeechRequest(
    val model: String,
    val input: String,
    val response_format: String = "wav"
)

interface OllamaApi {
    @POST("/api/chat")
    suspend fun generateChat(@Body request: ChatRequest): ChatResponse

    @POST("/v1/audio/speech")
    suspend fun generateSpeech(@Body request: SpeechRequest): ResponseBody
}
