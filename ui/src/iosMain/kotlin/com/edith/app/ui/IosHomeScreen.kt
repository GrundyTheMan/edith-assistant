package com.edith.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edith.app.home.ui.HomeContent
import com.edith.app.home.ui.HomeViewModel
import com.edith.app.home.ui.tabActions

/**
 * The iPhone's Home tab (K3.3, K4.15): the shared [HomeContent] over its view model, with the default overlays, so a clip says it cannot be played here yet
 * and there is no live view. Settings (the connect button) is the More tab, where the services' sign-in forms are. The Printer page is not on the iPhone yet.
 */
@Composable
fun IosHomeScreen(vm: HomeViewModel, openMore: () -> Unit) {
    val ui by vm.state.collectAsStateWithLifecycle()
    HomeContent(ui, vm.tabActions(openSettings = openMore))
}
