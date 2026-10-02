package com.fitair.app.coach

import android.content.Context

data class DayFacts(
    val date: String, val steps: Long, val distanceM: Double, val cardio: Double, val zoneMin: Int,
    val waterMl: Int, val waterGoalMl: Int, val rhr: Double?, val hrAvg: Double?, val hrMax: Double?,
    val workouts: List<String>, val partial: Boolean, val hasData: Boolean = true,
)

class SummaryText(val text: String, val source: String /* llm|template */, val createdMs: Long)

/** STUB (A1). */
object DaySummary {
    fun facts(ctx: Context, date: java.time.LocalDate): DayFacts =
        DayFacts(date.toString(), 0, 0.0, 0.0, 0, 0, 2500, null, null, null, emptyList(), true, false)
    fun facts(ctx: Context, date: String): DayFacts = facts(ctx, java.time.LocalDate.parse(date))
    suspend fun text(ctx: Context, date: java.time.LocalDate, regenerate: Boolean): SummaryText =
        SummaryText("", "template", System.currentTimeMillis())
    suspend fun text(ctx: Context, date: String, regenerate: Boolean): SummaryText = text(ctx, java.time.LocalDate.parse(date), regenerate)
}
