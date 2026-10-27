package com.hb.headlessbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Developer / ADB helper screen.
 * Shows step-by-step guide + current ADB status + Wi-Fi IP + copyable adb commands.
 * Does NOT attempt to enable ADB itself (impossible for normal apps).
 */
class DeveloperActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(0xFF1E1E1E.toInt())
        }
        scroll.addView(root)
        setContentView(scroll)

        fun section(title: String, body: String) {
            root.addView(TextView(this).apply {
                text = title
                textSize = 18f
                setTextColor(0xFF1A73E8.toInt())
                setPadding(0, 32, 0, 8)
            })
            root.addView(TextView(this).apply {
                text = body
                textSize = 14f
                setTextColor(0xFFDDDDDD.toInt())
                setLineSpacing(0f, 1.25f)
            })
        }

        // Status
        val adbOn = try {
            Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        } catch (_: Exception) {
            false
        }
        val ip = getWifiIp()
        val port = 5555 // common wireless debug port placeholder

        section(
            "Trạng thái hiện tại",
            "ADB (USB debugging): ${if (adbOn) "BẬT" else "TẮT"}\n" +
                "IP Wi-Fi: ${ip ?: "không có (bật Wi-Fi)"}\n" +
                "Lưu ý: Wireless Debugging port lấy trong Developer Options (thường 3xxxx)."
        )

        section(
            "Bước 1 – Bật Developer Options",
            "1. Mở Cài đặt → Về điện thoại (About phone)\n" +
                "2. Chạm 7 lần vào \"Số bản dựng\" (Build number)\n" +
                "3. Quay lại Cài đặt → Hệ thống → Tùy chọn nhà phát triển"
        )

        section(
            "Bước 2 – Wireless Debugging (Android 11+)",
            "1. Trong Developer Options → bật \"Gỡ lỗi không dây\" (Wireless debugging)\n" +
                "2. Chạm \"Gỡ lỗi không dây\" → \"Ghép nối thiết bị bằng mã\"\n" +
                "3. Ghi lại mã 6 số và cổng (vd 37123)\n" +
                "4. Trên máy tính / Termux chạy lệnh bên dưới"
        )

        val pairCmd = "adb pair ${ip ?: "IP"}:<PAIR_PORT> <PAIR_CODE>"
        val connectCmd = "adb connect ${ip ?: "IP"}:<DEBUG_PORT>"

        section("Lệnh ADB", "$pairCmd\n$connectCmd")

        val btnCopy = Button(this).apply {
            text = "Copy lệnh ADB"
            setOnClickListener {
                val text = "$pairCmd\n$connectCmd"
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("adb", text))
                Toast.makeText(this@DeveloperActivity, "Đã copy lệnh ADB", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(btnCopy)

        val btnDevSettings = Button(this).apply {
            text = "Mở Developer Options"
            setOnClickListener {
                try {
                    startActivity(android.content.Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                } catch (_: Exception) {
                    startActivity(android.content.Intent(Settings.ACTION_SETTINGS))
                }
            }
        }
        root.addView(btnDevSettings)

        section(
            "Chrome DevTools (WebView)",
            "1. App đã bật WebView.setWebContentsDebuggingEnabled(true)\n" +
                "2. Trên PC mở Chrome → chrome://inspect\n" +
                "3. Kết nối ADB xong sẽ thấy WebView của HeadlessBridge"
        )
    }

    @Suppress("DEPRECATION")
    private fun getWifiIp(): String? {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipInt = wm.connectionInfo?.ipAddress ?: return null
            if (ipInt == 0) return null
            Formatter.formatIpAddress(ipInt)
        } catch (_: Exception) {
            null
        }
    }
}
