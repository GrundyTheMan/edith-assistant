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
    private val account: GoogleSignInAccount?
) {

    // TODO: Live API calls need an OAuth access token. GoogleSignInAccount doesn't carry one;
    // fetch it with GoogleAuthUtil.getToken(context, account.account!!, "oauth2:<scopes>").

    suspend fun getEvents(): List<CalendarEvent> = withContext(Dispatchers.IO) {
        // TODO: Implement live API call here using token
        listOf(
            CalendarEvent("1", "Meeting with Team", "Discussing project Edith", Date(), Date()),
            CalendarEvent("2", "Lunch with Client", "Project discussion", Date(), Date())
        )
    }

    suspend fun getEmails(): List<EmailMessage> = withContext(Dispatchers.IO) {
        // TODO: Implement live API call here using token
        listOf(
            EmailMessage("1", "1", "Hello from Edith!", "sender@example.com")
        )
    }

    /**
     * Creates a new event in Google Calendar.
     */
    suspend fun addEvent(title: String, description: String, startTime: Date, endTime: Date, location: String): CalendarEvent = withContext(Dispatchers.IO) {
        // NOTE: In a real implementation, this would call apiService.insertEvent(...) 
        // which requires a specific API method call not yet defined.
        // We are mocking the successful creation for now.
        CalendarEvent(
            "3",
            title,
            description,
            startTime,
            endTime
        )
    }
}
