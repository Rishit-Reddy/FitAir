package com.fitair.app

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.units.Volume
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.reflect.KClass

object HealthPerms {
    val read: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(RespiratoryRateRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
        HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
    )
    val write: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
    )
    val all: Set<String> = read + write

    /**
     * Permissions asked in context, never in the startup gate (adding them to [all] would lock the app on update):
     * hydration (water logging) and, from 0.8.2, weight / body fat.
     */
    val optional: Set<String> = setOf(
        HealthPermission.getReadPermission(HydrationRecord::class),
        HealthPermission.getWritePermission(HydrationRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
    )
    val hydration: Set<String> = setOf(
        HealthPermission.getReadPermission(HydrationRecord::class),
        HealthPermission.getWritePermission(HydrationRecord::class),
    )
}

data class ProbeRow(
    val type: String,
    val count: Int,
    val firstIso: String?,
    val lastIso: String?,
    val medianGapSec: Double?,
    val origins: List<String>,
)

class HealthRepo(private val context: Context) {

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    suspend fun hasAllPermissions(): Boolean =
        client.permissionController.getGrantedPermissions().containsAll(HealthPerms.all)

    suspend fun hasHistoryPermission(): Boolean =
        client.permissionController.getGrantedPermissions().contains(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)

    /** Granted subset of [HealthPerms.optional]. Empty if Health Connect is unavailable. */
    suspend fun grantedOptional(): Set<String> = try {
        client.permissionController.getGrantedPermissions().intersect(HealthPerms.optional)
    } catch (e: Throwable) { emptySet() }

    suspend fun canWriteHydration(): Boolean = try {
        client.permissionController.getGrantedPermissions().contains(HealthPermission.getWritePermission(HydrationRecord::class))
    } catch (e: Throwable) { false }

    suspend fun canReadHydration(): Boolean = try {
        client.permissionController.getGrantedPermissions().contains(HealthPermission.getReadPermission(HydrationRecord::class))
    } catch (e: Throwable) { false }

    /** Writes one drink; [clientId] makes the record idempotent and lets [deleteHydration] remove it. Returns the HC record id or null. */
    suspend fun writeHydration(tMs: Long, ml: Double, clientId: String): String? {
        val start = Instant.ofEpochMilli(tMs); val end = Instant.ofEpochMilli(tMs + 1000)
        val z = ZoneId.systemDefault().rules
        val rec = HydrationRecord(
            startTime = start, startZoneOffset = z.getOffset(start), endTime = end, endZoneOffset = z.getOffset(end),
            volume = Volume.milliliters(ml), metadata = Metadata.manualEntry(clientRecordId = clientId),
        )
        return client.insertRecords(listOf(rec)).recordIdsList.firstOrNull()
    }

    suspend fun deleteHydration(clientId: String) {
        client.deleteRecords(HydrationRecord::class, emptyList(), listOf(clientId))
    }

    /** Hydration records from other apps in [from, to) as {t, ml, origin, hc_id}; this app's own records are skipped. */
    suspend fun readHydration(from: Instant, to: Instant): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        pages(HydrationRecord::class, from, to, { out.addAll(it) }) { r ->
            if (r.metadata.dataOrigin.packageName == context.packageName) emptyList()
            else listOf(JSONObject().put("t", r.startTime.toEpochMilli()).put("ml", r.volume.inMilliliters)
                .put("origin", r.metadata.dataOrigin.packageName).put("hc_id", r.metadata.id))
        }
        return out
    }

    /** Reads [type] records in [from, to) page by page; [onPage] gets API.md-shaped JSON entries per page. */
    suspend fun readForSync(type: String, from: Instant, to: Instant, onPage: suspend (List<JSONObject>) -> Unit) {
        when (type) {
            "heart_rate" -> pages(HeartRateRecord::class, from, to, onPage) { r ->
                val o = r.metadata.dataOrigin.packageName
                r.samples.map { JSONObject().put("t", it.time.toEpochMilli()).put("bpm", it.beatsPerMinute).put("origin", o) }
            }
            "steps" -> pages(StepsRecord::class, from, to, onPage) { r ->
                listOf(JSONObject().put("start", r.startTime.toEpochMilli()).put("end", r.endTime.toEpochMilli())
                    .put("count", r.count).put("origin", r.metadata.dataOrigin.packageName))
            }
            "distance" -> pages(DistanceRecord::class, from, to, onPage) { r ->
                listOf(JSONObject().put("start", r.startTime.toEpochMilli()).put("end", r.endTime.toEpochMilli())
                    .put("meters", r.distance.inMeters).put("origin", r.metadata.dataOrigin.packageName))
            }
            "total_calories" -> pages(TotalCaloriesBurnedRecord::class, from, to, onPage) { r ->
                listOf(JSONObject().put("start", r.startTime.toEpochMilli()).put("end", r.endTime.toEpochMilli())
                    .put("kcal", r.energy.inKilocalories).put("origin", r.metadata.dataOrigin.packageName))
            }
            "resting_hr" -> pages(RestingHeartRateRecord::class, from, to, onPage) { r ->
                listOf(JSONObject().put("t", r.time.toEpochMilli()).put("bpm", r.beatsPerMinute)
                    .put("origin", r.metadata.dataOrigin.packageName))
            }
            "hrv" -> pages(HeartRateVariabilityRmssdRecord::class, from, to, onPage) { r ->
                listOf(JSONObject().put("t", r.time.toEpochMilli()).put("rmssd", r.heartRateVariabilityMillis)
                    .put("origin", r.metadata.dataOrigin.packageName))
            }
            "respiratory_rate" -> pages(RespiratoryRateRecord::class, from, to, onPage) { r ->
                listOf(JSONObject().put("t", r.time.toEpochMilli()).put("rate", r.rate)
                    .put("origin", r.metadata.dataOrigin.packageName))
            }
            "sleep" -> pages(SleepSessionRecord::class, from, to, onPage) { r ->
                val st = JSONArray()
                r.stages.forEach {
                    st.put(JSONObject().put("start", it.startTime.toEpochMilli()).put("end", it.endTime.toEpochMilli()).put("stage", it.stage))
                }
                listOf(JSONObject().put("start", r.startTime.toEpochMilli()).put("end", r.endTime.toEpochMilli())
                    .put("origin", r.metadata.dataOrigin.packageName).put("stages", st))
            }
            "exercise" -> pages(ExerciseSessionRecord::class, from, to, onPage) { r ->
                listOf(JSONObject().put("start", r.startTime.toEpochMilli()).put("end", r.endTime.toEpochMilli())
                    .put("type", r.exerciseType).put("title", r.title ?: "").put("origin", r.metadata.dataOrigin.packageName))
            }
            else -> throw IllegalArgumentException("unknown type $type")
        }
    }

    private suspend fun <T : Record> pages(
        type: KClass<T>, from: Instant, to: Instant,
        onPage: suspend (List<JSONObject>) -> Unit, map: (T) -> List<JSONObject>,
    ) {
        var token: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                    pageSize = 1000,
                    pageToken = token,
                )
            )
            val out = resp.records.flatMap(map)
            if (out.isNotEmpty()) onPage(out)
            token = resp.pageToken?.takeIf { it.isNotEmpty() }
        } while (token != null)
    }

    /**
     * Health Connect's de-duplicated step total for [from, to) (HC merges overlapping origins).
     * Used only by the self-check to validate the local DB; null if it cannot be read.
     */
    suspend fun stepsTotal(from: Instant, to: Instant): Long? = runCatching {
        client.aggregate(
            AggregateRequest(metrics = setOf(StepsRecord.COUNT_TOTAL), timeRangeFilter = TimeRangeFilter.between(from, to))
        )[StepsRecord.COUNT_TOTAL] ?: 0L
    }.getOrNull()

    private suspend fun <T : Record> stream(
        type: KClass<T>,
        from: Instant,
        to: Instant,
        onRecord: (T) -> Unit,
    ) {
        var token: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                    pageSize = 1000,
                    pageToken = token,
                )
            )
            resp.records.forEach(onRecord)
            token = resp.pageToken?.takeIf { it.isNotEmpty() }
        } while (token != null)
    }

    private class Acc {
        var count = 0
        var first: Instant? = null
        var last: Instant? = null
        var prev: Instant? = null
        val origins = sortedSetOf<String>()
        val hist = HashMap<Long, Int>()
        var histN = 0L

        fun addTime(t: Instant) {
            if (first == null || t < first) first = t
            if (last == null || t > last) last = t
            prev?.let {
                val g = Math.round((t.toEpochMilli() - it.toEpochMilli()) / 1000.0)
                if (g >= 0) { hist.merge(g, 1, Int::plus); histN++ }
            }
            prev = t
        }

        fun median(): Double? {
            if (histN == 0L) return null
            val target = (histN + 1) / 2
            var run = 0L
            for (k in hist.keys.sorted()) {
                run += hist[k]!!
                if (run >= target) return k.toDouble()
            }
            return null
        }
    }

    private suspend fun <T : Record> probeType(
        name: String,
        type: KClass<T>,
        from: Instant,
        to: Instant,
        startOf: (T) -> Instant,
    ): ProbeRow = try {
        val a = Acc()
        stream(type, from, to) { r ->
            a.count++
            a.origins.add(r.metadata.dataOrigin.packageName)
            a.addTime(startOf(r))
        }
        ProbeRow(name, a.count, a.first?.toString(), a.last?.toString(), a.median(), a.origins.toList())
    } catch (e: Throwable) {
        ProbeRow("$name [ERR: ${e.message ?: e.javaClass.simpleName}]", 0, null, null, null, emptyList())
    }

    suspend fun probe(days: Int = 30): List<ProbeRow> {
        val to = Instant.now()
        val from = to.minus(Duration.ofDays(days.toLong()))
        val rows = mutableListOf<ProbeRow>()

        rows += probeType("Steps", StepsRecord::class, from, to) { it.startTime }
        rows += probeType("Distance", DistanceRecord::class, from, to) { it.startTime }

        // HeartRate: flatten samples; gaps computed over all samples in time order.
        var samples = 0L
        rows += try {
            val a = Acc()
            val s = Acc()
            stream(HeartRateRecord::class, from, to) { r ->
                a.count++
                a.origins.add(r.metadata.dataOrigin.packageName)
                a.addTime(r.startTime)
                for (smp in r.samples) { s.addTime(smp.time); samples++ }
            }
            ProbeRow("HeartRate", a.count, a.first?.toString(), a.last?.toString(), s.median(), a.origins.toList())
        } catch (e: Throwable) {
            ProbeRow("HeartRate [ERR: ${e.message ?: e.javaClass.simpleName}]", 0, null, null, null, emptyList())
        }
        rows += ProbeRow("HeartRate samples", samples.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), null, null, null, emptyList())

        rows += probeType("RestingHeartRate", RestingHeartRateRecord::class, from, to) { it.time }
        rows += probeType("HeartRateVariabilityRmssd", HeartRateVariabilityRmssdRecord::class, from, to) { it.time }
        rows += probeType("SleepSession", SleepSessionRecord::class, from, to) { it.startTime }
        rows += probeType("OxygenSaturation", OxygenSaturationRecord::class, from, to) { it.time }
        rows += probeType("ExerciseSession", ExerciseSessionRecord::class, from, to) { it.startTime }
        rows += probeType("ActiveCaloriesBurned", ActiveCaloriesBurnedRecord::class, from, to) { it.startTime }
        rows += probeType("TotalCaloriesBurned", TotalCaloriesBurnedRecord::class, from, to) { it.startTime }
        rows += probeType("RespiratoryRate", RespiratoryRateRecord::class, from, to) { it.time }
        rows += probeType("Vo2Max", Vo2MaxRecord::class, from, to) { it.time }
        // Optional permissions: an ERR row here just means "not granted yet" (nothing is requested by the probe).
        rows += probeType("Weight", WeightRecord::class, from, to) { it.time }
        rows += probeType("Hydration", HydrationRecord::class, from, to) { it.startTime }
        return rows
    }

    suspend fun logSession(startMs: Long, endMs: Long, title: String, notes: String?, kcal: Double?) {
        val start = Instant.ofEpochMilli(startMs)
        val end = Instant.ofEpochMilli(endMs)
        val offset = ZoneId.systemDefault().rules.getOffset(start)
        val records = mutableListOf<Record>()
        records += ExerciseSessionRecord(
            startTime = start,
            startZoneOffset = offset,
            endTime = end,
            endZoneOffset = ZoneId.systemDefault().rules.getOffset(end),
            metadata = Metadata.manualEntry(),
            // No EXERCISE_TYPE_PICKLEBALL in connect-client 1.1.0; TENNIS is the closest racquet-sport type.
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_TENNIS,
            title = title,
            notes = notes,
        )
        if (kcal != null && kcal > 0.0) {
            records += TotalCaloriesBurnedRecord(
                startTime = start,
                startZoneOffset = offset,
                endTime = end,
                endZoneOffset = ZoneId.systemDefault().rules.getOffset(end),
                energy = Energy.kilocalories(kcal),
                metadata = Metadata.manualEntry(),
            )
        }
        client.insertRecords(records)
    }
}
