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
            val nm = getSystemService(NotificationManager::class.java)
            val channelId = getString(R.string.notification_channel_id)
            // Xóa channel cũ (IMPORTANCE_LOW) nếu có — Android không cho đổi importance tại chỗ
            nm.deleteNotificationChannel(channelId)
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "HeadlessBridge foreground service"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
