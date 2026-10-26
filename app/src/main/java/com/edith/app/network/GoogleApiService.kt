package com.edith.app.network

import com.edith.app.data.calendar.CalendarEvent
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface GoogleApiService {
    @GET("calendar/v3/calendars/primary/events")
    suspend fun getCalendarEvents(
        @Query("timeMin") timeMin: String,
        @Query("timeMax") timeMax: String,
        @Header("Authorization") token: String
    ): List<CalendarEvent>

    @GET("gmail/v1/users/me/messages")
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
