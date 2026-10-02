package com.fitair.app

import com.fitair.app.ui.components.StageLabel
import com.fitair.app.ui.components.StageLabel.Fit
import org.junit.Assert.*
import org.junit.Test

class StageLabelTest {
    @Test fun fitRule() {
        // pad 8 on each side: long needs 30 + 16 = 46
        assertEquals(Fit.Long, StageLabel.fit(46f, 30f, 20f, 8f))
        assertEquals(Fit.Short, StageLabel.fit(45.9f, 30f, 20f, 8f))
        assertEquals(Fit.Short, StageLabel.fit(36f, 30f, 20f, 8f))
        assertEquals(Fit.None, StageLabel.fit(35.9f, 30f, 20f, 8f))
        assertEquals(Fit.None, StageLabel.fit(0f, 30f, 20f, 8f))
    }

    private val light = listOf(0xFFD2691E, 0xFF3D8BC9, 0xFF7A4FC9, 0xFF24307F).map { it.toInt() }
    private val dark = listOf(0xFFF0A35C, 0xFF93CCF5, 0xFFB79BF5, 0xFF6A7CF0).map { it.toInt() }

    @Test fun contrastOfChosenInkIsAtLeast4_5InAllEightColours() {
        for (bg in light + dark) {
            val ink = StageLabel.inkFor(bg)
            val c = StageLabel.contrast(ink, bg)
            assertTrue("bg ${Integer.toHexString(bg)} ink ${Integer.toHexString(ink)} = $c", c >= 4.5)
        }
    }

    @Test fun inkChoice() {
        assertEquals(StageLabel.INK_LIGHT, StageLabel.inkFor(light[3]))  // Deep, light theme: white
        assertEquals(StageLabel.INK_LIGHT, StageLabel.inkFor(light[2]))  // REM, light theme: white
        assertEquals(StageLabel.INK_DARK, StageLabel.inkFor(light[1]))   // Light, light theme: dark
        assertEquals(StageLabel.INK_DARK, StageLabel.inkFor(light[0]))   // Awake, light theme: dark
        dark.forEach { assertEquals(StageLabel.INK_DARK, StageLabel.inkFor(it)) }
    }

    @Test fun contrastBasics() {
        assertEquals(21.0, StageLabel.contrast(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 0.01)
        assertEquals(1.0, StageLabel.contrast(0xFF123456.toInt(), 0xFF123456.toInt()), 1e-9)
    }
}
