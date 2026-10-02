package com.fitair.app.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import com.fitair.app.AppLog
import com.fitair.app.LocalStore
import com.fitair.app.data.dao.PrefDao
import java.time.Instant
import java.time.ZoneId

/**
 * Weight both ways with Health Connect (optional permissions, asked from the Log tab): readings other apps wrote are copied into the
 * local `weight` table, and weights entered in FitAir (origin "fitair") are written to Health Connect once, idempotently.
 * Without the permissions every call is a no-op and the entry just stays in FitAir.
 */
object WeightSync {
    const val ORIGIN = "fitair"
    private const val K_PUSHED = "weight_pushed_t"
    private const val FIRST_IMPORT_DAYS = 365L

    val perms: Set<String> = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class),
    )

    private fun client(ctx: Context) = HealthConnectClient.getOrCreate(ctx.applicationContext)

    suspend fun granted(ctx: Context): Boolean = try {
        client(ctx).permissionController.getGrantedPermissions().containsAll(perms)
    } catch (e: Throwable) { false }

    /** Stores a weight entered in the app (kg, 20..400); returns false for a value that cannot be a body weight. */
    fun save(ctx: Context, kg: Double, tMs: Long = System.currentTimeMillis()): Boolean {
        if (kg !in 20.0..400.0) return false
        LocalStore.get(ctx).db.execSQL("INSERT OR REPLACE INTO weight(t,kg,fat_pct,origin) VALUES(?,?,NULL,?)", arrayOf<Any>(tMs, kg, ORIGIN))
        return true
    }

    /** Latest stored weight as (time, kg), from any source. */
    fun latest(ctx: Context): Pair<Long, Double>? =
        LocalStore.get(ctx).db.rawQuery("SELECT t, kg FROM weight ORDER BY t DESC LIMIT 1", null).use { if (it.moveToFirst()) it.getLong(0) to it.getDouble(1) else null }

    /** Import then push. Safe to call on every sync. */
    suspend fun sync(ctx: Context) {
        if (!granted(ctx)) return
        try { import(ctx) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { AppLog.d("weight import failed: ${e.message}") }
        try { push(ctx) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { AppLog.d("weight push failed: ${e.message}") }
    }

    private suspend fun import(ctx: Context) {
        val app = ctx.applicationContext
        val db = LocalStore.get(app).db
        val last = db.rawQuery("SELECT MAX(t) FROM weight WHERE origin<>?", arrayOf(ORIGIN)).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
        val now = Instant.now()
        val from = last?.let { Instant.ofEpochMilli(it).minusSeconds(2 * 86_400L) } ?: now.minusSeconds(FIRST_IMPORT_DAYS * 86_400L)
        var token: String? = null
        do {
            val resp = client(app).readRecords(ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.between(from, now.plusSeconds(60)), pageSize = 500, pageToken = token))
            for (r in resp.records) {
                if (r.metadata.dataOrigin.packageName == app.packageName) continue
                db.execSQL("INSERT OR REPLACE INTO weight(t,kg,fat_pct,origin) VALUES(?,?,NULL,?)",
                    arrayOf<Any>(r.time.toEpochMilli(), r.weight.inKilograms, r.metadata.dataOrigin.packageName))
            }
            token = resp.pageToken
        } while (token != null)
    }

    private suspend fun push(ctx: Context) {
        val app = ctx.applicationContext
        val db = LocalStore.get(app).db
        val pushed = PrefDao.get(app, K_PUSHED)?.toLongOrNull() ?: 0L
        val rows = ArrayList<Pair<Long, Double>>()
        db.rawQuery("SELECT t, kg FROM weight WHERE origin=? AND t>? ORDER BY t LIMIT 200", arrayOf(ORIGIN, pushed.toString())).use {
            while (it.moveToNext()) rows.add(it.getLong(0) to it.getDouble(1))
        }
        if (rows.isEmpty()) return
        val offset = ZoneId.systemDefault().rules
        val recs = rows.map { (t, kg) ->
            val at = Instant.ofEpochMilli(t)
            WeightRecord(time = at, zoneOffset = offset.getOffset(at), weight = Mass.kilograms(kg), metadata = Metadata.manualEntry(clientRecordId = "fitair-weight-$t"))
        }
        client(app).insertRecords(recs)
        PrefDao.set(app, K_PUSHED, rows.last().first.toString())
    }
}
