package com.edith.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.edith.app.data.calendar.CalendarEvent
import com.edith.app.data.calendar.GoogleRepository
import com.edith.app.network.NetworkClient
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(account: GoogleSignInAccount) {
    val coroutineScope = rememberCoroutineScope()
    var events by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // Dependency setup (Ideally this would be injected via Hilt/Koin)
    val googleRepo = remember { GoogleRepository(NetworkClient.getGoogleApi(), account) }
    
    // Using a simple state flow hook for loading/events
    LaunchedEffect(account) {
        isLoading = true
        try {
            // Fetching events from the repository
            events = googleRepo.getEvents()
        } catch (e: Exception) {
            // Handle error gracefully
            println("Error fetching calendar events: $e")
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Edith's Schedule 🗓️") },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.primary)
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp)
        ) {
            if (isLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(20.dp))
                Text("Edith is consulting your calendar... please hold the line.")
            } else if (events.isEmpty()) {
                Text("Edith hasn't found any entries yet. Let's plan something exciting!")
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(events) { event ->
                        CalendarEventCard(event = event)
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun CalendarEventCard(event: CalendarEvent) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Description: ${event.description}",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "When: ${event.start} to ${event.end}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}