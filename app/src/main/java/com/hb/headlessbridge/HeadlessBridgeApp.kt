package com.hb.headlessbridge

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.hb.headlessbridge.util.SecretKeyManager
import com.hb.headlessbridge.worker.CacheCleanupWorker

class HeadlessBridgeApp : Application() {

    companion object {
        const val TAG = "HeadlessBridge"
        lateinit var instance: HeadlessBridgeApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        // Ensure secret key exists early
        SecretKeyManager.getOrCreateKey(this) // persistent token — only rotate on Reset or 24h TTL
        CacheCleanupWorker.schedule(this)
        Log.i(TAG, "Application started")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                getString(R.string.notification_channel_id),
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps HeadlessBridge alive in background"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }
}
