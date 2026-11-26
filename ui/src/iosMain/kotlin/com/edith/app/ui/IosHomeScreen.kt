package com.edith.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edith.app.alerts.Alert
import com.edith.app.alerts.ui.CatchUpCard
import com.edith.app.home.ui.HomeContent
import com.edith.app.home.ui.HomeOverlays
import com.edith.app.home.ui.HomeViewModel
import com.edith.app.home.ui.tabActions

/**
 * The iPhone's Home tab (K3.3, K4.15, K4.16): the shared [HomeContent] over its view model, with Apple's player for clips and Blink live views (K4.17), and **While you were away** on top (what the
 * cameras reported since the app was last opened, until dismissed). Settings (the connect button) is the Settings tab, where the services' sign-in forms are. The printer
 * tile's Details opens the Printer page when a Jarvis is paired.
 */
@Composable
fun IosHomeScreen(
    vm: HomeViewModel,
    openMore: () -> Unit,
    openPrinter: () -> Unit = {},
    catchUp: List<Alert> = emptyList(),
    onDismissCatchUp: () -> Unit = {},
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    // Each screen keeps clear of the status bar (the clock and the camera cut-out) itself: the tab scaffold passes no top inset (MainViewController).
    Column(Modifier.statusBarsPadding()) {
        CatchUpCard(catchUp, onOpenClips = { vm.openClips(); onDismissCatchUp() }, onDismiss = onDismissCatchUp)
        Box(Modifier.weight(1f)) {
            HomeContent(ui, vm.tabActions(openSettings = openMore, openPrinter = openPrinter), HomeOverlays(clipPlayer = { url, headers -> IosClipPlayer(url, headers) }, live = { live, onClose -> IosLiveDialog(live, onClose) }))
        }
    }
}
