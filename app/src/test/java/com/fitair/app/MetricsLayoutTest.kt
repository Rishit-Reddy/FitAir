package com.fitair.app

import com.fitair.app.data.metrics.MetricId
import com.fitair.app.data.metrics.MetricId.*
import com.fitair.app.ui.metrics.LayoutEntry
import com.fitair.app.ui.metrics.MetricsLayout
import com.fitair.app.ui.metrics.metricsColumns
import org.junit.Assert.*
import org.junit.Test

class MetricsLayoutTest {
    @Test fun defaultsListEveryCardVisibleAndWeightOnlyWithData() {
        val d = MetricsLayout.defaults(false)
        assertEquals(listOf(Heart, Readiness, Sleep, RestingHr, Hrv, Load, Energy, Distance, Steps, Water), d.map { it.id })
        assertTrue(d.all { it.on })
        assertEquals(Weight, MetricsLayout.defaults(true).last().id)
    }

    @Test fun roundTripKeepsOrderAndHiding() {
        val e = MetricsLayout.move(MetricsLayout.defaults(false), 8, -8)           // Steps to the top
        val hidden = MetricsLayout.setOn(e, Distance, false)
        val back = MetricsLayout.merge(MetricsLayout.toJson(hidden), false)
        assertEquals(Steps, back.first().id)
        assertFalse(back.first { it.id == Distance }.on)
        assertEquals(hidden, back)
    }

    @Test fun unknownIdsAreDroppedAndNewIdsAppendedVisible() {
        val json = """[{"id":"steps","on":true},{"id":"mindful","on":true},{"id":"heart","on":false}]"""
        val m = MetricsLayout.merge(json, false)
        assertEquals(listOf(Steps, Heart), m.take(2).map { it.id })
        assertFalse(m[1].on)
        assertEquals(MetricsLayout.defaults(false).size, m.size)
        assertTrue(m.drop(2).all { it.on })
    }

    @Test fun brokenOrEmptyJsonGivesDefaults() {
        assertEquals(MetricsLayout.defaults(false), MetricsLayout.merge(null, false))
        assertEquals(MetricsLayout.defaults(false), MetricsLayout.merge("not json", false))
        assertEquals(MetricsLayout.defaults(false), MetricsLayout.merge("[]", false))
    }

    @Test fun duplicatesCollapseAndWeightIsDroppedWithoutData() {
        val json = """[{"id":"weight","on":true},{"id":"sleep","on":true},{"id":"sleep","on":false}]"""
        val m = MetricsLayout.merge(json, false)
        assertEquals(1, m.count { it.id == Sleep })
        assertNull(m.firstOrNull { it.id == Weight })
        assertTrue(MetricsLayout.merge(json, true).first().id == Weight)
    }

    @Test fun moveStaysInsideTheList() {
        val d = MetricsLayout.defaults(false)
        assertEquals(d, MetricsLayout.move(d, 0, -1)); assertEquals(d, MetricsLayout.move(d, d.lastIndex, 1))
        assertEquals(Readiness, MetricsLayout.move(d, 1, -1).first().id)
    }

    @Test fun visibleKeepsOrderOfShownCards() {
        val e = listOf(LayoutEntry(Sleep), LayoutEntry(Heart, false), LayoutEntry(Water))
        assertEquals(listOf(Sleep, Water), MetricsLayout.visible(e))
    }

    @Test fun columnsGrowWithWidth() {
        assertEquals(2, metricsColumns(411f)); assertEquals(3, metricsColumns(600f)); assertEquals(4, metricsColumns(840f))
    }
}
