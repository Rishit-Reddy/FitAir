package com.fitair.app.integrations.calendar.ics

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Periodic refresh of all subscribed calendar feeds (scheduled by [IcsFeeds.schedule]). Never fails the job over a feed error. */
class IcsWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        return try {
            IcsFeeds.refresh(applicationContext)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.success()
        }
    }
}
