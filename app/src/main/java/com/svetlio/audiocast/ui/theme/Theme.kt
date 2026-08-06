package com.svetlio.audiocast.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF00BFA5)
private val TealDark = Color(0xFF00897B)

private val DarkColors = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF00201B),
    secondary = TealDark,
    background = Color(0xFF0E0E0E),
    surface = Color(0xFF1A1A1A),
    onBackground = Color(0xFFECECEC),
    onSurface = Color(0xFFECECEC),
)

/** Always-dark theme; matches the dark window background in themes.xml. */
@Composable
fun AudioCastTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
