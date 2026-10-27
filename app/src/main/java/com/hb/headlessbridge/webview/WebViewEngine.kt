package com.hb.headlessbridge.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.*
import android.widget.Toast
import android.net.Uri
import android.content.pm.PackageManager
import android.content.Intent
import com.hb.headlessbridge.util.AdBlocker
import com.hb.headlessbridge.util.SecretKeyManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Near-Chromium WebView configuration + automation helpers.
 * - Strips "WV" / "wv" from UA so sites treat it as real Chromium.
 * - Full cookie / DOM storage support.
 * - Ad blocking, anti-popup, JS evaluation, screenshot, click/type/scroll.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebViewEngine(
    private val context: Context,
    val webView: WebView
) {

    companion object {
        private const val TAG = "WebViewEngine"
        private const val DEFAULT_TIMEOUT_MS = 15_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pageFinishedLatch = AtomicReference<CountDownLatch?>(null)
    private val lastTitle = AtomicReference("")
    private val lastUrl = AtomicReference("")
    private val isDestroyed = AtomicBoolean(false)

    var antiPopupEnabled: Boolean = true
    var destroyAdsEnabled: Boolean = true
    var customUserAgent: String? = null

    init {
        configureWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(true)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.loadsImagesAutomatically = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mediaPlaybackRequiresUserGesture = false
        settings.javaScriptCanOpenWindowsAutomatically = false // controlled by antiPopup
        settings.setSupportMultipleWindows(true)
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = false
        }

        // Performance / renderer priority
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        }

        // Cookie setup – accept all, including third-party
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // UA: strip "WV" / "wv" to look like real Chromium
        val defaultUa = settings.userAgentString ?: ""
        val cleanedUa = defaultUa
            .replace(Regex("\\bwv\\b", RegexOption.IGNORE_CASE), "")
            .replace(" WV ", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        settings.userAgentString = cleanedUa
        customUserAgent = cleanedUa
        Log.i(TAG, "UA cleaned: ${cleanedUa.take(80)}...")

        webView.webViewClient = createWebViewClient()
        webView.webChromeClient = createWebChromeClient()

        // Enable slow whole document draw for better screenshots
        try {
            val method = WebView::class.java.getMethod("enableSlowWholeDocumentDraw")
            method.invoke(null)
        } catch (_: Exception) { /* ignore on newer APIs */ }
    }

    private fun createWebViewClient(): WebViewClient {
        return object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                lastUrl.set(url ?: "")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                lastUrl.set(url ?: "")
                lastTitle.set(view?.title ?: "")
                pageFinishedLatch.get()?.countDown()
                // Flush cookies periodically
                CookieManager.getInstance().flush()
            }

            // API 24+
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleSpecialScheme(url)
            }

            // Legacy API < 24 (still needed for some OEM WebViews)
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url.isNullOrBlank()) return false
                return handleSpecialScheme(url)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (destroyAdsEnabled) {
                    val blocked = AdBlocker.intercept(request)
                    if (blocked != null) return blocked
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    Log.w(TAG, "Main frame error: ${error?.description}")
                }
            }
        }
    }

    private fun createWebChromeClient(): WebChromeClient {
        return object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                if (antiPopupEnabled) {
                    // Block window.open / popup
                    Log.d(TAG, "Blocked popup window")
                    return false
                }
                return super.onCreateWindow(view, isDialog, isUserGesture, resultMsg)
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.confirm()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.confirm()
                return true
            }

            override fun onJsPrompt(
                view: WebView?, url: String?, message: String?,
                defaultValue: String?, result: JsPromptResult?
            ): Boolean {
                result?.confirm(defaultValue)
                return true
            }
        }
    }

    // ───────────────────── Navigation ─────────────────────

    fun navigate(url: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        if (isDestroyed.get()) return false
        val latch = CountDownLatch(1)
        pageFinishedLatch.set(latch)
        mainHandler.post {
            webView.loadUrl(url)
        }
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        } finally {
            pageFinishedLatch.set(null)
        }
    }

    // ───────────────────── JS Evaluation ─────────────────────

    fun evaluateJs(script: String, timeoutMs: Long = 10_000L): String? {
        if (isDestroyed.get()) return null
        val result = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        mainHandler.post {
            webView.evaluateJavascript(script) { value ->
                result.set(value)
                latch.countDown()
            }
        }
        return try {
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                // evaluateJavascript returns JSON-encoded string, unwrap it
                val raw = result.get()
                if (raw != null && raw.startsWith("\"") && raw.endsWith("\"")) {
                    // Simple unescape
                    raw.substring(1, raw.length - 1)
                        .replace("\\\"", "\"")
                        .replace("\\n", "\n")
                        .replace("\\\\", "\\")
                } else {
                    raw
                }
            } else null
        } catch (e: InterruptedException) {
            null
        }
    }

    // ───────────────────── Cookies ─────────────────────

    fun getAllCookies(): String {
        CookieManager.getInstance().flush()
        val url = lastUrl.get().ifBlank { "https://www.google.com" }
        return CookieManager.getInstance().getCookie(url) ?: ""
    }

    fun getCookiesForUrl(url: String): String {
        CookieManager.getInstance().flush()
        return CookieManager.getInstance().getCookie(url) ?: ""
    }

    fun setCookie(url: String, cookieString: String) {
        CookieManager.getInstance().setCookie(url, cookieString)
        CookieManager.getInstance().flush()
    }

    fun clearAllCookies() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    // ───────────────────── HTML / Title / UA ─────────────────────

    fun getHtml(): String? {
        return evaluateJs("(function(){return document.documentElement.outerHTML;})()")
    }

    fun getTitle(): String = lastTitle.get().ifBlank {
        evaluateJs("document.title") ?: ""
    }

    fun getCurrentUrl(): String = lastUrl.get().ifBlank {
        evaluateJs("window.location.href") ?: ""
    }

    fun getUserAgent(): String {
        return customUserAgent ?: webView.settings.userAgentString ?: ""
    }

    fun setUserAgent(ua: String) {
        mainHandler.post {
            webView.settings.userAgentString = ua
            customUserAgent = ua
        }
    }

    // ───────────────────── Interaction ─────────────────────

    fun clickSelector(selector: String): Boolean {
        val js = """
            (function(){
                var el = document.querySelector('$selector');
                if(el){ el.click(); return true; }
                return false;
            })()
        """.trimIndent()
        val result = evaluateJs(js)
        return result == "true"
    }

    fun clickCoordinates(x: Float, y: Float) {
        mainHandler.post {
            val down = MotionEvent.obtain(
                System.currentTimeMillis(), System.currentTimeMillis(),
                MotionEvent.ACTION_DOWN, x, y, 0
            )
            val up = MotionEvent.obtain(
                System.currentTimeMillis(), System.currentTimeMillis() + 50,
                MotionEvent.ACTION_UP, x, y, 0
            )
            webView.dispatchTouchEvent(down)
            webView.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
        }
    }

    fun typeText(selector: String, text: String): Boolean {
        // Escape for JS string
        val escaped = text
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
        val js = """
            (function(){
                var el = document.querySelector('$selector');
                if(!el) return false;
                el.focus();
                el.value = '$escaped';
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
                return true;
            })()
        """.trimIndent()
        val result = evaluateJs(js)
        return result == "true"
    }

    fun scrollTo(x: Int, y: Int) {
        mainHandler.post {
            webView.scrollTo(x, y)
        }
        // Also try window.scrollTo for better compatibility
        evaluateJs("window.scrollTo($x, $y)")
    }

    // ───────────────────── Screenshot ─────────────────────

    fun takeScreenshotBase64(): String? {
        if (isDestroyed.get()) return null
        val result = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                val bitmap = Bitmap.createBitmap(
                    webView.width.coerceAtLeast(1),
                    webView.height.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bitmap)
                webView.draw(canvas)
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
                val bytes = stream.toByteArray()
                result.set(Base64.encodeToString(bytes, Base64.NO_WRAP))
                bitmap.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Screenshot failed", e)
            } finally {
                latch.countDown()
            }
        }
        return try {
            if (latch.await(5, TimeUnit.SECONDS)) result.get() else null
        } catch (e: InterruptedException) {
            null
        }
    }


    /**
     * Handle non-http(s) schemes that cause net::ERR_UNKNOWN_URL_SCHEME:
     * intent://, market://, upi://, whatsapp://, tg://, zalo://, tel:, mailto:, etc.
     * @return true if we consumed the URL (WebView should NOT load it)
     */
    private fun handleSpecialScheme(url: String): Boolean {
        val lower = url.lowercase()
        val specialPrefixes = listOf(
            "intent:", "market:", "upi:", "whatsapp:", "tg:", "telegram:",
            "zalo:", "viber:", "line:", "fb:", "facebook:", "tel:", "mailto:",
            "sms:", "geo:", "maps:", "spotify:", "youtube:"
        )
        val isSpecial = specialPrefixes.any { lower.startsWith(it) }
        if (!isSpecial) return false  // let WebView handle http/https/file/etc.

        Log.i(TAG, "Special scheme URL: ${url.take(120)}")
        return try {
            if (lower.startsWith("intent:")) {
                handleIntentScheme(url)
            } else {
                // market://, upi://, whatsapp:// ...
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    true
                } else {
                    Log.w(TAG, "No app can handle: $url")
                    Toast.makeText(context, "Không tìm thấy ứng dụng để mở liên kết này", Toast.LENGTH_SHORT).show()
                    true  // still consume to avoid ERR_UNKNOWN_URL_SCHEME
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleSpecialScheme failed: ${e.message}", e)
            Toast.makeText(context, "Không mở được liên kết: ${e.message}", Toast.LENGTH_SHORT).show()
            true
        }
    }

    /** Parse intent:// URI and launch target app with safe fallbacks. */
    private fun handleIntentScheme(url: String): Boolean {
        return try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Prefer explicit package if present
            val pm = context.packageManager
            if (intent.resolveActivity(pm) != null) {
                context.startActivity(intent)
                Log.i(TAG, "Launched intent target: ${intent.`package` ?: intent.component}")
                return true
            }
            // Fallback 1: browser_fallback_url extra (common in Chrome intent links)
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (!fallback.isNullOrBlank()) {
                Log.i(TAG, "Using browser_fallback_url: $fallback")
                mainHandler.post { webView.loadUrl(fallback) }
                return true
            }
            // Fallback 2: try https scheme from Intent data / extras
            val fallbackScheme = intent.scheme
            if (fallbackScheme == null || fallbackScheme == "https" || fallbackScheme == "http") {
                // already tried resolve
            }
            // Fallback 3: open in default browser if we can extract a web URL
            val webUrl = extractHttpsFromIntent(url)
            if (webUrl != null) {
                Log.i(TAG, "Extracted https fallback: $webUrl")
                mainHandler.post { webView.loadUrl(webUrl) }
                return true
            }
            Toast.makeText(
                context,
                "Không có ứng dụng phù hợp. Cài app đích hoặc mở bằng trình duyệt.",
                Toast.LENGTH_LONG
            ).show()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Intent.parseUri failed: ${e.message}", e)
            Toast.makeText(context, "Lỗi mở intent: ${e.message}", Toast.LENGTH_SHORT).show()
            true
        }
    }

    /** Try to pull an https URL out of an intent:// string for fallback. */
    private fun extractHttpsFromIntent(intentUrl: String): String? {
        // Common pattern: intent://host/path#Intent;scheme=https;...
        val schemeMatch = Regex("scheme=([^;]+)").find(intentUrl)
        val hostMatch = Regex("intent://([^#]+)").find(intentUrl)
        if (schemeMatch != null && hostMatch != null) {
            val scheme = schemeMatch.groupValues[1]
            if (scheme == "https" || scheme == "http") {
                return "$scheme://${hostMatch.groupValues[1]}"
            }
        }
        // browser_fallback_url already handled above
        return null
    }

    // ───────────────────── Lifecycle ─────────────────────

    fun destroy() {
        if (isDestroyed.compareAndSet(false, true)) {
            mainHandler.post {
                try {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.clearHistory()
                    webView.removeAllViews()
                    webView.destroy()
                } catch (_: Exception) {}
            }
        }
    }

    fun onResume() {
        mainHandler.post { webView.onResume() }
    }

    fun onPause() {
        mainHandler.post { webView.onPause() }
    }
}
