package com.fitair.app

import androidx.compose.ui.graphics.toArgb
import com.fitair.app.ui.theme.ChipPalette
import com.fitair.app.ui.theme.DarkStatus
import com.fitair.app.ui.theme.LightStatus
import org.junit.Assert.*
import org.junit.Test

/** Chip text is neutral ink on a container tinted with the status colour: at least 4.5:1 in both themes, on both card surfaces. */
class ChipContrastTest {
    private fun check(name: String, status: Int?, surface: Int, dark: Boolean) {
        val c = ChipPalette.contrast(ChipPalette.ink(dark), ChipPalette.container(status, surface, dark))
        assertTrue("$name on ${java.lang.Integer.toHexString(surface)} = $c", c >= 4.5)
    }

    @Test fun lightTones() {
        listOf("good" to LightStatus.good, "caution" to LightStatus.caution, "alert" to LightStatus.alert).forEach { (n, col) ->
            check(n, col.toArgb(), ChipPalette.LIGHT_CONTAINER, false); check(n, col.toArgb(), ChipPalette.LIGHT_CONTAINER_HIGH, false)
        }
        check("neutral", null, ChipPalette.LIGHT_CONTAINER, false); check("neutral", null, ChipPalette.LIGHT_CONTAINER_HIGH, false)
    }

    @Test fun darkTones() {
        listOf("good" to DarkStatus.good, "caution" to DarkStatus.caution, "alert" to DarkStatus.alert).forEach { (n, col) ->
            check(n, col.toArgb(), ChipPalette.DARK_CONTAINER, true); check(n, col.toArgb(), ChipPalette.DARK_CONTAINER_HIGH, true)
        }
        check("neutral", null, ChipPalette.DARK_CONTAINER, true); check("neutral", null, ChipPalette.DARK_CONTAINER_HIGH, true)
    }

    @Test fun contrastMathIsSane() {
        assertEquals(21.0, ChipPalette.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, ChipPalette.contrast(0xFF808080.toInt(), 0xFF808080.toInt()), 0.001)
    }
}
