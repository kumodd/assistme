package com.personalai.primary.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Expressive Editorial Light design tokens inspired by Google AI Edge Gallery:
 * crisp `#FFFFFF` floating 24dp cards & top/nav bars resting on a warm `#FFF5F5` tinted canvas,
 * soft `#F0F4F9` tonal pill controls, `#DE4A41` coral hero emblems, `#32628D` editorial link blue,
 * and `#1F1F1F` / `#444746` high-contrast charcoal typography.
 */
object SecondMindColors {
    val Canvas = Color(0xFFFFF5F5)           // Soft warm blush-tinted editorial canvas
    val TopBarSurface = Color(0xFFFFFFFF)    // Pure crisp white top & bottom chrome
    val CardSurface = Color(0xFFFFFFFF)      // Pure crisp white floating cards
    val CardElevated = Color(0xFFF0F4F9)     // Signature soft tonal pill / button surface
    val HairlineBorder = Color(0xFFECE2E2)   // Whisper-subtle divider & card edge
    val HeroCoral = Color(0xFFDE4A41)        // Signature circular hero icon badge
    val HeroCoralSoft = Color(0xFFFDECEB)    // Soft coral active indicator / container
    val CyanAccent = Color(0xFF32628D)       // Editorial link & primary interactive blue
    val BlueSoftContainer = Color(0xFFE8F1F8)
    val EmeraldPrivacy = Color(0xFF1E8E3E)   // Crisp high-contrast privacy/ready green
    val EmeraldContainer = Color(0xFFE6F4EA)
    val AmberHighlight = Color(0xFFE09F00)   // Warm amber for star/priority text contrast
    val StarGold = Color(0xFFFCC934)         // Signature star badge gold
    val AmberContainer = Color(0xFFFEF7DF)
    val VioletAi = Color(0xFF6750A4)         // Material 3 editorial AI violet
    val VioletContainer = Color(0xFFF3EEFA)
    val CoralAlert = Color(0xFFD93025)       // High-contrast light-mode alert red
    val PrimaryInk = Color(0xFF1F1F1F)       // Rich warm charcoal primary text
    val SecondaryInk = Color(0xFF444746)     // Body text charcoal
    val MutedText = Color(0xFF747775)        // Subtle caption & metadata gray
}

private val PrimaryColorScheme = lightColorScheme(
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

/**
 * Clean editorial typography scale with generous line heights so multilingual English + Devanagari
 * Hindi/Hinglish transcripts render effortlessly on light surfaces without clipping matras.
 */
private val SecondMindTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.4).sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.3).sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.1).sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.05.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 25.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.15.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.2.sp
    )
)

@Composable
fun PrimaryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PrimaryColorScheme,
        typography = SecondMindTypography,
        content = content
    )
}
