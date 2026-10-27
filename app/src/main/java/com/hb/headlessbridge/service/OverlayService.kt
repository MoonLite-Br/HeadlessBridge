package com.hb.headlessbridge.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.net.toUri
import com.hb.headlessbridge.util.SecretKeyManager

/**
 * Tiny floating status bubble: "Bridge: ON · 8765"
 * Requires SYSTEM_ALERT_WINDOW. Only started when user explicitly enables overlay.
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        const val ACTION_SHOW = "com.hb.headlessbridge.OVERLAY_SHOW"
        const val ACTION_HIDE = "com.hb.headlessbridge.OVERLAY_HIDE"

        fun canDraw(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else true

        /** Open system screen to grant overlay permission. */
        fun requestPermission(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:${context.packageName}".toUri()
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }

        fun show(context: Context) {
            if (!canDraw(context)) {
                requestPermission(context)
                return
            }
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_SHOW))
        }

        fun hide(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_HIDE))
        }
    }

    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                removeBubble()
                stopSelf()
            }
            else -> {
                if (!canDraw(this)) {
                    Log.w(TAG, "Overlay permission revoked – stopping")
                    stopSelf()
                    return START_NOT_STICKY
                }
                showBubble()
            }
        }
        return START_STICKY
    }

    private fun showBubble() {
        if (bubble != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val port = SecretKeyManager.getPort(this)
        val tv = TextView(this).apply {
            text = "Bridge: ON · $port"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xCC1A73E8.toInt())
            setPadding(28, 16, 28, 16)
            textSize = 12f
            setOnClickListener {
                val launch = packageManager.getLaunchIntentForPackage(packageName)
                launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            }
        }
        bubble = tv

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 120
        }
        try {
            windowManager?.addView(tv, params)
            Log.i(TAG, "Overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay: ${e.message}")
            stopSelf()
        }
    }

    private fun removeBubble() {
        bubble?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        bubble = null
    }

    override fun onDestroy() {
        removeBubble()
        super.onDestroy()
    }
}
