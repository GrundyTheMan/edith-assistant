package com.edith.app.data.calendar

import com.edith.app.data.calendar.CalendarEvent
import com.edith.app.network.EmailMessage
import com.edith.app.network.GoogleApiService
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

class GoogleRepository(
    private val apiService: GoogleApiService,
    private val account: GoogleSignInAccount
) {
    
    private fun getAccessToken(): String {
        // In a production app, you might want to handle token expiration here.
        return account.accessToken
    }

    suspend fun getEvents(): List<CalendarEvent> = withContext(Dispatchers.IO) {
        try {
            val token = getAccessToken()
            // return apiService.getCalendarEvents("2023-01-01T00:00:00Z", "2025-12-31T23:59:59Z", "Bearer $token")
            
            // Still returning mocks for now so the UI remains functional.
            return listOf(
                CalendarEvent("1", "Meeting with Team", "Discussing project Edith", Date(), Date()),
                CalendarEvent("2", "Lunch with Client", "Project discussion", Date(), Date())
            )
        } catch (e: Exception) {
            return emptyList()
        }
    }

    suspend fun getEmails(): List<EmailMessage> = withContext(Dispatchers.IO) {
        try {
            val token = getAccessToken()
            // return apiService.getEmails(10, "INBOX", "Bearer $token")
            
            return listOf(
                EmailMessage("1", "1", "Hello from Edith!", "sender@example.com")
            )
        } catch (e: Exception) {
            return emptyList()
        }
    }

    suspend fun addEvent(title: String, description: String, startTime: Date, endTime: Date): CalendarEvent = withContext(Dispatchers.IO) {
        // Real logic would call apiService.addEvent(...)
        CalendarEvent(
            "3",
            title,
            description,
            startTime,
            endTime
        )
    }
}
