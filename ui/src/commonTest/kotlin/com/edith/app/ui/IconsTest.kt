package com.edith.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.ui.graphics.vector.ImageVector
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * K3.1: every Material icon the Android app uses today exists in the Compose Multiplatform icon set, so the screens can move without redrawing
 * any. The list is what `grep "Icons\."` finds in app/src/main (32 icons); this fails to compile if one is missing. Add an icon here when a screen starts using it.
 */
class IconsTest {
    @Test fun everyIconTheAndroidAppUsesExistsInTheSharedIconSet() {
        val icons: List<ImageVector> = listOf(
            Icons.AutoMirrored.Filled.ArrowBack, Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Filled.Send, Icons.AutoMirrored.Filled.VolumeUp,
            Icons.Filled.Add, Icons.Filled.ArrowDownward, Icons.Filled.AttachFile, Icons.Filled.AudioFile, Icons.Filled.CalendarMonth,
            Icons.Filled.Close, Icons.Filled.Description, Icons.Filled.ErrorOutline, Icons.Filled.ExpandLess, Icons.Filled.ExpandMore,
            Icons.Filled.FiberManualRecord, Icons.Filled.Hearing, Icons.Filled.Home, Icons.Filled.Image, Icons.Filled.KeyboardArrowDown,
            Icons.Filled.KeyboardArrowUp, Icons.Filled.Menu, Icons.Filled.Mic, Icons.Filled.MoreVert, Icons.Filled.PlayArrow,
            Icons.Filled.Refresh, Icons.Filled.Settings, Icons.Filled.Star, Icons.Filled.Stop, Icons.Filled.StopCircle,
            Icons.Outlined.Hearing, Icons.Outlined.StarBorder,
        )
        assertEquals(31, icons.size)
        assertEquals(31, icons.map { it.name }.toSet().size, "each icon has its own name")
    }
}
