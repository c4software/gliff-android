package io.github.c4software.gliff.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = darkColorScheme(
    primary = Color(0xFF8FB4FF),
    onPrimary = Color(0xFF0B2A5E),
    primaryContainer = Color(0xFF1E3F7A),
    onPrimaryContainer = Color(0xFFD9E5FF),
    secondary = Color(0xFFB7C4DD),
    background = Color(0xFF101319),
    surface = Color(0xFF101319),
    surfaceVariant = Color(0xFF1C212B),
    surfaceContainer = Color(0xFF181C24),
    surfaceContainerHigh = Color(0xFF212733),
    onSurface = Color(0xFFE3E6EE),
    onSurfaceVariant = Color(0xFFA9B0BF),
    error = Color(0xFFFFB4AB),
    outline = Color(0xFF444B5A),
)

/** Dark only: the app is mostly a remote screen, and chrome around it should recede. */
@Composable
fun GliffTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
