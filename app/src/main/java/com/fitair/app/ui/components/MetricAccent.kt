package com.fitair.app.ui.components

import androidx.compose.ui.graphics.Color
import com.fitair.app.data.metrics.MetricId

/**
 * One fixed colour per metric (issue #13). Drawn only on the chart itself (line, bars, ring, dots), never on text,
 * titles or card backgrounds. Status and sleep-stage colours keep their own meaning and are not reused here.
 */
object MetricAccent {
    private class Pair2(val light: Color, val dark: Color)

    private val heart = Pair2(Color(0xFFC2448E), Color(0xFFF078B8))
    private val load = Pair2(Color(0xFFD9771F), Color(0xFFF2A25E))
    private val energy = Pair2(Color(0xFF9A7B0A), Color(0xFFE6CB4C))
    private val blue = Pair2(Color(0xFF3D8BC9), Color(0xFF93CCF5))
    private val water = Pair2(Color(0xFF0E8CA8), Color(0xFF5CCBE3))
    private val teal = Pair2(Color(0xFF3E8E88), Color(0xFF6FB8B1))
    private val violet = Pair2(Color(0xFF7A4FC9), Color(0xFFB79BF5))
    private val indigo = Pair2(Color(0xFF4A5BC4), Color(0xFF8F9CF5))

    fun of(id: MetricId, dark: Boolean): Color? {
        val p = when (id) {
            MetricId.Heart, MetricId.RestingHr -> heart
            MetricId.Load -> load
            MetricId.Energy -> energy
            MetricId.Steps, MetricId.Distance -> blue
            MetricId.Water -> water
            MetricId.Readiness, MetricId.Weight -> teal
            MetricId.Hrv -> violet
            MetricId.Sleep -> indigo
            MetricId.Bedtime -> return null
        }
        return if (dark) p.dark else p.light
    }
}
