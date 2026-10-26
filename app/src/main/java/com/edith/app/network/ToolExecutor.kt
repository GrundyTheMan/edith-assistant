package com.edith.app.network

import com.edith.app.data.calendar.GoogleRepository
import com.google.android.gms.auth.api.signin.GoogleSignInAccount

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
