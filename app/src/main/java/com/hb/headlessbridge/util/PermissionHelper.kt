package com.hb.headlessbridge.util

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Runtime permission helper using Activity Result API.
 * Request only when feature is actually used (mic / images / notifications).
 */
class PermissionHelper(private val activity: ComponentActivity) {

    private var onResult: ((Map<String, Boolean>) -> Unit)? = null

    private val launcher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            onResult?.invoke(result)
            onResult = null
            // If any permanently denied → guide to Settings
            val permanentlyDenied = result.filter { !it.value }.keys.any { perm ->
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
            }
            if (permanentlyDenied) {
                showSettingsDialog()
            }
        }

    /** Permissions needed for mic + images + notifications depending on SDK. */
    fun requiredPermissions(): Array<String> {
        val list = mutableListOf<String>()
        list += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.READ_MEDIA_IMAGES
            list += Manifest.permission.POST_NOTIFICATIONS
        } else {
            list += Manifest.permission.READ_EXTERNAL_STORAGE
            // POST_NOTIFICATIONS not required below 33
        }
        return list.toTypedArray()
    }

    fun hasAll(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
    }

    fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Request all relevant permissions. Call only when user triggers a feature.
     */
    fun requestAll(callback: (Map<String, Boolean>) -> Unit = {}) {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            callback(requiredPermissions().associateWith { true })
            return
        }
        onResult = callback
        launcher.launch(missing.toTypedArray())
    }

    fun request(vararg permissions: String, callback: (Map<String, Boolean>) -> Unit = {}) {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            callback(permissions.associateWith { true })
            return
        }
        onResult = callback
        launcher.launch(missing.toTypedArray())
    }

    private fun showSettingsDialog() {
        AlertDialog.Builder(activity)
            .setTitle("Cần cấp quyền")
            .setMessage(
                "Bạn đã từ chối một số quyền cần thiết. " +
                    "Vui lòng mở Cài đặt ứng dụng và cấp quyền thủ công."
            )
            .setPositiveButton("Mở Cài đặt") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.packageName, null)
                )
                activity.startActivity(intent)
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }
}
