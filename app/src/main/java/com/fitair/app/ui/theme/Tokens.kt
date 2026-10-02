package com.fitair.app.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design tokens (docs/ARCHITECTURE.md 2.4). Screens use these and Material3 primitives only. */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
    val gutter = 20.dp          // page side padding
    val section = 24.dp         // gap between Today sections
    val minTouch = 48.dp
}

object Shapes {
    val card = RoundedCornerShape(12.dp)
    val chip = RoundedCornerShape(8.dp)
    val sheet = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
}

object Type {
    private val sans = FontFamily.SansSerif
    val display = TextStyle(fontFamily = sans, fontWeight = FontWeight.ExtraLight, fontSize = 64.sp, lineHeight = 68.sp, letterSpacing = (-1.5).sp)
    val headline = TextStyle(fontFamily = sans, fontWeight = FontWeight.Light, fontSize = 28.sp, lineHeight = 34.sp)
    val title = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 22.sp)
    val body = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    val bodyLight = TextStyle(fontFamily = sans, fontWeight = FontWeight.Light, fontSize = 17.sp, lineHeight = 24.sp)
    val bodySmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val label = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp)
    /** Callers upper-case the text. */
    val caption = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.sp)
}

/**
 * Motion: standard 150 ms (fade, expand), emphasized 250 ms (sheet, accept collapse), FastOutSlowIn, no springs.
 * Compose scales these by the system animator scale, so a scale of 0 turns animation off.
 */
object Motion {
    const val STANDARD_MS = 150
    const val EMPHASIZED_MS = 250
    fun <T> standard(): TweenSpec<T> = tween(STANDARD_MS, easing = FastOutSlowInEasing)
    fun <T> emphasized(): TweenSpec<T> = tween(EMPHASIZED_MS, easing = FastOutSlowInEasing)
}

/** Status colours; only ever drawn as a 6dp dot or a delta arrow, never as large fills. */
@Immutable
class StatusColors(val good: Color, val caution: Color, val alert: Color)

val LightStatus = StatusColors(good = Color(0xFF3E8E88), caution = Color(0xFFB7791F), alert = Color(0xFFB4483C))
val DarkStatus = StatusColors(good = Color(0xFF6FB8B1), caution = Color(0xFFD9A55A), alert = Color(0xFFE07A6E))

val LocalStatusColors = staticCompositionLocalOf { LightStatus }
