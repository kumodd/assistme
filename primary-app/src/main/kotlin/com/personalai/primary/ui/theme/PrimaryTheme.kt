package com.personalai.primary.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Semantic accents for the "2nd Mind Memory" Obsidian workspace.
 */
object SecondMindColors {
    val Canvas = Color(0xFF090E14)
    val CardSurface = Color(0xFF121A24)
    val CardElevated = Color(0xFF182330)
    val HairlineBorder = Color(0xFF233245)
    val CyanAccent = Color(0xFF67E8F9)
    val EmeraldPrivacy = Color(0xFF34D399)
    val EmeraldContainer = Color(0xFF0A2E25)
    val AmberHighlight = Color(0xFFFBBF24)
    val AmberContainer = Color(0xFF2E2208)
    val VioletAi = Color(0xFFA78BFA)
    val VioletContainer = Color(0xFF20173A)
    val CoralAlert = Color(0xFFFF6B6B)
    val MutedText = Color(0xFF94A3B8)
}

private val PrimaryColorScheme = darkColorScheme(
    primary = Color(0xFF67E8F9),
    onPrimary = Color(0xFF002A36),
    primaryContainer = Color(0xFF0C3B4E),
    onPrimaryContainer = Color(0xFFD6F6FF),
    secondary = Color(0xFFA78BFA),
    onSecondary = Color(0xFF1E1042),
    secondaryContainer = Color(0xFF1E2235),
    onSecondaryContainer = Color(0xFFE6DCFF),
    tertiary = Color(0xFF34D399),
    onTertiary = Color(0xFF003022),
    tertiaryContainer = Color(0xFF0D2E25),
    onTertiaryContainer = Color(0xFFBDFCE3),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF560006),
    errorContainer = Color(0xFF3D1418),
    onErrorContainer = Color(0xFFFFD6D6),
    background = Color(0xFF090E14),
    onBackground = Color(0xFFE8EEF5),
    surface = Color(0xFF0F1620),
    onSurface = Color(0xFFE8EEF5),
    surfaceVariant = Color(0xFF16202C),
    onSurfaceVariant = Color(0xFFB5C2D1),
    outline = Color(0xFF2B3B4E),
    outlineVariant = Color(0xFF1D2938)
)

@Composable
fun PrimaryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PrimaryColorScheme,
        content = content
    )
}
