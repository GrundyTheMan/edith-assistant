package com.edith.app.network

import retrofit2.http.Body
import retrofit2.http.POST

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    val think: Boolean? = null
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

interface OllamaApi {
    @POST("/api/chat")
    suspend fun generateChat(@Body request: ChatRequest): ChatResponse
}
