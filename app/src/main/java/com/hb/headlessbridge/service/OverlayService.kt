package com.hb.headlessbridge.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Optional SYSTEM_ALERT_WINDOW overlay.
 * Currently a stub – can be extended to show a tiny floating indicator
 * when headless mode is active.
 */
class OverlayService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("OverlayService", "Overlay service started (stub)")
        return START_STICKY
    }
}
