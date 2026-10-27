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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import android.webkit.WebView
import android.os.Looper
import android.os.Handler

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
        private val recreateCount = AtomicInteger(0)
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
        // Warm WebView on main so first HTTP call is not cold-start
        Handler(Looper.getMainLooper()).post {
            ensureEngine()
        }
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


    /**
     * Trả về WebViewEngine còn sống. Nếu null/destroyed → tạo WebView mới trên main thread.
     * Không phụ thuộc MainActivity. Gọi từ HTTP worker thread (không gọi trên main — tránh ANR).
     *
     * Android: WebView PHẢI tạo trên UI thread (Looper.getMainLooper()).
     * Dùng applicationContext vì Service không có window token; headless automation không cần attach view.
     */
    fun ensureEngine(): WebViewEngine? {
        val current = engineRef.get()
        if (current != null && !current.isDestroyed() && current.isUsable()) {
            return current
        }
        // Không await nếu đang ở main thread — tránh deadlock
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return createEngineOnMain()
        }
        var created: WebViewEngine? = null
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                created = createEngineOnMain()
            } finally {
                latch.countDown()
            }
        }
        val ok = latch.await(5, TimeUnit.SECONDS)
        if (!ok) {
            Log.e(TAG, "ensureEngine: timeout 5s waiting for main thread")
        }
        return created ?: engineRef.get()
    }

    private fun createEngineOnMain(): WebViewEngine? {
        // Must be called on main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.e(TAG, "createEngineOnMain called off main thread")
            return null
        }
        return try {
            // Dispose previous if any
            engineRef.get()?.let { old ->
                try {
                    if (!old.isDestroyed()) old.destroy()
                } catch (_: Exception) {}
            }
            val webView = WebView(applicationContext)
            // Kích thước tối thiểu — một số API (screenshot/draw) cần non-zero size
            webView.layout(0, 0, 1080, 1920)
            webView.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, 1080, 1920)
            val engine = WebViewEngine(applicationContext, webView)
            engine.antiPopupEnabled = SecretKeyManager.isAntiPopup(this)
            engine.destroyAdsEnabled = SecretKeyManager.isDestroyAds(this)
            engineRef.set(engine)
            val n = recreateCount.incrementAndGet()
            Log.i(TAG, "WebViewEngine created/recreated (count=$n)")
            // Load blank so engine is warm
            webView.loadUrl("about:blank")
            engine
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebViewEngine", e)
            null
        }
    }

    private fun startServer() {
        if (server != null) return
        val port = SecretKeyManager.getPort(this)
        try {
            server = BridgeHttpServer(
                context = this,
                port = port,
                engineProvider = { ensureEngine() },
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
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openPending)
            .addAction(0, getString(R.string.stop_service), stopPending)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
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

        val eng = engineRef.get()
        val ready = eng != null && !eng.isDestroyed() && eng.isUsable()
        return mapOf(
            "headless" to headlessState.get(),
            "foreground" to true,
            "port" to SecretKeyManager.getPort(this),
            "battery" to batteryPct,
            "ram_avail_mb" to (memInfo.availMem / (1024 * 1024)),
            "ram_total_mb" to (memInfo.totalMem / (1024 * 1024)),
            "low_memory" to memInfo.lowMemory,
            "webview_ready" to ready,
            "webview_recreate_count" to recreateCount.get()
        )
    }

    override fun requestStop() {
        stopServer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * User swiped app from recents → restart FGS so bridge stays alive.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved – restarting service")
        val restart = Intent(applicationContext, BridgeServerService::class.java).apply {
            action = ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(restart)
            } else {
                applicationContext.startService(restart)
            }
        } catch (e: Exception) {
            Log.e(TAG, "restart after task removed failed: ${e.message}")
        }
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
