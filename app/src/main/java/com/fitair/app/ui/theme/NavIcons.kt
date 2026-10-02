package com.fitair.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Custom 24dp vector icons (no material-icons-extended). Outlined when unselected, filled when selected.
 * Drawn in black; callers tint them (`primary` when selected, `onSurfaceVariant` otherwise).
 */
object NavIcons {
    private val ink = SolidColor(Color.Black)

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.line(w: Float = 2f, f: PathBuilder.() -> Unit) =
        path(fill = null, stroke = ink, strokeLineWidth = w, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = f)

    private fun ImageVector.Builder.solid(rule: PathFillType = PathFillType.NonZero, f: PathBuilder.() -> Unit) =
        path(fill = ink, pathFillType = rule, pathBuilder = f)

    // ---- Today: sun over the horizon ----------------------------------------------------------------------

    private fun ImageVector.Builder.sunRays() = line {
        moveTo(12f, 5f); lineTo(12f, 7.5f)
        moveTo(17.2f, 9.8f); lineTo(19f, 8f)
        moveTo(6.8f, 9.8f); lineTo(5f, 8f)
        moveTo(3f, 15f); lineTo(5.5f, 15f)
        moveTo(18.5f, 15f); lineTo(21f, 15f)
        moveTo(3f, 19.5f); lineTo(21f, 19.5f)
    }

    val TodayOutline: ImageVector by lazy {
        icon("today_outline") {
            sunRays()
            line { moveTo(7.5f, 15f); arcToRelative(4.5f, 4.5f, 0f, false, true, 9f, 0f); close() }
        }
    }
    val TodayFilled: ImageVector by lazy {
        icon("today_filled") {
            sunRays()
            solid { moveTo(7f, 15f); arcToRelative(5f, 5f, 0f, false, true, 10f, 0f); close() }
        }
    }

    // ---- Calendar: page with two rings ------------------------------------------------------------------

    private fun ImageVector.Builder.rings() = line { moveTo(8f, 3f); lineTo(8f, 6f); moveTo(16f, 3f); lineTo(16f, 6f) }

    val CalendarOutline: ImageVector by lazy {
        icon("calendar_outline") {
            rings()
            line {
                moveTo(6f, 5.5f); lineTo(18f, 5.5f); arcToRelative(2f, 2f, 0f, false, true, 2f, 2f); lineTo(20f, 18f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f); lineTo(6f, 20f); arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                lineTo(4f, 7.5f); arcToRelative(2f, 2f, 0f, false, true, 2f, -2f); close()
                moveTo(4f, 10.5f); lineTo(20f, 10.5f)
            }
        }
    }
    val CalendarFilled: ImageVector by lazy {
        icon("calendar_filled") {
            rings()
            // header band and body with a 1.5 gap between them
            solid {
                moveTo(6f, 5f); lineTo(18f, 5f); arcToRelative(2f, 2f, 0f, false, true, 2f, 2f); lineTo(20f, 9.5f); lineTo(4f, 9.5f)
                lineTo(4f, 7f); arcToRelative(2f, 2f, 0f, false, true, 2f, -2f); close()
                moveTo(4f, 11f); lineTo(20f, 11f); lineTo(20f, 18f); arcToRelative(2f, 2f, 0f, false, true, -2f, 2f); lineTo(6f, 20f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f); close()
            }
        }
    }

    // ---- Coach: chat bubble ------------------------------------------------------------------------------

    private fun PathBuilder.bubble() {
        moveTo(6f, 4f); lineTo(18f, 4f); quadTo(20f, 4f, 20f, 6f); lineTo(20f, 14f); quadTo(20f, 16f, 18f, 16f)
        lineTo(10f, 16f); lineTo(6f, 20f); lineTo(6f, 16f); quadTo(4f, 16f, 4f, 14f); lineTo(4f, 6f); quadTo(4f, 4f, 6f, 4f); close()
    }
    val CoachOutline: ImageVector by lazy { icon("coach_outline") { line { bubble() } } }
    val CoachFilled: ImageVector by lazy { icon("coach_filled") { solid { bubble() } } }

    // ---- Log: plus in a circle ---------------------------------------------------------------------------

    val LogOutline: ImageVector by lazy {
        icon("log_outline") {
            line { moveTo(3f, 12f); arcToRelative(9f, 9f, 0f, true, false, 18f, 0f); arcToRelative(9f, 9f, 0f, true, false, -18f, 0f); close() }
            line { moveTo(12f, 8f); lineTo(12f, 16f); moveTo(8f, 12f); lineTo(16f, 12f) }
        }
    }
    val LogFilled: ImageVector by lazy {
        icon("log_filled") {
            // even-odd: the plus is a hole in the disc
            solid(PathFillType.EvenOdd) {
                moveTo(3f, 12f); arcToRelative(9f, 9f, 0f, true, false, 18f, 0f); arcToRelative(9f, 9f, 0f, true, false, -18f, 0f); close()
                moveTo(11f, 7f); lineTo(13f, 7f); lineTo(13f, 11f); lineTo(17f, 11f); lineTo(17f, 13f); lineTo(13f, 13f)
                lineTo(13f, 17f); lineTo(11f, 17f); lineTo(11f, 13f); lineTo(7f, 13f); lineTo(7f, 11f); lineTo(11f, 11f); close()
            }
        }
    }

    // ---- plain plus (Calendar "+") -----------------------------------------------------------------------

    val Add: ImageVector by lazy { icon("add") { line(2.2f) { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) } } }

    // ---- Metrics: axes with a rising trend line ("monitoring") ---------------------------------------------

    private fun ImageVector.Builder.trend() = line {
        moveTo(3.5f, 16.5f); lineTo(8.5f, 11f); lineTo(12.5f, 14.5f); lineTo(20.5f, 6.5f)
    }

    val MetricsOutline: ImageVector by lazy {
        icon("metrics_outline") {
            line { moveTo(3.5f, 3.5f); lineTo(3.5f, 20.5f); lineTo(20.5f, 20.5f) }
            trend()
        }
    }
    val MetricsFilled: ImageVector by lazy {
        icon("metrics_filled") {
            solid { moveTo(3.5f, 16.5f); lineTo(8.5f, 11f); lineTo(12.5f, 14.5f); lineTo(20.5f, 6.5f); lineTo(20.5f, 19f); lineTo(3.5f, 19f); close() }
            line { moveTo(3.5f, 3.5f); lineTo(3.5f, 20.5f); lineTo(20.5f, 20.5f) }
            trend()
        }
    }

    // ---- gear (Today header) -----------------------------------------------------------------------------

    val Gear: ImageVector by lazy {
        icon("gear") {
            line(1.8f) {
                val teeth = 8
                for (i in 0 until teeth) {
                    val a = i * 2 * PI / teeth
                    fun pt(r: Double, da: Double, first: Boolean) {
                        val x = (12 + r * cos(a + da)).toFloat(); val y = (12 + r * sin(a + da)).toFloat()
                        if (first) moveTo(x, y) else lineTo(x, y)
                    }
                    pt(6.8, -0.26, i == 0); pt(9.2, -0.17, false); pt(9.2, 0.17, false); pt(6.8, 0.26, false)
                }
                close()
                moveTo(15f, 12f); arcToRelative(3f, 3f, 0f, true, false, -6f, 0f); arcToRelative(3f, 3f, 0f, true, false, 6f, 0f); close()
            }
        }
    }
}
