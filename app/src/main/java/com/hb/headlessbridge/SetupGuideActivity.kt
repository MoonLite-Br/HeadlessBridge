package com.hb.headlessbridge

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hb.headlessbridge.util.SecretKeyManager

/**
 * OEM-specific battery / autostart guide so FGS is not killed.
 */
class SetupGuideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(0xFF101317.toInt())
        }
        scroll.addView(root)
        setContentView(scroll)

        val mfr = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        val key = when {
            mfr.contains("xiaomi") || brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> "xiaomi"
            mfr.contains("oppo") || brand.contains("oppo") || brand.contains("realme") || mfr.contains("oneplus") -> "oppo"
            mfr.contains("vivo") || brand.contains("vivo") || brand.contains("iqoo") -> "vivo"
            mfr.contains("samsung") || brand.contains("samsung") -> "samsung"
            mfr.contains("huawei") || brand.contains("huawei") || brand.contains("honor") -> "huawei"
            else -> "generic"
        }

        root.addView(TextView(this).apply {
            text = "Giữ app sống trên nền"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 16)
        })
        root.addView(TextView(this).apply {
            text = "Máy: ${Build.MANUFACTURER} ${Build.MODEL}"
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, 0, 0, 24)
        })

        val (title, steps, intentFactory) = guideFor(key)
        root.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(0xFF5B9DFF.toInt())
            setPadding(0, 0, 0, 12)
        })
        root.addView(TextView(this).apply {
            text = steps
            textSize = 15f
            setTextColor(0xFFDDDDDD.toInt())
            setLineSpacing(6f, 1f)
            setPadding(0, 0, 0, 24)
        })

        root.addView(Button(this).apply {
            text = "Mở Settings tương ứng"
            setOnClickListener {
                val intents = intentFactory()
                var opened = false
                for (intent in intents) {
                    try {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (intent.resolveActivity(packageManager) != null) {
                            startActivity(intent)
                            opened = true
                            break
                        }
                    } catch (e: Exception) {
                        // try next
                    }
                }
                if (!opened) {
                    try {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        })
                    } catch (e: Exception) {
                        Toast.makeText(this@SetupGuideActivity, "Không mở được Settings: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })

        root.addView(Button(this).apply {
            text = "Pin / Battery optimization"
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        })

        root.addView(Button(this).apply {
            text = "Đã hiểu, không hiện lại"
            setOnClickListener {
                getSharedPreferences("hb_ui", MODE_PRIVATE)
                    .edit().putBoolean("setup_guide_done", true).apply()
                SecretKeyManager.setAutoStart(this@SetupGuideActivity, true)
                finish()
            }
        })
    }

    private fun guideFor(key: String): Triple<String, String, () -> List<Intent>> {
        return when (key) {
            "xiaomi" -> Triple(
                "Xiaomi / Redmi / POCO (MIUI / HyperOS)",
                "1. Bật Tự động khởi chạy (Autostart) cho HeadlessBridge\n" +
                    "2. Pin → Không hạn chế (No restrictions)\n" +
                    "3. Khóa app trong recent (khóa biểu tượng)\n" +
                    "4. Tắt tiết kiệm pin cho app",
                {
                    listOf(
                        Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
                        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName"))
                    )
                }
            )
            "oppo" -> Triple(
                "OPPO / Realme / OnePlus (ColorOS)",
                "1. Cho phép tự khởi động (Startup manager)\n" +
                    "2. Pin → Cho phép chạy nền\n" +
                    "3. Tắt tối ưu hóa pin cho app\n" +
                    "4. Khóa app trong recent",
                {
                    listOf(
                        Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
                        Intent().setComponent(ComponentName("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity")),
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName"))
                    )
                }
            )
            "vivo" -> Triple(
                "Vivo / iQOO (OriginOS)",
                "1. High background power consumption → Cho phép\n" +
                    "2. Autostart → Bật\n" +
                    "3. Pin → Không hạn chế\n" +
                    "4. Khóa app trong recent",
                {
                    listOf(
                        Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
                        Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName"))
                    )
                }
            )
            "samsung" -> Triple(
                "Samsung (One UI)",
                "1. Pin → Ứng dụng chưa được dùng → Xóa HeadlessBridge khỏi list\n" +
                    "2. Pin → Giới hạn sử dụng nền → Không giới hạn\n" +
                    "3. Tắt 'Đưa ứng dụng vào ngủ'\n" +
                    "4. (Tuỳ chọn) Deep sleeping apps → không thêm app này",
                {
                    listOf(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName")),
                        Intent("com.samsung.android.sm.ACTION_BATTERY").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            )
            "huawei" -> Triple(
                "Huawei / Honor",
                "1. App launch → Quản lý thủ công → bật Auto-launch, Secondary launch, Run in background\n" +
                    "2. Battery → App launch / Không hạn chế\n" +
                    "3. Protected apps → bật\n" +
                    "4. Khóa trong recent",
                {
                    listOf(
                        Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")),
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName"))
                    )
                }
            )
            else -> Triple(
                "Android chung",
                "1. Cài đặt → Ứng dụng → HeadlessBridge → Pin → Không hạn chế\n" +
                    "2. Tắt tối ưu pin / battery saver cho app\n" +
                    "3. Cho phép notification\n" +
                    "4. Không force-stop app",
                {
                    listOf(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName")),
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    )
                }
            )
        }
    }

    companion object {
        fun shouldShow(activity: AppCompatActivity): Boolean {
            return !activity.getSharedPreferences("hb_ui", MODE_PRIVATE)
                .getBoolean("setup_guide_done", false)
        }
    }
}
