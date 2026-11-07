package com.moblized.spendscan.paperless

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.moblized.spendscan.App
import java.util.concurrent.TimeUnit

/** Sends receipts in the background, whenever there's a connection, even after the app is closed. */
class PaperlessWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val result = app.paperless.run()
        app.prefs.setPaperlessResult(result, System.currentTimeMillis())
        return when (result) {
            SyncResult.Off -> Result.success()
            is SyncResult.Finished -> if (result.processing > 0 && runAttemptCount < MAX_WAITS) Result.retry() else Result.success()
            // The token won't start working by itself. The next save, or a new token, tries again.
            is SyncResult.Stopped -> if (result.problem == SyncProblem.REFUSED) Result.success() else Result.retry()
        }
    }

    private companion object {
        // Checks on Paperless for about half an hour while it reads what it was sent.
        const val MAX_WAITS = 6
    }
}

object PaperlessWork {
    private const val NAME = "paperless"

    /**
     * Sends whatever's waiting. [now] cuts short a wait between tries, for the Send now button. Otherwise it goes
     * after anything already running, so nothing saved in the meantime gets missed.
     */
    fun start(context: Context, now: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<PaperlessWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // WorkManager isn't there in some tests; sending just waits for the next start then.
        runCatching {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(NAME, if (now) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
