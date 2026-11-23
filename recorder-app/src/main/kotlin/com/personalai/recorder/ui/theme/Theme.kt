package com.personalai.recorder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RecorderDarkColorScheme = darkColorScheme(
    primary = Color(0xFF4FD8EB),
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF97F0FF),
    secondary = Color(0xFFB1CBD0),
    onSecondary = Color(0xFF1C3438),
    secondaryContainer = Color(0xFF334B4F),
    onSecondaryContainer = Color(0xFFCDE7EC),
    error = Color(0xFFFF5449),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1416),
    onBackground = Color(0xFFDEE3E5),
    surface = Color(0xFF161C1E),
    onSurface = Color(0xFFDEE3E5),
    surfaceVariant = Color(0xFF252D30),
    onSurfaceVariant = Color(0xFFBFC8CA)
)

@Composable
fun RecorderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RecorderDarkColorScheme,
        content = content
    )
}
