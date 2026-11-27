package com.edith.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import com.edith.app.accounts.ui.CalendarScreen
import com.edith.app.accounts.ui.MailScreen
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
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.edith.app.alerts.AlertTaps
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.edith.app.chat.ui.ChatScreen
import com.edith.app.home.ui.HomeScreen
import com.edith.app.printer.ui.PrinterScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edith.app.ui.components.VoiceBanner
import com.edith.app.ui.components.VoiceBannerViewModel
import com.edith.app.ui.settings.SettingsScreen

/** The route of the Printer page (C39). */
private const val PRINTER_ROUTE = "printer"

private val Destination.icon: ImageVector
    get() = when (this) {
        Destination.Chat -> Icons.AutoMirrored.Filled.Chat
        Destination.Calendar -> Icons.Filled.CalendarMonth
        Destination.Mail -> Icons.Filled.Email
        Destination.Home -> Icons.Filled.Home
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

    // C33: a tapped camera alert brings the Home tab forward (the tab then opens its Clips).
    val openClips by AlertTaps.openClips.collectAsStateWithLifecycle()
    LaunchedEffect(openClips) {
        if (openClips) {
            navController.navigate(Destination.Home.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

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
            composable(Destination.Chat.route) {
                ChatWithVoiceBanner(onOpenSettings = {
                    navController.navigate(Destination.Settings.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                    }
                })
            }
            composable(Destination.Calendar.route) {
                CalendarScreen(onOpenSettings = {
                    navController.navigate(Destination.Settings.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                    }
                })
            }
            composable(Destination.Mail.route) {
                MailScreen(onOpenSettings = {
                    navController.navigate(Destination.Settings.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                    }
                })
            }
            composable(Destination.Home.route) {
                HomeScreen(
                    onOpenSettings = {
                        navController.navigate(Destination.Settings.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                        }
                    },
                    onOpenPrinter = { navController.navigate(PRINTER_ROUTE) { launchSingleTop = true } },
                )
            }
            // The Printer page (C39): reached from the printer tile on Home, not a bottom-bar tab. Back returns to Home.
            composable(PRINTER_ROUTE) {
                PrinterScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPairing = {
                        navController.navigate(Destination.Settings.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Destination.Settings.route) { SettingsScreen() }
        }
    }
}

/** The Chat tab, with the "I can't speak yet" banner above it when nothing can speak (C36 §5). */
@Composable
private fun ChatWithVoiceBanner(onOpenSettings: () -> Unit, banner: VoiceBannerViewModel = viewModel(factory = VoiceBannerViewModel.Factory)) {
    val showBanner by banner.visible.collectAsStateWithLifecycle()
    Column {
        if (showBanner) VoiceBanner(onOpenSettings = onOpenSettings, onDismiss = banner::dismiss, modifier = Modifier.statusBarsPadding())
        // The banner sits under the status bar, so the chat's own top bar must not add that space again.
        Box(if (showBanner) Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars) else Modifier.weight(1f)) { ChatScreen() }
    }
}
