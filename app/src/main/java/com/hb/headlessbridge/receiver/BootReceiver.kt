package com.hb.headlessbridge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.hb.headlessbridge.service.BridgeServerService
import com.hb.headlessbridge.util.SecretKeyManager

/**
 * Start Foreground Service after BOOT_COMPLETED.
 * Android 12+ allows startForegroundService from BOOT_COMPLETED receiver.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        if (!SecretKeyManager.isBackgroundRun(context) && !SecretKeyManager.isAutoStart(context)) {
            Log.i(TAG, "Boot: background_run/auto_start off – skip")
            return
        }
        Log.i(TAG, "Boot: starting BridgeServerService")
        val i = Intent(context, BridgeServerService::class.java).apply {
            this.action = BridgeServerService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Boot start failed: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
