package com.edith.app.home.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edith.app.alerts.AlertTaps
import com.edith.app.di.viewModelFactory

/** Android's Home tab: the shared [HomeContent] with its view model, the camera alert's tap, and the players only Android has (ExoPlayer, WebRTC). */
@Composable
fun HomeScreen(onOpenSettings: () -> Unit, onOpenPrinter: () -> Unit = {}, vm: HomeViewModel = viewModel(factory = HomeViewModelFactory)) {
    val ui by vm.state.collectAsStateWithLifecycle()
    // C33: arriving from a tapped camera alert opens the Clips list.
    val fromAlert by AlertTaps.openClips.collectAsStateWithLifecycle()
    LaunchedEffect(fromAlert) {
        if (fromAlert) {
            vm.openClips()
            AlertTaps.consume()
        }
    }
    // A live view stops when the app leaves the screen: a camera left streaming drains its battery.
    // A rotation stops and recreates the screen too, but the view model (and the stream in it) lives on: keep the view going then.
    val activity = androidx.activity.compose.LocalActivity.current
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true) vm.stopLive()
    }
    HomeContent(
        ui,
        vm.tabActions(onOpenSettings, onOpenPrinter),
        overlays = HomeOverlays(
            clipPlayer = { url, headers -> ClipPlayer(url, headers) },
            live = { live, onClose -> LiveDialog(live, onClose) },
        ),
    )
}

/** The Home tab's view model, made from the app's container (the shared class has no factory: the iPhone makes it its own way). */
val HomeViewModelFactory = viewModelFactory { c, _ -> HomeViewModel(c.home, c.clock) { c.accounts.jarvisConnection() != null } }
