package com.edith.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = EdithPurple,
    background = LightBackground,
    surface = LightBackground,
)

private val DarkColors = darkColorScheme(
    primary = EdithPurpleLight,
    background = DarkBackground,
    surface = DarkBackground,
)

/** Edith's look. Dynamic (wallpaper) colour is deliberately off so the approved purple look never changes. */
@Composable
fun EdithTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
