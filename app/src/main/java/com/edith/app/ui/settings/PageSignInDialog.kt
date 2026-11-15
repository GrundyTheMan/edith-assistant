package com.edith.app.ui.settings

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.edith.app.core.log.Log
import com.edith.app.core.log.LogTag
import com.edith.app.home.PageSignInStart
import com.edith.app.home.SignInResult

const val PAGE_SIGN_IN_TAG = "page-sign-in"
const val PAGE_ADDRESS_TAG = "page-sign-in-address"

/**
 * Signing in to a service on its own web page (Alexa: Amazon's, C30). Two ways, as the owner chose: **in the app**,
 * where Edith shows the page and catches where it ends; or **in the browser**, where the owner copies the address the
 * page ends on (it won't load: that is expected) and pastes it here. The browser works wherever Chrome does,
 * including Andarium on the iPhone. The password goes to the service's page, never through Edith's code.
 */
@Composable
fun PageSignInDialog(
    name: String,
    siteName: String,
    begin: () -> PageSignInStart?,
    finish: (address: String, cookies: String, done: (SignInResult) -> Unit) -> Unit,
    onClose: () -> Unit,
) {
    var start by remember { mutableStateOf<PageSignInStart?>(null) }
    var step by remember { mutableStateOf(Step.Choose) }
    var message by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    fun done(address: String, cookies: String) {
        working = true
        finish(address, cookies) { result ->
            working = false
            when (result) {
                SignInResult.Done -> onClose()
                is SignInResult.Failed -> { message = result.message; step = Step.Choose }
                is SignInResult.NeedsCode -> { message = result.message; step = Step.Choose }
            }
        }
    }

    when (step) {
        Step.Choose -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Sign in to $name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$siteName's own page asks for your email, password and any code. Your password goes to ${siteName}'s page, never through Edith.")
                    if (working) Text("Finishing the sign-in…", color = MaterialTheme.colorScheme.primary)
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = { message = null; start = begin(); if (start != null) step = Step.Web }, enabled = !working, modifier = Modifier.testTag(PAGE_SIGN_IN_TAG)) { Text("Sign in here") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { message = null; start = begin(); if (start != null) step = Step.OpenBrowser }, enabled = !working) { Text("Use my browser") }
                    TextButton(onClick = onClose) { Text("Cancel") }
                }
            },
        )
        Step.Web -> start?.let { s -> PageWebView(name, s, onFinished = { address, cookies -> step = Step.Choose; done(address, cookies) }, onClose = { step = Step.Choose }) }
        Step.OpenBrowser -> start?.let { s -> BrowserSteps(name, siteName, s, onFinished = { address -> step = Step.Choose; done(address, "") }, onClose = onClose) }
    }
}

private enum class Step { Choose, Web, OpenBrowser }

/** Amazon's page in the app. Cookies the page expects are set first; the page ending is caught as it is reached. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PageWebView(name: String, start: PageSignInStart, onFinished: (address: String, cookies: String) -> Unit, onClose: () -> Unit) {
    var finished by remember { mutableStateOf(false) }
    val cookies = remember { CookieManager.getInstance() }
    val webView = remember { arrayOfNulls<WebView>(1) }
    DisposableEffect(Unit) {
        onDispose {
            // Nothing of the sign-in stays in the web view: not the page, not the cookies.
            webView[0]?.destroy()
            cookies.removeAllCookies(null)
            cookies.flush()
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sign in to $name", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Cancel") }
                }
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webView[0] = this
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // A browser's name, not a web view's: sign-in pages treat the two differently.
                            settings.userAgentString = settings.userAgentString.replace("; wv", "").replace("Version/4.0 ", "")
                            cookies.removeAllCookies(null)
                            cookies.setAcceptCookie(true)
                            val domain = Uri.parse(start.cookieSite).host.orEmpty().removePrefix("www.")
                            start.cookies.forEach { (n, v) -> cookies.setCookie(start.cookieSite, "$n=$v; Domain=.$domain; Path=/; Secure") }
                            cookies.flush()
                            fun reached(url: String): Boolean {
                                if (finished || !start.isFinish(url)) return false
                                finished = true
                                // The page that ends the sign-in is never shown: only its address is wanted.
                                stopLoading()
                                val held = cookies.getCookie(start.cookieSite).orEmpty()
                                Log.i(LogTag.Home, "$name's sign-in page ended")
                                onFinished(url, held)
                                return true
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = reached(request.url.toString())
                                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { reached(url) }
                            }
                            loadUrl(start.url)
                        }
                    },
                )
            }
        }
    }
}

/** The browser way: open the page, then ask for the address it ends on. */
@Composable
private fun BrowserSteps(name: String, siteName: String, start: PageSignInStart, onFinished: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var opened by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    if (!opened) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Sign in with your browser") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your browser opens $siteName's page. Sign in there. When it ends on a page that won't load, copy that page's address from the address bar, come back to Edith and paste it.")
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(start.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        opened = true
                    } catch (e: ActivityNotFoundException) {
                        problem = "No browser was found to open the page."
                    }
                }) { Text("Open the page") }
            },
            dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        )
    } else {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Finish signing in to $name") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Paste the address the page ended on. It starts with https://www.amazon.com/ap/maplanding.")
                    OutlinedTextField(address, { address = it; problem = null }, label = { Text("Paste the address") }, minLines = 2,
                        modifier = Modifier.fillMaxWidth().testTag(PAGE_ADDRESS_TAG))
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = { if (address.isBlank()) problem = "Paste the address first." else onFinished(address.trim()) }, enabled = address.isNotBlank()) { Text("Use this address") } },
            dismissButton = {
                Row {
                    OutlinedButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(start.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Text("Open the page again") }
                    TextButton(onClick = onClose) { Text("Cancel") }
                }
            },
        )
    }
}
