package com.edith.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.edith.app.data.calendar.CalendarEvent
import com.edith.app.data.calendar.GoogleRepository
import com.edith.app.network.GoogleApiService
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CalendarScreen(account: GoogleSignInAccount) {
    val apiService = GoogleApiService()
    val repository = GoogleRepository(apiService, account)
    
    val events by androidx.compose.runtime.produceState(initialValue = emptyList<CalendarEvent>()) {
        value = repository.getEvents()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Calendar", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        
        LazyColumn {
            items(events) { event ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = event.title, style = MaterialTheme.typography.titleLarge)
                        Text(text = event.description ?: "")
                    }
                }
            }
        }
    }
}
