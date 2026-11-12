package com.edith.app.ui.navigation

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CalendarMonth
import com.edith.app.accounts.ui.CalendarScreen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.edith.app.chat.ui.ChatScreen
import com.edith.app.ui.settings.SettingsScreen

private val Destination.icon: ImageVector
    get() = when (this) {
        Destination.Chat -> Icons.AutoMirrored.Filled.Chat
        Destination.Calendar -> Icons.Filled.CalendarMonth
        Destination.Settings -> Icons.Filled.Settings
    }

/**
 * App shell: bottom navigation bar plus one placeholder screen per destination (filled in by later chunks).
 * [banner] is shown above every screen (e.g. the crash banner); it handles the status-bar inset itself.
 */
@Composable
fun EdithNavHost(banner: @Composable () -> Unit = {}) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        topBar = banner,
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.start.route,
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
        ) {
            composable(Destination.Chat.route) { ChatScreen() }
            composable(Destination.Calendar.route) {
                CalendarScreen(onOpenSettings = {
                    navController.navigate(Destination.Settings.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                    }
                })
            }
            composable(Destination.Settings.route) { SettingsScreen() }
        }
    }
}
