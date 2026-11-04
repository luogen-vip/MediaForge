package com.logan.mediaforge.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = darkColorScheme(
    primary = Color(0xFF5B9BFF),
    onPrimary = Color(0xFF06121F),
    secondary = Color(0xFF37D2C0),
    onSecondary = Color(0xFF05201C),
    tertiary = Color(0xFFFFC864),
    background = Color(0xFF0B0F1A),
    onBackground = Color(0xFFE8ECF4),
    surface = Color(0xFF121828),
    onSurface = Color(0xFFE8ECF4),
    surfaceVariant = Color(0xFF1B2336),
    onSurfaceVariant = Color(0xFFAAB4C8),
    error = Color(0xFFFF6B6B),
    outline = Color(0xFF39445C)
)

@Composable
fun MediaForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
