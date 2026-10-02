package com.fitair.app.ui.components

import com.fitair.app.data.metrics.MetricId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MetricAccentTest {
    @Test fun everyChartedMetricHasAColourInBothThemes() {
        MetricId.values().filter { it != MetricId.Bedtime }.forEach {
            assertNotNull("$it light", MetricAccent.of(it, false))
            assertNotNull("$it dark", MetricAccent.of(it, true))
        }
        assertNull(MetricAccent.of(MetricId.Bedtime, false))
    }

    @Test fun lightAndDarkDiffer() = assertNotEquals(MetricAccent.of(MetricId.Heart, false), MetricAccent.of(MetricId.Heart, true))

    @Test fun relatedMetricsShareAColour() = assertEquals(MetricAccent.of(MetricId.Steps, true), MetricAccent.of(MetricId.Distance, true))
}
