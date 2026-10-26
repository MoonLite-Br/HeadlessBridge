package com.hb.headlessbridge.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.security.SecureRandom

/**
 * Generates and persists a 64-character hex secret key.
 * Stored only in MODE_PRIVATE SharedPreferences (never on external storage).
 */
object SecretKeyManager {

    private const val PREFS_NAME = "hb_secure_prefs"
    private const val KEY_SECRET = "secret_key"
    private const val KEY_PORT = "server_port"
    private const val KEY_ANTI_POPUP = "anti_popup"
    private const val KEY_DESTROY_ADS = "destroy_ads"
    private const val KEY_HEADLESS = "headless_mode"
    private const val KEY_AUTO_START = "auto_start_boot"
    private const val DEFAULT_PORT = 8765

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns existing key or generates a new 64-hex key. */
    fun getOrCreateKey(context: Context): String {
        val p = prefs(context)
        val existing = p.getString(KEY_SECRET, null)
        if (!existing.isNullOrBlank() && existing.length == 64) {
            return existing
        }
        val key = generateHexKey(64)
        p.edit().putString(KEY_SECRET, key).apply()
        Log.i("SecretKeyManager", "Generated new secret key (length=${key.length})")
        return key
    }

    fun getKey(context: Context): String = getOrCreateKey(context)

    fun getPort(context: Context): Int =
        prefs(context).getInt(KEY_PORT, DEFAULT_PORT)

    fun setPort(context: Context, port: Int) {
        if (port in 1024..65535) {
            prefs(context).edit().putInt(KEY_PORT, port).apply()
        }
    }

    fun isAntiPopup(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ANTI_POPUP, true)

    fun setAntiPopup(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ANTI_POPUP, enabled).apply()
    }

    fun isDestroyAds(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DESTROY_ADS, true)

    fun setDestroyAds(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DESTROY_ADS, enabled).apply()
    }

    fun isHeadless(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HEADLESS, false)

    fun setHeadless(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HEADLESS, enabled).apply()
    }

    fun isAutoStart(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START, false)

    fun setAutoStart(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }

    private fun generateHexKey(length: Int): String {
        val bytes = ByteArray(length / 2)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Safe logging – never print full key */
    fun maskedKey(key: String): String {
        if (key.length < 12) return "****"
        return key.take(6) + "..." + key.takeLast(4)
    }
}
