package com.edith.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.edith.app.accounts.ui.MailScreenFrame
import com.edith.app.accounts.ui.MailViewModel

/** The iPhone's Mail tab (K7.1): the shared ranked list over its view model. Keeps clear of the status bar itself, as the other tabs do (MainViewController passes no top inset). */
@Composable
fun IosMailScreen(vm: MailViewModel, openMore: () -> Unit) {
    Box(Modifier.statusBarsPadding()) { MailScreenFrame(vm, openMore) }
}
