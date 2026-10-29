package com.edith.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edith.app.data.calendar.CalendarEvent
import com.edith.app.data.calendar.GoogleRepository
import com.edith.app.network.NetworkClient
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CalendarViewModel(account: GoogleSignInAccount?) : ViewModel() {
    private val apiService = NetworkClient.getGoogleApi()
    private val repository = GoogleRepository(apiService, account)
    
    private val _events = MutableStateFlow<List<CalendarEvent>>(emptyList())
    val events: StateFlow<List<CalendarEvent>> = _events

    init {
        loadEvents()
    }

    private fun loadEvents() {
        viewModelScope.launch {
            _events.value = repository.getEvents()
        }
    }

    fun addEvent(title: String, description: String) {
        viewModelScope.launch {
            repository.addEvent(title, description, java.util.Date(), java.util.Date(), "")
            loadEvents()
        }
    }
}
