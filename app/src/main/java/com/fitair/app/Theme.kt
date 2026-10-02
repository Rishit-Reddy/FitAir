package com.fitair.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

enum class ThemeMode(val key: String, val label: String) {
    System("system", "System"), Light("light", "Light"), Dark("dark", "Dark");

    companion object {
        fun fromKey(k: String?) = values().firstOrNull { it.key == k } ?: System
    }
}

private val Teal = Color(0xFF3E8E88)
private val TealDark = Color(0xFF6FB8B1)

private val LightScheme = lightColorScheme(
    primary = Teal, onPrimary = Color.White,
    background = Color(0xFFFAFAF9), onBackground = Color(0xFF161616),
    surface = Color(0xFFFAFAF9), onSurface = Color(0xFF161616),
    surfaceVariant = Color(0xFFF0F0EE), onSurfaceVariant = Color(0xFF7A7A76),
    outlineVariant = Color(0xFFE2E2DF), outline = Color(0xFFB8B8B4),
    secondaryContainer = Color(0xFFE3EFED), onSecondaryContainer = Color(0xFF1F4E4A),
)

private val DarkScheme = darkColorScheme(
    primary = TealDark, onPrimary = Color(0xFF0B1514),
    background = Color(0xFF0F0F0F), onBackground = Color(0xFFEDEDEB),
    surface = Color(0xFF0F0F0F), onSurface = Color(0xFFEDEDEB),
    surfaceVariant = Color(0xFF1A1A1A), onSurfaceVariant = Color(0xFF8C8C88),
    outlineVariant = Color(0xFF2A2A2A), outline = Color(0xFF4A4A48),
    secondaryContainer = Color(0xFF1E3532), onSecondaryContainer = Color(0xFFBFE0DC),
)

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraLight, fontSize = 84.sp, letterSpacing = (-2).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 32.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 17.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 11.sp, letterSpacing = 1.sp),
)

@Composable
fun FitAirTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = AppTypography, content = content)
}
