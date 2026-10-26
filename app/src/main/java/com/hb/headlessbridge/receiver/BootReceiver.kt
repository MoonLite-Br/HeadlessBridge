package com.hb.headlessbridge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hb.headlessbridge.service.BridgeServerService
import com.hb.headlessbridge.util.SecretKeyManager

/**
 * Optional auto-start after reboot if user enabled the flag.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            if (SecretKeyManager.isAutoStart(context)) {
                Log.i("BootReceiver", "Auto-starting BridgeServerService")
                BridgeServerService.start(context)
            }
        }
    }
}
