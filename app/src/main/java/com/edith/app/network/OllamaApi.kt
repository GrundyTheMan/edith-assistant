package com.edith.app.network

import retrofit2.Call
import retrofit2.HttpBody
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false
)

data class ChatMessage(
    val role: String,
    val content: String
)

data class ChatResponse(
    val model: String,
    val message: ChatMessage,
    val done: Boolean
)

interface OllamaApi {
    @POST("/api/chat")
    suspend fun generateChat(@Body request: ChatRequest): ChatResponse
}
