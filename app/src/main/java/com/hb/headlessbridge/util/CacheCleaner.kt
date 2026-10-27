package com.hb.headlessbridge.util

import android.content.Context
import android.util.Log
import android.webkit.WebView
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Measures and cleans WebView / app cache directories that can balloon to 60MB+.
 */
object CacheCleaner {

    private const val TAG = "CacheCleaner"
    /** Threshold above which periodic worker will clear (bytes). */
    const val THRESHOLD_BYTES = 30L * 1024 * 1024  // 30 MB

    data class CacheReport(
        val appWebViewBytes: Long,
        val cacheBytes: Long,
        val databasesBytes: Long,
        val filesBytes: Long,
        val totalBytes: Long
    ) {
        fun mb(n: Long) = "%.1f".format(n / (1024.0 * 1024.0))
        override fun toString(): String =
            "app_webview=${mb(appWebViewBytes)}MB cache=${mb(cacheBytes)}MB " +
                "db=${mb(databasesBytes)}MB files=${mb(filesBytes)}MB total=${mb(totalBytes)}MB"
    }

    /** Recursively sum file sizes under [dir]. */
    fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        return try {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (e: Exception) {
            Log.w(TAG, "dirSize failed for ${dir.path}: ${e.message}")
            0L
        }
    }

    fun measure(context: Context): CacheReport {
        val dataDir = context.applicationInfo.dataDir?.let { File(it) }
        val appWebView = dataDir?.let { File(it, "app_webview") }
        val cache = context.cacheDir
        val databases = dataDir?.let { File(it, "databases") }
        val files = context.filesDir

        val w = dirSize(appWebView)
        val c = dirSize(cache)
        val d = dirSize(databases)
        val f = dirSize(files)
        return CacheReport(w, c, d, f, w + c + d + f)
    }

    /**
     * Clear WebView HTTP cache + disk cache folders under app_webview.
     * Safe to call from background thread; [webView] clearCache must run on UI if provided.
     */
    fun clear(context: Context, webView: WebView? = null): CacheReport {
        val before = measure(context)
        Log.i(TAG, "Cache BEFORE clear: $before")

        // 1) WebView.clearCache (must be on main if webView is attached)
        try {
            webView?.clearCache(true)
        } catch (e: Exception) {
            Log.w(TAG, "webView.clearCache: ${e.message}")
        }

        // 2) Delete known heavy subdirs under app_webview (keep cookies if possible)
        val dataDir = context.applicationInfo.dataDir?.let { File(it) }
        val appWebView = dataDir?.let { File(it, "app_webview") }
        if (appWebView != null && appWebView.exists()) {
            // Common heavy dirs: Cache, GPUCache, Code Cache, Service Worker, Blob Storage
            val toWipe = listOf(
                "Cache", "GPUCache", "Code Cache", "Service Worker",
                "blob_storage", "Session Storage", "Local Storage",
                "databases", "IndexedDB", "WebStorage"
            )
            for (name in toWipe) {
                val sub = File(appWebView, name)
                if (sub.exists()) {
                    val ok = sub.deleteRecursively()
                    Log.i(TAG, "Deleted ${sub.path}: $ok")
                }
            }
        }

        // 3) App cacheDir
        try {
            context.cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        } catch (e: Exception) {
            Log.w(TAG, "cacheDir wipe: ${e.message}")
        }

        val after = measure(context)
        Log.i(TAG, "Cache AFTER clear: $after (freed ${before.mb(before.totalBytes - after.totalBytes)}MB)")
        return after
    }
}
