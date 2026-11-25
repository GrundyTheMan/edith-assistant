@file:OptIn(ExperimentalForeignApi::class)

package com.edith.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edith.app.chat.ui.IosFilePicker
import com.edith.app.printer.ui.PrinterContent
import com.edith.app.printer.ui.PrinterViewModel
import com.edith.app.printer.ui.pageActions
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.currentLocale
import platform.UniformTypeIdentifiers.UTTypeData

/**
 * The iPhone's Printer page (K3.4, K4.15): the shared [PrinterContent] over its view model. It asks Jarvis only while it is on screen, so the view model
 * attaches when the page opens and detaches when it closes. **Upload** opens the Files picker for any file (a .gcode has no type of its own); the file's
 * name and size are checked by the page as on Android, and it is read from the picker's copy when the owner presses Send. The clock follows the phone's 12/24-hour setting.
 */
@Composable
fun IosPrinterScreen(vm: PrinterViewModel, onBack: () -> Unit, openPairing: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val controls by vm.controls.collectAsStateWithLifecycle()
    val picker = remember { IosFilePicker(listOf(UTTypeData)) }
    DisposableEffect(vm) {
        vm.attach()
        onDispose { vm.detach() }
    }
    val pick = {
        picker.pick { address ->
            val path = address?.let { NSURL.URLWithString(it)?.path }
            if (path != null) {
                val size = (NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: -1L
                val name = NSURL.fileURLWithPath(path).lastPathComponent.orEmpty()
                vm.data.chooseUpload(name, size) { runCatching { SystemFileSystem.source(Path(path)).buffered() }.getOrNull() }
            }
        }
    }
    PrinterContent(ui, deviceIs24Hour(), vm.pageActions(onBack, openPairing, pickUpload = pick), cards, controls)
}

/** Whether the phone shows times on a 24-hour clock: the pattern for "j" (the locale's preferred hour) has no am/pm marker. */
fun deviceIs24Hour(): Boolean = NSDateFormatter.dateFormatFromTemplate("j", 0u, NSLocale.currentLocale)?.contains('a') != true
