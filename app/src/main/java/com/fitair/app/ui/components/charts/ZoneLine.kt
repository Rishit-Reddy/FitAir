package com.fitair.app.ui.components.charts

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.fitair.app.data.metrics.MetricStats
import com.fitair.app.ui.theme.ZoneColors

/**
 * Draws a heart rate line one segment at a time, each segment in the colour of the zone its later point is in.
 * [vals] are bpm (NaN = gap, breaks the line); [ys] are the pixel heights of the same points.
 */
fun DrawScope.zoneLine(xs: List<Float>, ys: List<Float?>, vals: FloatArray, zoneBpm: FloatArray?, colors: ZoneColors, fallback: Color, width: Float) {
    fun col(v: Float) = if (zoneBpm == null) fallback else colors[MetricStats.zoneIndex(v.toDouble(), zoneBpm)]
    for (i in 1 until vals.size) {
        val a = ys[i - 1]; val b = ys[i]
        if (a == null || b == null || vals[i].isNaN() || vals[i - 1].isNaN()) continue
        drawLine(col(vals[i]), Offset(xs[i - 1], a), Offset(xs[i], b), width, StrokeCap.Round)
    }
    for (i in vals.indices) {
        val y = ys[i] ?: continue
        if ((i == 0 || ys[i - 1] == null) && (i == vals.lastIndex || ys[i + 1] == null)) drawCircle(col(vals[i]), width * 1.3f, Offset(xs[i], y))
    }
}
