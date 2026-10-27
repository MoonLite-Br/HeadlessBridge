package com.hb.headlessbridge

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.switchmaterial.SwitchMaterial
import com.hb.headlessbridge.service.BridgeServerService
import com.hb.headlessbridge.util.SecretKeyManager
import com.hb.headlessbridge.service.OverlayService
import com.hb.headlessbridge.util.PermissionHelper
import com.hb.headlessbridge.util.CacheCleaner
import com.hb.headlessbridge.webview.WebViewEngine

class MainActivity : AppCompatActivity() {

    companion object {
        /** Used by BridgeHttpServer to gate /eval and /navigate. */
        @Volatile var isInForeground: Boolean = false
    }

    private lateinit var webView: WebView
    private lateinit var headlessOverlay: LinearLayout
    private lateinit var btnSettings: ImageButton
    private lateinit var tvHeadlessToggle: TextView
    private lateinit var tvHeadlessStatus: TextView

    private var engine: WebViewEngine? = null
    private var settingsDialog: BottomSheetDialog? = null
    private lateinit var permissionHelper: PermissionHelper

    private val headlessReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val on = intent?.getBooleanExtra("on", false) ?: return
            runOnUiThread { applyHeadlessUi(on) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        permissionHelper = PermissionHelper(this)

        // Immersive-ish
        WindowInsetsControllerCompat(window, window.decorView).let { c ->
            c.hide(WindowInsetsCompat.Type.navigationBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        webView = findViewById(R.id.webview)
        headlessOverlay = findViewById(R.id.headless_overlay)
        btnSettings = findViewById(R.id.btn_settings)
        tvHeadlessToggle = findViewById(R.id.tv_headless_toggle)
        tvHeadlessStatus = findViewById(R.id.tv_headless_status)

        engine = WebViewEngine(this, webView).also {
            it.antiPopupEnabled = SecretKeyManager.isAntiPopup(this)
            it.destroyAdsEnabled = SecretKeyManager.isDestroyAds(this)
            BridgeServerService.setEngine(it)
        }

        // Load default page
        webView.loadUrl("https://www.google.com")

        btnSettings.setOnClickListener { showSettingsSheet() }
        tvHeadlessToggle.setOnClickListener {
            setHeadlessMode(false)
        }

        // Restore headless state
        val isHeadless = SecretKeyManager.isHeadless(this)
        applyHeadlessUi(isHeadless)

        // Start foreground service
        ensureBridgeService() // xin POST_NOTIFICATIONS rồi mới start FGS

        // Register receiver
        val filter = IntentFilter("com.hb.headlessbridge.HEADLESS_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(headlessReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(headlessReceiver, filter)
        }

        // Update status text
        val port = SecretKeyManager.getPort(this)
        tvHeadlessStatus.text = "Bridge · $port"

        // First-run OEM keep-alive guide
        if (SetupGuideActivity.shouldShow(this)) {
            startActivity(Intent(this, SetupGuideActivity::class.java))
        }
    }

    private fun applyHeadlessUi(on: Boolean) {
        if (on) {
            webView.visibility = View.GONE
            headlessOverlay.visibility = View.VISIBLE
        } else {
            headlessOverlay.visibility = View.GONE
            webView.visibility = View.VISIBLE
        }
        SecretKeyManager.setHeadless(this, on)
    }

    private fun setHeadlessMode(on: Boolean) {
        applyHeadlessUi(on)
        // Also notify service / sync
        BridgeServerService.getEngine()?.let { /* already shared */ }
        // Broadcast so service knows
        sendBroadcast(Intent("com.hb.headlessbridge.HEADLESS_CHANGED").apply {
            putExtra("on", on)
            setPackage(packageName)
        })
        // Ensure service is running when headless
        if (on) {
            ensureBridgeService() // xin POST_NOTIFICATIONS rồi mới start FGS
        }
        Toast.makeText(this, if (on) "Headless ON" else "Headless OFF", Toast.LENGTH_SHORT).show()
    }

    private fun showSettingsSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_settings, null)
        dialog.setContentView(view)

        val rowCopyKey = view.findViewById<View>(R.id.row_copy_key)
        val switchAntiPopup = view.findViewById<SwitchMaterial>(R.id.switch_anti_popup)
        val switchDestroyAds = view.findViewById<SwitchMaterial>(R.id.switch_destroy_ads)
        val switchHeadless = view.findViewById<SwitchMaterial>(R.id.switch_headless)
        val rowWireless = view.findViewById<View>(R.id.row_wireless_debug)
        val etPort = view.findViewById<EditText>(R.id.et_port)
        val rowForceGc = view.findViewById<View>(R.id.row_force_gc)
        val rowIgnoreBattery = view.findViewById<View>(R.id.row_ignore_battery)
        val tvUa = view.findViewById<TextView>(R.id.tv_ua_info)
        val tvCache = view.findViewById<TextView>(R.id.tv_cache_size)
        val rowClearCache = view.findViewById<View>(R.id.row_clear_cache)
        val switchBgRun = view.findViewById<SwitchMaterial>(R.id.switch_bg_run)
        val switchBgDebug = view.findViewById<SwitchMaterial>(R.id.switch_bg_debug)
        val switchOverlay = view.findViewById<SwitchMaterial>(R.id.switch_overlay)
        val rowPermissions = view.findViewById<View>(R.id.row_permissions)
        val rowDeveloper = view.findViewById<View>(R.id.row_developer)

        // Init values
        switchAntiPopup.isChecked = SecretKeyManager.isAntiPopup(this)
        switchDestroyAds.isChecked = SecretKeyManager.isDestroyAds(this)
        switchHeadless.isChecked = SecretKeyManager.isHeadless(this)
        switchBgRun.isChecked = SecretKeyManager.isBackgroundRun(this)
        switchBgDebug.isChecked = SecretKeyManager.isAllowBackgroundDebug(this)
        switchOverlay.isChecked = SecretKeyManager.isOverlay(this) && OverlayService.canDraw(this)
        etPort.setText(SecretKeyManager.getPort(this).toString())
        val report = CacheCleaner.measure(this)
        tvCache.text = "🧹  Dọn cache (${report.mb(report.totalBytes)} MB)"
        tvUa.text = "UA: ${engine?.getUserAgent()?.take(60) ?: "…"}… | Token: ${SecretKeyManager.maskedKey(SecretKeyManager.getKey(this))}"

        rowCopyKey.setOnClickListener {
            val key = SecretKeyManager.getKey(this)
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("hb_token", key))
            Toast.makeText(this, "Token copied (${SecretKeyManager.maskedKey(key)}) — dùng: Authorization: Bearer <token>", Toast.LENGTH_LONG).show()
        }

        switchAntiPopup.setOnCheckedChangeListener { _, isChecked ->
            SecretKeyManager.setAntiPopup(this, isChecked)
            engine?.antiPopupEnabled = isChecked
        }
        switchDestroyAds.setOnCheckedChangeListener { _, isChecked ->
            SecretKeyManager.setDestroyAds(this, isChecked)
            engine?.destroyAdsEnabled = isChecked
        }
        switchHeadless.setOnCheckedChangeListener { _, isChecked ->
            setHeadlessMode(isChecked)
        }

        switchBgRun.setOnCheckedChangeListener { _, isChecked ->
            SecretKeyManager.setBackgroundRun(this, isChecked)
            if (isChecked) {
                AlertDialog.Builder(this)
                    .setTitle("Chạy nền")
                    .setMessage("Foreground Service sẽ giữ app sống và tốn pin. Chỉ bật khi cần automation liên tục.")
                    .setPositiveButton("OK") { _, _ -> ensureBridgeService() }
                    .setNegativeButton("Huỷ") { _, _ ->
                        switchBgRun.isChecked = false
                        SecretKeyManager.setBackgroundRun(this, false)
                    }
                    .show()
            } else {
                BridgeServerService.stop(this)
            }
        }

        switchBgDebug.setOnCheckedChangeListener { _, isChecked ->
            SecretKeyManager.setAllowBackgroundDebug(this, isChecked)
            Toast.makeText(this, if (isChecked) " /eval & /navigate cho phép khi background" else "Chỉ foreground", Toast.LENGTH_SHORT).show()
        }

        switchOverlay.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!OverlayService.canDraw(this)) {
                    OverlayService.requestPermission(this)
                    switchOverlay.isChecked = false
                    return@setOnCheckedChangeListener
                }
                SecretKeyManager.setOverlay(this, true)
                OverlayService.show(this)
            } else {
                SecretKeyManager.setOverlay(this, false)
                OverlayService.hide(this)
            }
        }

        rowClearCache.setOnClickListener {
            val after = CacheCleaner.clear(this, webView)
            tvCache.text = "🧹  Dọn cache (${after.mb(after.totalBytes)} MB)"
            Toast.makeText(this, "Đã dọn cache → ${after.mb(after.totalBytes)} MB", Toast.LENGTH_SHORT).show()
        }

        rowPermissions.setOnClickListener {
            permissionHelper.requestAll { result ->
                val granted = result.values.count { it }
                Toast.makeText(this, "Quyền: $granted/${result.size} granted", Toast.LENGTH_SHORT).show()
            }
        }

        rowDeveloper.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, DeveloperActivity::class.java))
        }

        rowWireless.setOnClickListener {
            dialog.dismiss()
            showWirelessDebugDialog()
        }

        etPort.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val port = etPort.text.toString().toIntOrNull() ?: 8765
                if (port in 1024..65535) {
                    SecretKeyManager.setPort(this, port)
                    Toast.makeText(this, "Port saved. Restart service to apply.", Toast.LENGTH_SHORT).show()
                    tvHeadlessStatus.text = "Bridge · $port"
                }
            }
        }

        rowForceGc.setOnClickListener {
            System.gc()
            Runtime.getRuntime().gc()
            Toast.makeText(this, "GC requested", Toast.LENGTH_SHORT).show()
        }

        rowIgnoreBattery.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        settingsDialog = dialog
        dialog.show()
    }

    private fun showWirelessDebugDialog() {
        val input = EditText(this).apply {
            hint = "pairing code"
            setPadding(40, 30, 40, 30)
        }
        val portInput = EditText(this).apply {
            hint = "pair port (e.g. 37xxx)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setPadding(40, 30, 40, 30)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
            addView(input)
            addView(portInput)
        }

        AlertDialog.Builder(this)
            .setTitle("Wireless Debugging")
            .setMessage("1. Enable Wireless debugging in Developer options\n2. Tap 'Pair device with pairing code'\n3. Enter code + port below\n\nIf app has no shell permission, copy the adb command to Termux.")
            .setView(container)
            .setPositiveButton("Pair / Show command") { _, _ ->
                val code = input.text.toString().trim()
                val pairPort = portInput.text.toString().trim()
                if (code.isBlank() || pairPort.isBlank()) {
                    Toast.makeText(this, "Need both code and port", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val cmd = "adb pair 127.0.0.1:$pairPort $code"
                // Try Runtime.exec (usually fails without shell/root)
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                    val exit = process.waitFor()
                    if (exit == 0) {
                        Toast.makeText(this, "Pair command executed", Toast.LENGTH_SHORT).show()
                    } else {
                        // Fallback: copy command
                        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("adb_pair", cmd))
                        Toast.makeText(this, "Command copied – paste in Termux", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("adb_pair", cmd))
                    Toast.makeText(this, "No shell access. Command copied to clipboard:\n$cmd", Toast.LENGTH_LONG).show()
                }
                // Always enable WebView debugging
                WebView.setWebContentsDebuggingEnabled(true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    // Fallback to general battery settings
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            } else {
                Toast.makeText(this, "Already ignoring battery optimizations", Toast.LENGTH_SHORT).show()
            }
        }
    }


    /**
     * Android 13+: xin POST_NOTIFICATIONS trước, rồi mới start FGS
     * để notification hiện được. Sau khi granted → restart service.
     */
    private fun ensureBridgeService() {
        if (!SecretKeyManager.isBackgroundRun(this)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            !permissionHelper.has(android.Manifest.permission.POST_NOTIFICATIONS)
        ) {
            // Xin quyền trước — sau khi user trả lời mới start/restart FGS
            permissionHelper.request(android.Manifest.permission.POST_NOTIFICATIONS) { result ->
                val granted = result[android.Manifest.permission.POST_NOTIFICATIONS] == true
                Log.i("MainActivity", "POST_NOTIFICATIONS granted=$granted → start FGS")
                BridgeServerService.stop(this)
                BridgeServerService.start(this)
            }
            return
        }
        if (!BridgeServerService.isRunning()) {
            BridgeServerService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        engine?.onResume()
        // Ensure service running only if user enabled background run
        ensureBridgeService()
        // Hide overlay if permission revoked
        if (SecretKeyManager.isOverlay(this) && !OverlayService.canDraw(this)) {
            OverlayService.hide(this)
            SecretKeyManager.setOverlay(this, false)
        }
    }

    override fun onPause() {
        isInForeground = false
        engine?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(headlessReceiver)
        } catch (_: Exception) {}
        // Do NOT destroy engine / WebView here – service may still need it.
        // Only clear if activity is finishing permanently and we want to.
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack() && webView.visibility == View.VISIBLE) {
            webView.goBack()
        } else {
            // Move to background instead of finish – keep process alive
            moveTaskToBack(true)
        }
    }
}
