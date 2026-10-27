package com.hb.headlessbridge.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import androidx.core.content.ContextCompat
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hb.headlessbridge.MainActivity
import com.hb.headlessbridge.R
import com.hb.headlessbridge.server.BridgeHttpServer
import com.hb.headlessbridge.util.SecretKeyManager
import com.hb.headlessbridge.webview.WebViewEngine
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground Service that hosts the HTTP bridge server and keeps the process alive.
 * Type: dataSync (Android 14+ friendly).
 */
class BridgeServerService : Service(), BridgeHttpServer.HeadlessController {

    companion object {
        private const val TAG = "BridgeServerService"
        const val ACTION_START = "com.hb.headlessbridge.START"
        const val ACTION_STOP = "com.hb.headlessbridge.STOP"
        const val NOTIFICATION_ID = 1001

        private val engineRef = AtomicReference<WebViewEngine?>(null)
        private val headlessState = AtomicReference(false)
        private val serviceInstance = AtomicReference<BridgeServerService?>(null)

        fun setEngine(engine: WebViewEngine?) {
            engineRef.set(engine)
        }

        fun getEngine(): WebViewEngine? = engineRef.get()

        fun isRunning(): Boolean = serviceInstance.get() != null

        fun start(context: Context) {
            val intent = Intent(context, BridgeServerService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BridgeServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var server: BridgeHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): BridgeServerService = this@BridgeServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        serviceInstance.set(this)
        Log.i(TAG, "Service created")
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // API 33+: need POST_NOTIFICATIONS before startForeground with visible notification
                if (Build.VERSION.SDK_INT >= 33) {
                    val granted = ContextCompat.checkSelfPermission(
                        this, Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        Log.w(TAG, "POST_NOTIFICATIONS not granted – still starting FGS (may be silent)")
                    }
                }
                startForeground(NOTIFICATION_ID, buildNotification())
                startServer()
            }
        }
        return START_STICKY
    }

    private fun startServer() {
        if (server != null) return
        val port = SecretKeyManager.getPort(this)
        try {
            server = BridgeHttpServer(
                context = this,
                port = port,
                engineProvider = { engineRef.get() },
                headlessController = this
            )
            server?.start(5000, false)
            Log.i(TAG, "HTTP server started on port $port")
            BridgeHttpServer.log("Server listening on 127.0.0.1:$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
        }
    }

    private fun stopServer() {
        try {
            server?.stop()
        } catch (_: Exception) {}
        server = null
        Log.i(TAG, "HTTP server stopped")
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, BridgeServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, getString(R.string.notification_channel_id))
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_settings)
            .setContentIntent(openPending)
            .addAction(0, getString(R.string.stop_service), stopPending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeadlessBridge::WakeLock")
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
    }

    override fun setHeadless(on: Boolean) {
        headlessState.set(on)
        SecretKeyManager.setHeadless(this, on)
        sendBroadcast(Intent("com.hb.headlessbridge.HEADLESS_CHANGED").apply {
            putExtra("on", on)
            setPackage(packageName)
        })
    }

    override fun isHeadless(): Boolean = headlessState.get()

    override fun setAntiPopup(on: Boolean) {
        SecretKeyManager.setAntiPopup(this, on)
        engineRef.get()?.antiPopupEnabled = on
    }

    override fun setDestroyAds(on: Boolean) {
        SecretKeyManager.setDestroyAds(this, on)
        engineRef.get()?.destroyAdsEnabled = on
    }

    override fun getStatus(): Map<String, Any?> {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val bat = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if (level >= 0) (level * 100 / scale) else -1

        return mapOf(
            "headless" to headlessState.get(),
            "foreground" to true,
            "port" to SecretKeyManager.getPort(this),
            "battery" to batteryPct,
            "ram_avail_mb" to (memInfo.availMem / (1024 * 1024)),
            "ram_total_mb" to (memInfo.totalMem / (1024 * 1024)),
            "low_memory" to memInfo.lowMemory
        )
    }

    override fun requestStop() {
        stopServer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopServer()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        serviceInstance.set(null)
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }
}
