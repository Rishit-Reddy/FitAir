package com.fitair.app.ui.today

import android.content.Context
import com.fitair.app.data.dao.PrefDao
import java.time.LocalDate

/** "Seen?" on the morning recap: remembered for one calendar day, then the recap returns the next morning. */
object MorningSeen {
    private const val K = "morning_seen_date"
    fun isSeen(ctx: Context, day: LocalDate) = PrefDao.get(ctx, K) == day.toString()
    fun mark(ctx: Context, day: LocalDate) = PrefDao.set(ctx, K, day.toString())
}
