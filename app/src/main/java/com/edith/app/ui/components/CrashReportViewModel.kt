package com.edith.app.ui.components

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edith.app.core.CrashReporter
import com.edith.app.di.viewModelFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Holds the last crash report (if any) for [CrashBanner]. File access stays off the main thread. */
class CrashReportViewModel(private val app: Application, private val io: CoroutineDispatcher) : ViewModel() {
    private val _report = MutableStateFlow<String?>(null)
    val report: StateFlow<String?> = _report.asStateFlow()

    init {
        viewModelScope.launch { _report.value = withContext(io) { CrashReporter.pending(app)?.text } }
    }

    fun dismiss() {
        _report.value = null
        viewModelScope.launch(io) { CrashReporter.dismiss(app) }
    }

    companion object {
        val Factory = viewModelFactory { container, _ -> CrashReportViewModel(container.app, container.dispatchers.io) }
    }
}
