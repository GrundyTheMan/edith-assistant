package com.edith.app.printer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edith.app.di.viewModelFactory
import kotlinx.io.asSource
import kotlinx.io.buffered

/** The Printer page's view model, made from the app's container (the shared class has no factory: the iPhone makes it its own way). */
val PrinterViewModelFactory = viewModelFactory { c, _ -> PrinterViewModel(c.printerSession, c.printerPrefs, c.clock) }

/** Android's Printer page (C39 part 8): the shared [PrinterContent] with Android's file picker (.gcode has no media type, so every file is offered) and 12/24-hour clock. It asks Jarvis only while it is on screen. */
@Composable
internal fun PrinterScreen(onBack: () -> Unit, onOpenPairing: () -> Unit, vm: PrinterViewModel = viewModel(factory = PrinterViewModelFactory)) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val controls by vm.controls.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.attach() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.detach() }
    val context = LocalContext.current
    val is24Hour = android.text.format.DateFormat.is24HourFormat(context)
    // .gcode has no media type of its own, so the picker shows every file; the name and size are checked after (PrinterUpload.problem).
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val picked = UploadPick.describe(context.contentResolver, uri)
            vm.data.chooseUpload(picked.name, picked.size) { context.contentResolver.openInputStream(uri)?.asSource()?.buffered() }
        }
    }
    PrinterContent(ui, is24Hour, vm.pageActions(onBack, onOpenPairing, pickUpload = { picker.launch(arrayOf("*/*")) }), cards, controls)
}
