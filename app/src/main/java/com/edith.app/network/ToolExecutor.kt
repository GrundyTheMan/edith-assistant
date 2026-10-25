package com.edith.app.network

import com.edith.app.data.calendar.CalendarEvent
import com.edith.app.data.calendar.GoogleRepository
import com.edith.app.network.GoogleApiService
import com.edith.app.network.SearchResult
import com.edith.app.network.WebSearchService
import com.edith.app.network.AudioService
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

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

interface GoogleApiService {
    @GET("calendar/v3/calendars/primary/events")
    suspend fun getCalendarEvents(
        @Query("timeMin") timeMin: String,
        @Query("timeMax") timeMax: String,
        @Header("Authorization") token: String
    ): List<CalendarEvent>

    @GET("gmail/v1/users/{userId}/messages")
    suspend fun getEmails(
        @Query("maxResults") maxResults: Int,
        @Query("labelId") labelId: String,
        @Header("Authorization") token: String
    ): List<EmailMessage>
}

data class EmailMessage(
    val id: String,
    val threadId: String,
    val snippet: String,
    val from: String
)

/**
 * Handles the transmission of audio files to the Ollama server for transcription.
 */
class AudioService(private val configManager: com.edith.app.utils.ConfigManager) {
    
    suspend fun transcribe(fileUri: String): String = withContext(Dispatchers.IO) {
        // For a real implementation, we would send the file to an Ollama endpoint 
        // that supports Whisper or a similar transcription model.
        // Since Ollama's standard API is text-based, this might require a custom 
        // endpoint on your server or a multi-modal model.
        
        // Placeholder: Returning a mock transcription.
        return "This is a mock transcription of the recording. Edith is listening..."
    }
}

/**
 * Orchestrates the actions between the AI and the system's capabilities.
 */
class ToolExecutor(
    private val googleRepository: GoogleRepository,
    private val account: GoogleSignInAccount,
    private val webSearchService: WebSearchService,
    private val audioService: AudioService
) {
    suspend fun execute(toolCall: ToolCall): String {
        return when (toolCall.function) {
            "get_calendar_events" -> {
                val events = googleRepository.getEvents()
                events.joinToString { "${it.title} (${it.description})" }
            }
            "add_calendar_event" -> {
                val title = toolCall.arguments["title"] ?: ""
                val description = toolCall.arguments["description"] ?: ""
                googleRepository.addEvent(title, description, java.util.Date(), java.util.Date()).toString()
            }
            "get_emails" -> {
                val emails = googleRepository.getEmails()
                emails.joinToString { "${it.from}: ${it.snippet}" }
            }
            "web_search" -> {
                val query = toolCall.arguments["query"] ?: ""
                val results = webSearchService.search(query)
                results.joinToString { "${it.title}: ${it.snippet} (${it.link})" }
            }
            "transcribe_recording" -> {
                val fileUri = toolCall.arguments["file_uri"] ?: ""
                audioService.transcribe(fileUri)
            }
            else -> "Error: Unknown tool ${toolCall.function}"
        }
    }
}
