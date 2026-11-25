package com.edith.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edith.app.printer.ui.PrinterContent
import com.edith.app.printer.ui.PrinterViewModel
import com.edith.app.printer.ui.pageActions

/**
 * The iPhone's Printer page (K3.4, K4.15): the shared [PrinterContent] over its view model. It asks Jarvis only while it is on screen, so the view model
 * attaches when the page opens and detaches when it closes. Choosing a .gcode file to upload is not on the iPhone yet (the Files picker is audio-only
 * for now), so the Upload button does nothing here.
 */
@Composable
fun IosPrinterScreen(vm: PrinterViewModel, onBack: () -> Unit, openPairing: () -> Unit, is24Hour: Boolean = false) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val controls by vm.controls.collectAsStateWithLifecycle()
    DisposableEffect(vm) {
        vm.attach()
        onDispose { vm.detach() }
    }
    PrinterContent(ui, is24Hour, vm.pageActions(onBack, openPairing), cards, controls)
}
