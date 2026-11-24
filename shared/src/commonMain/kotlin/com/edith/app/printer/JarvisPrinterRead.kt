package com.edith.app.printer

import com.edith.app.home.DeviceState

/** What Jarvis says about the printer, as the Home tab shows it: null when Jarvis is not paired, does not share it, or cannot reach it (C39). */
fun interface JarvisPrinterRead {
    suspend fun read(): DeviceState?
}
