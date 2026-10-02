package com.fitair.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color

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
    surfaceVariant = Color(0xFFF0F0EE), onSurfaceVariant = Color(0xFF666662),
    outlineVariant = Color(0xFFE2E2DF), outline = Color(0xFFB8B8B4),
    secondaryContainer = Color(0xFFE3EFED), onSecondaryContainer = Color(0xFF1F4E4A),
    error = LightStatus.alert, onError = Color.White,
)

private val DarkScheme = darkColorScheme(
    primary = TealDark, onPrimary = Color(0xFF0B1514),
    background = Color(0xFF0F0F0F), onBackground = Color(0xFFEDEDEB),
    surface = Color(0xFF0F0F0F), onSurface = Color(0xFFEDEDEB),
    surfaceVariant = Color(0xFF1A1A1A), onSurfaceVariant = Color(0xFF8C8C88),
    outlineVariant = Color(0xFF2A2A2A), outline = Color(0xFF4A4A48),
    secondaryContainer = Color(0xFF1E3532), onSecondaryContainer = Color(0xFFBFE0DC),
    error = DarkStatus.alert, onError = Color(0xFF1A0E0C),
)

private val AppTypography = Typography(
    displayLarge = Type.display,
    headlineMedium = Type.headline,
    titleMedium = Type.title,
    bodyLarge = Type.bodyLight,
    bodyMedium = Type.body,
    bodySmall = Type.bodySmall,
    labelLarge = Type.label,
    labelMedium = Type.label,
    labelSmall = Type.caption,
)

@Composable
fun FitAirTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    // The app draws edge-to-edge, so status/navigation bar icons must contrast with the app theme
    // (dark icons on the light theme, light icons on the dark theme), not the system theme.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(
        LocalStatusColors provides if (dark) DarkStatus else LightStatus,
        LocalStageColors provides if (dark) DarkStage else LightStage,
    ) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = AppTypography, content = content)
    }
}
