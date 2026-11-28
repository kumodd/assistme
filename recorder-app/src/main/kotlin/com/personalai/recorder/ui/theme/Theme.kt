package com.personalai.recorder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Aligned with Primary's Google AI Edge Gallery Expressive Editorial Light palette.
private val RecorderLightColorScheme = lightColorScheme(
    primary = Color(0xFF32628D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8F1F8),
    onPrimaryContainer = Color(0xFF113250),
    secondary = Color(0xFFDE4A41),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFDECEB),
    onSecondaryContainer = Color(0xFF6E1A14),
    tertiary = Color(0xFF1E8E3E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE6F4EA),
    onTertiaryContainer = Color(0xFF0B461D),
    error = Color(0xFFD93025),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFCE8E6),
    onErrorContainer = Color(0xFF7A1510),
    background = Color(0xFFFFF5F5),
    onBackground = Color(0xFF1F1F1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFF0F4F9),
    onSurfaceVariant = Color(0xFF444746),
    outline = Color(0xFFD5DCE4),
    outlineVariant = Color(0xFFECE2E2)
)

@Composable
fun RecorderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RecorderLightColorScheme,
        content = content
    )
}
