package com.fitair.app.data

import android.content.Context
import com.fitair.app.data.dao.PrefDao
import java.util.Locale

/** Height and BMI. BMI = kg / m²; the WHO adult bands are shown as plain ranges, never as a verdict. */
object Body {
    const val PREF_HEIGHT = "height_cm"

    fun heightCm(ctx: Context): Double? = PrefDao.get(ctx, PREF_HEIGHT)?.toDoubleOrNull()?.takeIf { validHeight(it) }
    fun setHeightCm(ctx: Context, cm: Double?) = PrefDao.set(ctx, PREF_HEIGHT, cm?.takeIf { validHeight(it) }?.let { String.format(Locale.US, "%.0f", it) })

    fun validHeight(cm: Double) = cm in 120.0..230.0

    /** Null when either input is missing or out of range. */
    fun bmi(kg: Double?, cm: Double?): Double? {
        if (kg == null || cm == null || !validHeight(cm) || kg !in 25.0..300.0) return null
        val m = cm / 100.0
        return kg / (m * m)
    }

    /** WHO adult band as a neutral phrase. */
    fun band(bmi: Double): String = when {
        bmi < 18.5 -> "below 18.5"
        bmi < 25.0 -> "in the 18.5–25 range"
        bmi < 30.0 -> "in the 25–30 range"
        else -> "30 or above"
    }

    /** Weights that put BMI at 18.5 and 25 for [cm], for the "range for your height" line. */
    fun rangeKg(cm: Double): Pair<Double, Double> { val m = cm / 100.0; return 18.5 * m * m to 25.0 * m * m }

    fun fmt(bmi: Double) = String.format(Locale.US, "%.1f", bmi)
}
