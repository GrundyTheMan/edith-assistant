package com.edith.app.alerts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.edith.app.alerts.Alert
import com.edith.app.core.time.formatPattern
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** "Front door — Doorbell rings", the words for one alert. */
fun alertLine(a: Alert): String = "${a.device} — ${a.kind.label}"

/** "3:42 PM". */
fun alertTime(at: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String = formatPattern(Instant.fromEpochMilliseconds(at), zone, "h:mm a", "en-US")

/**
 * New camera alerts while the app is open (K4.16): one card each, newest last, with a way to the clips and a way to dismiss. Nothing here is a system
 * notification: iOS shows those only for an app that is running in the background, which this one is not.
 */
@Composable
fun AlertBanners(alerts: List<Alert>, onOpenClips: () -> Unit, onDismiss: (Alert) -> Unit, modifier: Modifier = Modifier) {
    if (alerts.isEmpty()) return
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        alerts.forEach { a ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth().testTag(ALERT_TAG)) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(alertLine(a), style = MaterialTheme.typography.bodyMedium)
                        Text(alertTime(a.at), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = onOpenClips) { Text("Clips") }
                    TextButton(onClick = { onDismiss(a) }) { Text("Dismiss") }
                }
            }
        }
    }
}

/** "While you were away": what the cameras reported since the app was last opened, as a card at the top of the Home tab, until dismissed. */
@Composable
fun CatchUpCard(alerts: List<Alert>, onOpenClips: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (alerts.isEmpty()) return
    Card(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag(CATCH_UP_TAG)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("While you were away", style = MaterialTheme.typography.titleSmall)
            alerts.take(SHOWN).forEach { a ->
                Text("${alertTime(a.at)}  ${alertLine(a)}", style = MaterialTheme.typography.bodyMedium)
            }
            if (alerts.size > SHOWN) Text("and ${alerts.size - SHOWN} more", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = onOpenClips) { Text("See the clips") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

private const val SHOWN = 5
const val ALERT_TAG = "alert-banner"
const val CATCH_UP_TAG = "catch-up-card"
