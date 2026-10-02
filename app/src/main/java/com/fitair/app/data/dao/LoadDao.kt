package com.fitair.app.data.dao

import android.content.Context

/** Today's cardio load: [soFar] up to now, [typicalByNow] = 28-day median at this hour (null with < 7 days), [ratio] = acute/chronic. */
class LoadToday(val soFar: Double, val typicalByNow: Double?, val ratio: Double?, val coverage: Double?, val partial: Boolean)

/** STUB (A1). */
object LoadDao {
    fun today(ctx: Context): LoadToday = LoadToday(0.0, null, null, null, true)
}
