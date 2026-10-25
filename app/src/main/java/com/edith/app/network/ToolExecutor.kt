package com.edith.app.network

import com.edith.app.data.calendar.CalendarEvent
import com.edith.app.data.calendar.GoogleRepository
import com.google.android.gms.auth.api.signin.GoogleSignInAccount

data class ToolCall(
    val function: String,
    val arguments: Map<String, String>
)

data class ChatResponse(
    val model: String,
    val message: ChatMessage,
    val done: Boolean,
    val tool_calls: List<ToolCall>? = null
)

/**
 * Orchestrates the actions between the AI and the system's capabilities.
 */
class ToolExecutor(
    private val googleRepository: GoogleRepository,
    private val account: GoogleSignInAccount
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
                // Simple mock for now
                googleRepository.addEvent(title, description, java.util.Date(), java.util.Date()).toString()
            }
            "get_emails" -> {
                val emails = googleRepository.getEmails()
                emails.joinToString { "${it.from}: ${it.snippet}" }
            }
            else -> "Error: Unknown tool ${toolCall.function}"
        }
    }
}
