package com.fitair.app.ui.metrics

import android.content.Context
import com.fitair.app.data.dao.PrefDao
import com.fitair.app.data.metrics.MetricId
import org.json.JSONArray
import org.json.JSONObject

/** One row of the Metrics layout: which card and whether it is shown. */
data class LayoutEntry(val id: MetricId, val on: Boolean = true)

/**
 * The Metrics tab layout (docs/PLAN_090 5, Edit mode). Pure merge/move/serialise helpers plus read/write of the pref
 * `metrics_layout` = `[{"id":"heart","on":true},...]` through [PrefDao].
 */
object MetricsLayout {
    const val PREF = "metrics_layout"

    /** Cards that can appear in the tab (Bedtime is Today-only). */
    private val TAB_IDS = MetricCards.DEFAULT_ORDER

    private val KEYS = mapOf(
        MetricId.Heart to "heart", MetricId.Readiness to "readiness", MetricId.Sleep to "sleep", MetricId.RestingHr to "rhr",
        MetricId.Hrv to "hrv", MetricId.Load to "load", MetricId.Energy to "energy", MetricId.Distance to "distance",
        MetricId.Steps to "steps", MetricId.Water to "water", MetricId.Weight to "weight",
    )
    fun key(id: MetricId): String? = KEYS[id]
    private fun idOf(key: String): MetricId? = KEYS.entries.firstOrNull { it.value == key }?.key

    /** Factory order, every card visible; Weight only when [hasWeight]. */
    fun defaults(hasWeight: Boolean): List<LayoutEntry> = TAB_IDS.filter { it != MetricId.Weight || hasWeight }.map { LayoutEntry(it) }

    /**
     * Saved JSON merged with the current card set: unknown ids and duplicates are dropped, new ids are appended visible, Weight is
     * dropped while there is no weight data. Broken or empty JSON gives the defaults.
     */
    fun merge(json: String?, hasWeight: Boolean): List<LayoutEntry> {
        val saved = parse(json)
        val allowed = defaults(hasWeight).map { it.id }.toSet()
        val kept = saved.filter { it.id in allowed }.distinctBy { it.id }
        val missing = defaults(hasWeight).filter { d -> kept.none { it.id == d.id } }
        return kept + missing
    }

    fun parse(json: String?): List<LayoutEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = idOf(o.optString("id")) ?: return@mapNotNull null
                LayoutEntry(id, o.optBoolean("on", true))
            }
        } catch (e: Exception) { emptyList() }
    }

    fun toJson(entries: List<LayoutEntry>): String {
        val a = JSONArray()
        entries.forEach { e -> key(e.id)?.let { a.put(JSONObject().put("id", it).put("on", e.on)) } }
        return a.toString()
    }

    /** Moves the entry at [index] by [delta] (-1 up, +1 down); out-of-range moves return the list unchanged. */
    fun move(entries: List<LayoutEntry>, index: Int, delta: Int): List<LayoutEntry> {
        val to = index + delta
        if (index !in entries.indices || to !in entries.indices) return entries
        val m = entries.toMutableList()
        m.add(to, m.removeAt(index))
        return m
    }

    fun setOn(entries: List<LayoutEntry>, id: MetricId, on: Boolean): List<LayoutEntry> = entries.map { if (it.id == id) it.copy(on = on) else it }

    /** The shown cards in order. */
    fun visible(entries: List<LayoutEntry>): List<MetricId> = entries.filter { it.on }.map { it.id }

    fun read(ctx: Context, hasWeight: Boolean): List<LayoutEntry> = merge(PrefDao.get(ctx, PREF), hasWeight)
    fun write(ctx: Context, entries: List<LayoutEntry>) = PrefDao.set(ctx, PREF, toJson(entries))
    fun reset(ctx: Context) = PrefDao.set(ctx, PREF, null)
}
