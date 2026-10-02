package com.fitair.app.analytics

import org.json.JSONObject

class DriverView(val key: String, val label: String, val score: Double, val weightPct: Int, val text: String)

/** Display model parsed from a daily_metrics.readiness_json (v2). */
class ReadinessView(
    val score: Int?,
    val version: Int,
    /** Present components, largest influence first. */
    val drivers: List<DriverView>,
    /** Component label to reason, for components that could not be scored. */
    val missing: List<Pair<String, String>>,
    val note: String?,
) {
    /** The single reason shown under the number. */
    val mainDriver: String? get() = drivers.firstOrNull()?.text

    companion object {
        fun parse(json: String?): ReadinessView? {
            if (json.isNullOrEmpty() || json == "null") return null
            return try {
                val o = JSONObject(json)
                val score = if (o.isNull("score")) null else Math.rint(o.getDouble("score")).toInt()
                val arr = o.optJSONArray("drivers")
                val drivers = (0 until (arr?.length() ?: 0)).map {
                    val d = arr!!.getJSONObject(it)
                    DriverView(d.getString("key"), d.getString("label"), d.getDouble("score"), d.getInt("weight_pct"), d.getString("text"))
                }
                val m = o.optJSONObject("missing")
                val missing = m?.keys()?.asSequence()?.map { (ReadinessMath.LABELS[it] ?: it) to m.getString(it) }?.toList() ?: emptyList()
                val notes = o.optJSONArray("notes")
                ReadinessView(score, o.optInt("readiness_version", 1), drivers, missing,
                    if (score == null) notes?.optString(0)?.takeIf { it.isNotBlank() } else null)
            } catch (e: Exception) { null }
        }
    }
}
