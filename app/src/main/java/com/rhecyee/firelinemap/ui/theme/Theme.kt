package com.rhecyee.firelinemap.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF6A623),
    secondary = Color(0xFFB9C8A8),
    background = Color(0xFF151A14),
    surface = Color(0xFF20261F),
    error = Color(0xFFFF6B5E)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF8C4D00),
    secondary = Color(0xFF4E6342),
    background = Color(0xFFF5F3EA),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFB3261E)
)

@Composable
fun FirelineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
