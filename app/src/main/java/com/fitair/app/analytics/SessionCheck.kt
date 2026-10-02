package com.fitair.app.analytics

/**
 * Flags exercise sessions that are probably not exercise (docs/PLAN_081.md 3.1). Pure.
 * suspicious = meanHrr < 0.25 && share(hrr >= 0.40) < 0.10, or type in {biking, e-biking, other} && meanHrr < 0.30.
 * A session of < 10 min or without heart rate is never flagged. The user's verdict always wins.
 */
object SessionCheck {
    const val MIN_MIN = 10
    /** Health Connect exercise types: OTHER_WORKOUT = 0, BIKING = 8 (stationary biking is real exercise and is not listed). */
    val LOW_ASSURANCE_TYPES = setOf(0, 8)

    class Result(val meanHrr: Double?, val shareHigh: Double?, val suspicious: Boolean)

    fun check(durationMin: Double, type: Int, bucketMeans: List<Double>, rest: Double, hrMax: Double): Result {
        if (durationMin < MIN_MIN || bucketMeans.isEmpty()) return Result(null, null, false)
        val hrrs = bucketMeans.map { CardioLoad.hrr(it, rest, hrMax).coerceAtLeast(0.0) }
        val mean = hrrs.average()
        val share = hrrs.count { it >= 0.40 }.toDouble() / hrrs.size
        return Result(mean, share, isSuspicious(mean, share, type))
    }

    fun isSuspicious(meanHrr: Double, shareHigh: Double, type: Int): Boolean =
        (meanHrr < 0.25 && shareHigh < 0.10) || (type in LOW_ASSURANCE_TYPES && meanHrr < 0.30)

    const val NOT_EXERCISE = "not_exercise"
    const val EXERCISE = "exercise"

    /**
     * Effective verdict of a session: a user verdict (auto = false) wins over everything; otherwise the automatic flag.
     * Returns true when the session counts as NOT exercise.
     */
    fun excluded(userVerdict: String?, autoFlagged: Boolean): Boolean = when (userVerdict) {
        NOT_EXERCISE -> true
        EXERCISE -> false
        else -> autoFlagged
    }

    /** What to do with the stored flag row after a check: "set" (write auto not_exercise), "clear" (remove an auto row), or "keep". */
    fun autoAction(existingAuto: Boolean?, existingUser: Boolean, suspicious: Boolean): String = when {
        existingUser -> "keep"
        suspicious -> "set"
        existingAuto == true -> "clear"
        else -> "keep"
    }
}
