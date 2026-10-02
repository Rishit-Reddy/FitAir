package com.fitair.app.ui.components

import kotlin.math.pow

/** Pure rules for the minutes drawn inside sleep-stage segments (docs/PLAN_081.md 7.5). */
object StageLabel {
    enum class Fit { Long, Short, None }

    /** Side padding kept free inside a segment, in dp. */
    const val PAD_DP = 4f

    const val INK_LIGHT = 0xFFFFFFFF.toInt()
    const val INK_DARK = 0xFF161616.toInt()

    /**
     * Long form ("1h 05") if `longPx + 2 * padPx <= segmentPx`, else the short form ("65m") by the same rule, else no label.
     * Labels therefore never extend past their segment.
     */
    fun fit(segmentPx: Float, longPx: Float, shortPx: Float, padPx: Float): Fit = when {
        longPx + 2 * padPx <= segmentPx -> Fit.Long
        shortPx + 2 * padPx <= segmentPx -> Fit.Short
        else -> Fit.None
    }

    private fun lin(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    /** WCAG relative luminance of an ARGB int. */
    fun luminance(argb: Int): Double =
        0.2126 * lin((argb shr 16) and 0xFF) + 0.7152 * lin((argb shr 8) and 0xFF) + 0.0722 * lin(argb and 0xFF)

    /** WCAG contrast ratio of two ARGB ints (1..21). */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Whichever of white and #161616 has the higher contrast on [bg]. */
    fun inkFor(bg: Int): Int = if (contrast(INK_LIGHT, bg) >= contrast(INK_DARK, bg)) INK_LIGHT else INK_DARK
}
