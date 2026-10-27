package com.hb.headlessbridge.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Secure API token manager (mục 7).
 * - 32-byte random token (64 hex) via SecureRandom
 * - EncryptedSharedPreferences (AES256-GCM + Android Keystore)
 * - Generated once and kept; rotated only when the user asks (rotate()).
 *   (A silent 24h expiry used to invalidate the token on the first process start after a
 *   day, so every Termux script suddenly got 401.)
 * - NEVER expose via public HTTP /key endpoint
 */
object SecretKeyManager {

    private const val TAG = "SecretKeyManager"
    private const val PREFS_NAME = "hb_secure_prefs_enc"
    private const val KEY_TOKEN = "api_token"
    private const val KEY_TOKEN_CREATED = "api_token_created"
    private const val KEY_PORT = "server_port"
    private const val KEY_ANTI_POPUP = "anti_popup"
    private const val KEY_DESTROY_ADS = "destroy_ads"
    private const val KEY_HEADLESS = "headless_mode"
    private const val KEY_AUTO_START = "auto_start_boot"
    private const val KEY_BG_RUN = "background_run"
    private const val KEY_ALLOW_BG_DEBUG = "allow_background_debug"
    private const val KEY_OVERLAY = "overlay_enabled"
    private const val DEFAULT_PORT = 8765

    @Volatile private var cachedToken: String? = null
    @Volatile private var prefsInstance: SharedPreferences? = null

    /**
     * Build the encrypted prefs ONCE. It used to be rebuilt (Keystore + MasterKey) on every
     * single call – every isHeadless()/getPort()/isAntiPopup() – including on HTTP threads.
     */
    private fun prefs(context: Context): SharedPreferences {
        prefsInstance?.let { return it }
        return synchronized(this) {
            prefsInstance ?: createPrefs(context).also { prefsInstance = it }
        }
    }

    private fun createPrefs(context: Context): SharedPreferences {
        val appCtx = context.applicationContext
        return try {
            val masterKey = MasterKey.Builder(appCtx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appCtx,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences unavailable, fallback: ${e.message}")
            appCtx.getSharedPreferences("hb_secure_prefs_fallback", Context.MODE_PRIVATE)
        }
    }

    /**
     * Return the current token. A new one is generated only if none is stored (or it is
     * malformed) or when [forceRotate] is true.
     */
    fun getOrCreateKey(context: Context, forceRotate: Boolean = false): String {
        if (!forceRotate) {
            cachedToken?.let { return it }
        }
        val p = prefs(context)
        val existing = p.getString(KEY_TOKEN, null)
        if (!forceRotate && !existing.isNullOrBlank() && existing.length == 64) {
            cachedToken = existing
            return existing
        }
        val token = generateHexKey(64)
        p.edit()
            .putString(KEY_TOKEN, token)
            .putLong(KEY_TOKEN_CREATED, System.currentTimeMillis())
            .apply()
        cachedToken = token
        Log.i(TAG, "Token rotated (masked=${maskedKey(token)})")
        return token
    }

    fun getKey(context: Context): String = getOrCreateKey(context)

    fun rotate(context: Context): String = getOrCreateKey(context, forceRotate = true)

    fun getPort(context: Context): Int = prefs(context).getInt(KEY_PORT, DEFAULT_PORT)

    fun setPort(context: Context, port: Int) {
        if (port in 1024..65535) prefs(context).edit().putInt(KEY_PORT, port).apply()
    }

    fun isAntiPopup(context: Context): Boolean = prefs(context).getBoolean(KEY_ANTI_POPUP, true)
    fun setAntiPopup(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_ANTI_POPUP, v).apply() }

    fun isDestroyAds(context: Context): Boolean = prefs(context).getBoolean(KEY_DESTROY_ADS, true)
    fun setDestroyAds(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_DESTROY_ADS, v).apply() }

    fun isHeadless(context: Context): Boolean = prefs(context).getBoolean(KEY_HEADLESS, false)
    fun setHeadless(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_HEADLESS, v).apply() }

    fun isAutoStart(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_START, false)
    fun setAutoStart(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_AUTO_START, v).apply() }

    fun isBackgroundRun(context: Context): Boolean = prefs(context).getBoolean(KEY_BG_RUN, true)
    fun setBackgroundRun(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_BG_RUN, v).apply() }

    /**
     * When true, /eval and /navigate work even if app is backgrounded.
     * Default is now TRUE: the bridge is token-protected and bound to 127.0.0.1, and callers
     * are almost always Termux (i.e. this app is NOT in the foreground). With the old default
     * (false) the main use case answered 403 until the switch was found in settings.
     */
    fun isAllowBackgroundDebug(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALLOW_BG_DEBUG, true)
    fun setAllowBackgroundDebug(context: Context, v: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_BG_DEBUG, v).apply()
    }

    fun isOverlay(context: Context): Boolean = prefs(context).getBoolean(KEY_OVERLAY, false)
    fun setOverlay(context: Context, v: Boolean) { prefs(context).edit().putBoolean(KEY_OVERLAY, v).apply() }

    private fun generateHexKey(length: Int): String {
        val bytes = ByteArray(length / 2)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun maskedKey(key: String): String {
        if (key.length < 12) return "****"
        return key.take(6) + "..." + key.takeLast(4)
    }
}
