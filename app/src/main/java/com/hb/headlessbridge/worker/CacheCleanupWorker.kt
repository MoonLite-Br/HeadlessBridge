package com.hb.headlessbridge.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.hb.headlessbridge.util.CacheCleaner
import java.util.concurrent.TimeUnit

/**
 * Runs every 7 days. If app_webview > 30MB, clears cache.
 */
class CacheCleanupWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            val report = CacheCleaner.measure(applicationContext)
            Log.i(TAG, "Periodic check: $report")
            if (report.appWebViewBytes > CacheCleaner.THRESHOLD_BYTES) {
                Log.i(TAG, "Threshold exceeded – clearing cache")
                // No WebView instance here; clear disk only
                CacheCleaner.clear(applicationContext, webView = null)
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "CacheCleanupWorker failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "CacheCleanupWorker"
        private const val UNIQUE_NAME = "hb_cache_cleanup_weekly"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<CacheCleanupWorker>(7, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "Scheduled weekly cache cleanup")
        }
    }
}
