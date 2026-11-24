package com.edith.app.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import com.edith.app.ui.gallery.ComponentGallery
import com.edith.app.ui.gallery.ComponentGalleryContent
import com.edith.app.ui.theme.EdithTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/** K3.1: the quality rules every shared component must keep (parity plan H3 and H4), checked on the gallery. */
@OptIn(ExperimentalTestApi::class)
class GalleryTest {
    @Test fun everyClickableControlInTheGalleryHasAName() = runComposeUiTest {
        setContent { ComponentGallery() }
        assertEveryClickableIsNamed()
    }

    @Test fun theGalleryContentCanBeScrolledInsideAnotherScreen() = runComposeUiTest {
        // The first iPhone build crashed on this: a scrolling column measured inside another one is given infinite height.
        setContent { Column(Modifier.verticalScroll(rememberScrollState())) { ComponentGalleryContent() } }
        assertEveryClickableIsNamed()
    }

    @Test fun textColoursAreReadableInLightAndDark() = runComposeUiTest {
        val schemes = mutableMapOf<String, ColorScheme>()
        setContent {
            EdithTheme(darkTheme = false) { schemes["light"] = MaterialTheme.colorScheme }
            EdithTheme(darkTheme = true) { schemes["dark"] = MaterialTheme.colorScheme }
        }
        for ((name, c) in schemes) {
            val pairs = mapOf(
                "onBackground on background" to (c.onBackground to c.background),
                "onSurface on surface" to (c.onSurface to c.surface),
                "onPrimary on primary" to (c.onPrimary to c.primary),
                "onErrorContainer on errorContainer" to (c.onErrorContainer to c.errorContainer),
                "onSecondaryContainer on secondaryContainer" to (c.onSecondaryContainer to c.secondaryContainer),
                "primary on background (links)" to (c.primary to c.background),
            )
            for ((what, colours) in pairs) {
                val ratio = contrastRatio(colours.first, colours.second)
                assertTrue(ratio >= 4.5, "$name theme, $what: contrast $ratio is below 4.5")
            }
        }
    }
}
