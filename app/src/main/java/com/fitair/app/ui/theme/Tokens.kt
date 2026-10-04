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
    val gutter = 16.dp          // page side padding (0.9.0: 16)
    val gap = 12.dp             // between Today blocks and Metrics grid cells
    val subGap = 8.dp           // between sub-cards inside the big card
    val heroPad = 12.dp         // big-card padding
    val tilePad = 14.dp         // sub-card padding
    val section = 24.dp         // gap between Today sections
    val minTouch = 48.dp
}

object Shapes {
    val card = RoundedCornerShape(12.dp)
    val chip = RoundedCornerShape(8.dp)
    /** Big Today card. */
    val hero = RoundedCornerShape(28.dp)
    /** Sub-cards and Metrics cards. */
    val tile = RoundedCornerShape(20.dp)
    /** Status chip: full pill. */
    val pill = RoundedCornerShape(percent = 50)
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

    // 0.9.0 (docs/PLAN_090 section 2): sentence-case titles, tabular figures for numbers.
    private const val TNUM = "tnum"
    val metricTitle = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp)
    val valueL = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp, fontFeatureSettings = TNUM)
    val unitL = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp)
    val valueS = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 26.sp, fontFeatureSettings = TNUM)
    /** [valueS] at font scale 1.3 and above. */
    val valueSDense = valueS.copy(fontSize = 20.sp, lineHeight = 24.sp)
    val unitS = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
    val valueGrid = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 34.sp, fontFeatureSettings = TNUM)
    val chip = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
    val bullet = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    val axis = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp)
    val headerDate = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 22.sp, lineHeight = 28.sp)
    val pageTitle = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 30.sp)
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

/** Tier colours (good / caution / alert); drawn only as dots, glyphs and score bars, never on text or backgrounds. */
@Immutable
class StatusColors(val good: Color, val caution: Color, val alert: Color)

val LightStatus = StatusColors(good = Color(0xFF2F7D4F), caution = Color(0xFFA26A14), alert = Color(0xFFB4483C))
val DarkStatus = StatusColors(good = Color(0xFF6CC895), caution = Color(0xFFE0A955), alert = Color(0xFFEE8073))

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** Sleep stage colours; used for sleep stages only. */
@Immutable
class StageColors(val awake: Color, val light: Color, val rem: Color, val deep: Color)

val LightStage = StageColors(awake = Color(0xFFD2691E), light = Color(0xFF3D8BC9), rem = Color(0xFF7A4FC9), deep = Color(0xFF24307F))
val DarkStage = StageColors(awake = Color(0xFFF0A35C), light = Color(0xFF93CCF5), rem = Color(0xFFB79BF5), deep = Color(0xFF6A7CF0))

val LocalStageColors = staticCompositionLocalOf { LightStage }

/** Heart-rate zone colours, index 0 = below Light (resting) .. 4 = Peak; used for the heart rate line, dots and zone rows only. */
@Immutable
class ZoneColors(val z: List<Color>) { operator fun get(i: Int) = z[i.coerceIn(0, z.lastIndex)] }

val LightZone = ZoneColors(listOf(Color(0xFF7C8A99), Color(0xFF2F9E6B), Color(0xFFC29A12), Color(0xFFD9701F), Color(0xFFC63D3D)))
val DarkZone = ZoneColors(listOf(Color(0xFF9AA7B5), Color(0xFF5CCB93), Color(0xFFE6C34D), Color(0xFFF0954F), Color(0xFFF2706B)))

val LocalZoneColors = staticCompositionLocalOf { LightZone }

/**
 * Chip colours as plain ARGB ints so contrast is unit-testable on the JVM. A status chip tints its container with the
 * status colour (16 % light, 24 % dark) over its surface; the text stays neutral ink (docs/PLAN_090 section 2).
 */
object ChipPalette {
    const val LIGHT_CONTAINER = 0xFFFFFFFF.toInt()      // big-card / Metrics card surface
    const val LIGHT_CONTAINER_HIGH = 0xFFF2F3F1.toInt() // sub-card surface
    const val DARK_CONTAINER = 0xFF18191A.toInt()
    const val DARK_CONTAINER_HIGH = 0xFF222423.toInt()
    const val LIGHT_INK = 0xFF161616.toInt()
    const val DARK_INK = 0xFFEDEDEB.toInt()
    const val LIGHT_NEUTRAL = 0xFFE2E2DF.toInt()        // outlineVariant
    const val DARK_NEUTRAL = 0xFF2A2A2A.toInt()

    fun blend(top: Int, bottom: Int, alpha: Float): Int {
        fun ch(shift: Int): Int {
            val t = (top shr shift) and 0xFF; val b = (bottom shr shift) and 0xFF
            return Math.round(t * alpha + b * (1f - alpha)).coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Container of a status chip of colour [status] on [surface]; neutral chips use the outline variant. */
    fun container(status: Int?, surface: Int, dark: Boolean): Int =
        if (status == null) (if (dark) DARK_NEUTRAL else LIGHT_NEUTRAL) else blend(status, surface, if (dark) 0.24f else 0.16f)

    fun ink(dark: Boolean) = if (dark) DARK_INK else LIGHT_INK

    private fun lin(c: Int): Double { val v = c / 255.0; return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
    fun luminance(argb: Int): Double =
        0.2126 * lin((argb shr 16) and 0xFF) + 0.7152 * lin((argb shr 8) and 0xFF) + 0.0722 * lin(argb and 0xFF)

    /** WCAG contrast ratio, 1..21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
