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
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private lateinit var btnHeadlessCopy: View
    private lateinit var btnReload: ImageButton
    private lateinit var statusDot: View
    private lateinit var tvStatus: TextView
    private lateinit var tvUrl: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            uiHandler.postDelayed(this, 1000)
        }
    }

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
        btnHeadlessCopy = findViewById(R.id.btn_headless_copy)
        btnReload = findViewById(R.id.btn_reload)
        statusDot = findViewById(R.id.status_dot)
        tvStatus = findViewById(R.id.tv_status)
        tvUrl = findViewById(R.id.tv_url)

        engine = WebViewEngine(this, webView).also {
            it.antiPopupEnabled = SecretKeyManager.isAntiPopup(this)
            it.destroyAdsEnabled = SecretKeyManager.isDestroyAds(this)
            BridgeServerService.setEngine(it)
        }

        // Load default page
        webView.loadUrl("https://www.google.com")

        btnSettings.setOnClickListener { showSettingsSheet() }
        btnReload.setOnClickListener { webView.reload() }
        btnHeadlessCopy.setOnClickListener { copyKeyToClipboard() }
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
        refreshStatus()

        // First-run OEM keep-alive guide
        if (SetupGuideActivity.shouldShow(this)) {
            startActivity(Intent(this, SetupGuideActivity::class.java))
        }
    }

    /** Top bar + headless card. Runs on the main thread, so only touch cheap, non-blocking state. */
    private fun refreshStatus() {
        val running = BridgeServerService.isRunning()
        val port = SecretKeyManager.getPort(this)
        val color = getColor(if (running) R.color.status_ok else R.color.status_off)
        statusDot.backgroundTintList = ColorStateList.valueOf(color)
        tvStatus.text = if (running) "Bridge đang chạy · 127.0.0.1:$port" else "Bridge đã dừng"
        // webView.url is safe on the main thread (engine.getCurrentUrl() may block on it).
        tvUrl.text = webView.url?.takeIf { it.isNotBlank() } ?: "about:blank"
        tvHeadlessStatus.text = if (running) "127.0.0.1:$port" else "Đã dừng"
    }

    private fun copyKeyToClipboard() {
        val key = SecretKeyManager.getKey(this)
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("hb_token", key))
        Toast.makeText(
            this,
            "Đã sao chép key (${SecretKeyManager.maskedKey(key)}). Dùng: Authorization: Bearer <key>",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun applyHeadlessUi(on: Boolean) {
        // Keep the WebView VISIBLE and just cover it with the opaque overlay (it is drawn on
        // top in activity_main.xml). A GONE WebView has size 0 and isn't rendered, which made
        // /screenshot return a blank 1x1 image and throttled JS in headless mode.
        webView.visibility = View.VISIBLE
        headlessOverlay.visibility = if (on) View.VISIBLE else View.GONE
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
        Toast.makeText(this, if (on) "Đã bật headless" else "Đã tắt headless", Toast.LENGTH_SHORT).show()
    }

    private fun showSettingsSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_settings, null)
        dialog.setContentView(view)

        val rowCopyKey = view.findViewById<View>(R.id.row_copy_key)
        val switchAntiPopup = view.findViewById<SwitchMaterial>(R.id.switch_anti_popup)
        val switchDestroyAds = view.findViewById<SwitchMaterial>(R.id.switch_destroy_ads)
        val switchHeadless = view.findViewById<SwitchMaterial>(R.id.switch_headless)
        val rowSetupGuide = view.findViewById<View>(R.id.row_setup_guide)
        val etPort = view.findViewById<EditText>(R.id.et_port)
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
        tvCache.text = "Dọn cache (${report.mb(report.totalBytes)} MB)"
        tvUa.text = "Key ${SecretKeyManager.maskedKey(SecretKeyManager.getKey(this))} · ${engine?.getUserAgent()?.take(80) ?: ""}"

        rowCopyKey.setOnClickListener { copyKeyToClipboard() }

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
            Toast.makeText(this, if (isChecked) "/eval và /navigate chạy được khi app ở nền" else "Chỉ chạy khi app đang mở", Toast.LENGTH_SHORT).show()
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
            tvCache.text = "Dọn cache (${after.mb(after.totalBytes)} MB)"
            Toast.makeText(this, "Đã dọn cache → ${after.mb(after.totalBytes)} MB", Toast.LENGTH_SHORT).show()
        }

        rowPermissions.setOnClickListener {
            permissionHelper.requestAll { result ->
                val granted = result.values.count { it }
                Toast.makeText(this, "Đã cấp $granted/${result.size} quyền", Toast.LENGTH_SHORT).show()
            }
        }

        rowDeveloper.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, DeveloperActivity::class.java))
        }

        rowSetupGuide.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, SetupGuideActivity::class.java))
        }

        etPort.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val port = etPort.text.toString().toIntOrNull() ?: 8765
                if (port in 1024..65535) {
                    SecretKeyManager.setPort(this, port)
                    Toast.makeText(this, "Đã lưu port. Khởi động lại service để áp dụng.", Toast.LENGTH_SHORT).show()
                    refreshStatus()
                }
            }
        }

        rowIgnoreBattery.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        settingsDialog = dialog
        dialog.show()
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
                Toast.makeText(this, "Đã bỏ tối ưu hoá pin rồi", Toast.LENGTH_SHORT).show()
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
        uiHandler.removeCallbacks(statusTicker)
        uiHandler.post(statusTicker)
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
        uiHandler.removeCallbacks(statusTicker)
        isInForeground = false
        // WebView.onPause() freezes JS timers/rendering. While the bridge service is running
        // the page must keep working (Termux is in front, this app is "paused") – that was
        // the real reason /eval & /navigate only worked with the app on screen.
        if (!BridgeServerService.isRunning()) {
            engine?.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(headlessReceiver)
        } catch (_: Exception) {}
        // This Activity's WebView dies with it. Unregister it so the service stops handing
        // out a dead/leaked view and builds its own detached engine on the next request
        // (setEngine destroys + detaches the old one; cookies live in CookieManager and survive).
        engine?.let { mine ->
            if (BridgeServerService.getEngine() === mine) {
                BridgeServerService.setEngine(null)
            } else {
                mine.destroy()
            }
        }
        engine = null
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
