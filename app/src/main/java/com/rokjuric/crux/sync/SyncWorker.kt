package com.rokjuric.crux.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rokjuric.crux.CruxApp
import java.util.concurrent.TimeUnit

/** Uploads finished workouts and pulls the latest plan, retrying until the server answers. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = (applicationContext as CruxApp).repo
        if (repo.settings.token.isBlank()) return Result.success()
        return if (repo.refresh().isSuccess) Result.success() else Result.retry()
    }

    companion object {
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Soon, once there is a network. */
        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        /** Keeps the plan fresh even if the app is not opened for a while. */
        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(online)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
