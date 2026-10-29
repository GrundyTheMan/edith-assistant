package com.edith.app.network

import com.edith.app.data.calendar.GoogleRepository
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Orchestrates the actions between the AI and the system's capabilities.
 */
class ToolExecutor(
    private val googleRepository: GoogleRepository,
    private val account: GoogleSignInAccount?,
    private val webSearchService: WebSearchService,
    private val audioService: AudioService,
    private val weatherService: WeatherService
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
                val startTime = Date() // Should parse from string if possible
                val endTime = Date()   // Should parse from string if possible
                val location = toolCall.arguments["location"] ?: ""
                googleRepository.addEvent(title, description, startTime, endTime, location).toString()
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
                audioService.transcribe(java.io.File(fileUri))
            }
            "get_weather" -> {
                val location = toolCall.arguments["location"] ?: ""
                val weather = weatherService.getWeather(location)
                if (weather != null) {
                    "The weather in $location is currently ${weather.temp_c}°C with ${weather.description}."
                } else {
                    "I'm sorry, I couldn't retrieve the weather for $location."
                }
            }
            else -> "Error: Unknown tool ${toolCall.function}"
        }
    }
}
