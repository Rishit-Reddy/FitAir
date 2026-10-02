package com.fitair.app

import com.fitair.app.ui.components.charts.indexAt
import com.fitair.app.ui.components.charts.labelIndices
import org.junit.Assert.*
import org.junit.Test

class ChartsTest {
    @Test fun labelIndicesSpread() {
        assertEquals(listOf(0, 10, 19, 29), labelIndices(30))
        assertEquals(listOf(0, 1), labelIndices(2))
        assertTrue(labelIndices(0).isEmpty())
    }
    @Test fun indexAtClamps() {
        assertEquals(0, indexAt(-5f, 10f, 100f, 10))
        assertEquals(9, indexAt(500f, 10f, 100f, 10))
        assertEquals(5, indexAt(65f, 10f, 100f, 10))
        assertNull(indexAt(1f, 0f, 100f, 0))
    }
}
