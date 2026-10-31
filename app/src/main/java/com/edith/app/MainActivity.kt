package com.edith.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edith.app.ui.components.CrashBanner
import com.edith.app.ui.components.CrashReportViewModel
import com.edith.app.ui.navigation.EdithNavHost
import com.edith.app.ui.theme.EdithTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EdithTheme {
                val crash: CrashReportViewModel = viewModel(factory = CrashReportViewModel.Factory)
                val report by crash.report.collectAsStateWithLifecycle()
                EdithNavHost(banner = { report?.let { CrashBanner(it, onDismiss = crash::dismiss) } })
            }
        }
    }
}
