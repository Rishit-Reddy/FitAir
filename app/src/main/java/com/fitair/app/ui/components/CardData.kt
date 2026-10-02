package com.fitair.app.ui.components

import com.fitair.app.data.metrics.MetricId
import com.fitair.app.ui.components.charts.Mini

/** Card sizes of the one shared metric card (docs/PLAN_090 section 4). */
enum class CardSize { Large, Small, Grid }

/** Ready = normal; Learning = chip says "Learning your normal"; Stale = value dimmed; Empty = "—", dotted baseline, "No data". */
enum class CardState { Ready, Learning, Stale, Empty }

/** A status chip: dot + words. [Tone.Neutral] means no dot and a neutral container. */
class Chip(val text: String, val tone: Tone = Tone.Neutral, val short: String? = null)

/** Everything a [MetricCard] draws. Pure data, built by `MetricCards.card`. */
class CardData(
    val id: MetricId,
    val title: String,
    val value: String?,
    val unit: String? = null,
    val sub: String? = null,
    val mini: Mini? = null,
    val chip: Chip? = null,
    val state: CardState = CardState.Ready,
    /** Full sentence for TalkBack, e.g. "Resting heart rate, 53 beats per minute, in your usual range. Opens details." */
    val a11y: String = title,
    /** Weekday initials under a Grid chart (7 entries) and the index of today's label. */
    val weekdays: List<String> = emptyList(),
    val todayIndex: Int = -1,
    /** Small cards draw the tiny [mini] instead of the chip when true (never both). */
    val smallShowsMini: Boolean = false,
)
