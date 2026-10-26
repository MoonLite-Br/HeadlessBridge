package com.hb.headlessbridge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hb.headlessbridge.service.BridgeServerService

/**
 * Simple keep-alive triggered by AlarmManager / WorkManager.
 */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("KeepAliveReceiver", "Triggered – ensuring service is running")
        if (!BridgeServerService.isRunning()) {
            BridgeServerService.start(context)
        }
    }
}
