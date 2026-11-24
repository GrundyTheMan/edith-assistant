package com.edith.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import kotlin.test.assertTrue

/** The H3 rule of the parity plan: every control VoiceOver or TalkBack can tap has a name (its text or a content description). */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.assertEveryClickableIsNamed() {
    val nodes = onAllNodes(hasClickAction()).fetchSemanticsNodes()
    assertTrue(nodes.isNotEmpty(), "the screen has no clickable controls at all, so this check would prove nothing")
    nodes.forEach { node ->
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
        assertTrue(!text.isNullOrBlank() || !description.isNullOrBlank(), "a clickable control has no name: ${node.config}")
    }
}

/** WCAG contrast ratio of two colours (1 to 21). 4.5 is the AA minimum for body text. */
fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}
