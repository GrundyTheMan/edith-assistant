package com.edith.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.edith.app.core.lifecycle.AppVisibility
import com.edith.app.ui.components.CrashBanner
import com.edith.app.ui.components.CrashReportViewModel
import com.edith.app.ui.navigation.EdithNavHost
import com.edith.app.ui.settings.BrowserSignInDialogHost
import com.edith.app.ui.settings.AccountsViewModel
import com.edith.app.ui.theme.EdithTheme

/** Edith's single activity: edge to edge, hosting the navigation. */
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
                // C17b: a browser sign-in in progress shows its dialog on any screen.
                BrowserSignInDialogHost(viewModel(factory = AccountsViewModel.Factory))
            }
        }
    }

    // C17b: Edith's network is blocked while she's off screen; the browser sign-in waits for her to be back.
    override fun onStart() {
        super.onStart()
        AppVisibility.set(true)
    }

    override fun onStop() {
        AppVisibility.set(false)
        super.onStop()
    }
}
