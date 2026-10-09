package com.example.jarvis.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val HudColorScheme = darkColorScheme(
    primary = HudCyanNeon,
    secondary = HudPrimaryAmber,
    tertiary = HudGoldAccent,
    background = HudBackground,
    surface = HudSurface,
    onPrimary = HudBackground,
    onSecondary = HudBackground,
    onBackground = HudTextPrimary,
    onSurface = HudTextPrimary,
    error = HudErrorRed
)

@Composable
fun JARVISTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = HudColorScheme,
        typography = Typography,
        content = content
    )
}
