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
import android.content.ActivityNotFoundException
import android.content.Intent
import com.hb.headlessbridge.util.AdBlocker
import com.hb.headlessbridge.util.SecretKeyManager
import org.json.JSONObject
import org.json.JSONTokener
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
    @Volatile private var pendingUrl: String = ""

    var antiPopupEnabled: Boolean = true
    var destroyAdsEnabled: Boolean = true
    var customUserAgent: String? = null

    init {
        configureWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        // DevTools only in debug builds (release would expose the WebView to any adb client)
        WebView.setWebContentsDebuggingEnabled(com.hb.headlessbridge.BuildConfig.DEBUG)

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

        // UA: strip the WebView markers so it looks like real Chrome for Android.
        //   "(Linux; Android 13; Pixel 7; wv)"  ->  "(Linux; Android 13; Pixel 7)"
        //   "Version/4.0 Chrome/..."           ->  "Chrome/..."
        // (Removing only the "wv" token leaves a malformed "; )" which is itself a bot signal.)
        val defaultUa = settings.userAgentString ?: ""
        val cleanedUa = defaultUa
            .replace(Regex(";\\s*wv\\)", RegexOption.IGNORE_CASE), ")")
            .replace(Regex("\\s+Build/[^;)]*"), "")
            .replace(Regex("\\bVersion/\\d+(\\.\\d+)*\\s*"), "")
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

            // API 26+. Without this, a renderer crash (low memory) KILLS THE WHOLE APP.
            // Returning true keeps the process alive; the service then builds a fresh engine.
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                rendererGone = true
                val crashed = detail?.didCrash() == true
                Log.e(TAG, "Renderer gone (crashed=$crashed) – engine will be recreated")
                com.hb.headlessbridge.server.BridgeHttpServer.log(
                    "WebView renderer gone (" + (if (crashed) "crash" else "killed by system / low memory") + ")"
                )
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                lastUrl.set(url ?: "")
                lastTitle.set(view?.title ?: "")
                // An old about:blank / redirect finishing must not release a pending navigate().
                if (url != "about:blank" || pendingUrl == "about:blank") pageFinishedLatch.get()?.countDown()
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
        if (!isUsable()) return false
        val latch = CountDownLatch(1)
        pendingUrl = url
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
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) decodeJsResult(result.get()) else null
        } catch (e: InterruptedException) {
            null
        }
    }

    /**
     * evaluateJavascript hands back a JSON value. Strings come quoted and escaped
     * (WebView even writes the "less-than" sign as a unicode escape), so decode them with a
     * real JSON parser. The old hand-rolled unescape corrupted /html output and anything
     * containing unicode escapes.
     */
    private fun decodeJsResult(raw: String?): String? {
        if (raw == null || raw == "null") return null
        if (raw.length >= 2 && raw.startsWith("\"")) {
            return try {
                JSONTokener(raw).nextValue() as? String ?: raw
            } catch (e: Exception) {
                raw
            }
        }
        return raw
    }

    /** Safe JS string literal (quotes, backslashes, newlines, </script>, U+2028 …). */
    fun jsLiteral(s: String): String = JSONObject.quote(s)

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
                var el = document.querySelector(${jsLiteral(selector)});
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

    /**
     * Set (or append to) the value of an input / textarea / contenteditable.
     * Uses the native value setter so React/Vue controlled inputs notice the change.
     */
    fun typeText(selector: String, text: String, clear: Boolean = true, append: Boolean = false): Boolean {
        val replaceExisting = clear && !append
        val js = """
            (function(){
                var el = document.querySelector(${jsLiteral(selector)});
                if(!el) return false;
                var text = ${jsLiteral(text)};
                var replaceExisting = $replaceExisting;
                var append = $append;
                el.focus();
                if(el.isContentEditable){
                    if(replaceExisting){ document.execCommand('selectAll', false, null); }
                    document.execCommand('insertText', false, text);
                    return true;
                }
                var proto = (el.tagName === 'TEXTAREA') ? window.HTMLTextAreaElement.prototype
                                                         : window.HTMLInputElement.prototype;
                var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                var next = (append ? (el.value || '') : '') + text;
                if(desc && desc.set){ desc.set.call(el, next); } else { el.value = next; }
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
                // A hidden / never-laid-out WebView has size 0 → screenshot would be 1x1.
                if (webView.width == 0 || webView.height == 0) {
                    webView.measure(
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                    )
                    webView.layout(0, 0, 1080, 1920)
                }
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
     * Chặn scheme lạ → mở app tương ứng, tránh ERR_UNKNOWN_URL_SCHEME / màn trắng.
     * @return true = WebView không load URL này.
     */
    private fun handleSpecialScheme(url: String): Boolean {
        val lower = url.lowercase()
        // http(s) / about / data / file / blob → để WebView xử lý
        if (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("about:") || lower.startsWith("data:") ||
            lower.startsWith("file:") || lower.startsWith("blob:") ||
            lower.startsWith("javascript:")
        ) {
            return false
        }

        Log.i(TAG, "Special scheme: ${url.take(160)}")
        return try {
            if (lower.startsWith("intent:")) {
                handleIntentScheme(url)
            } else {
                // tg://, whatsapp://, market://, upi://, tel:, mailto:, zalo://...
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    // Không dùng createChooser → ưu tiên app mặc định / app gốc
                }
                val resolved = intent.resolveActivity(context.packageManager)
                if (resolved != null) {
                    context.startActivity(intent)
                } else {
                    Log.w(TAG, "No app for scheme: $url")
                    Toast.makeText(context, "Không tìm thấy ứng dụng để mở liên kết", Toast.LENGTH_SHORT).show()
                }
                true
            }
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "ActivityNotFoundException: ${e.message}")
            Toast.makeText(context, "Không tìm thấy ứng dụng để mở liên kết", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Log.e(TAG, "handleSpecialScheme error: ${e.message}", e)
            true // vẫn consume để WebView không hiện ERR_UNKNOWN_URL_SCHEME
        }
    }

    /** intent://...#Intent;scheme=https;package=...;end */
    private fun handleIntentScheme(url: String): Boolean {
        return try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // package= trong intent → ép mở đúng app, tránh dialog chọn
            // (parseUri đã set intent.`package` nếu có)
            val pm = context.packageManager
            if (intent.resolveActivity(pm) != null) {
                context.startActivity(intent)
                Log.i(TAG, "Opened intent package=${intent.`package`}")
                return true
            }
            // Fallback: browser_fallback_url
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (!fallback.isNullOrBlank()) {
                Log.i(TAG, "browser_fallback_url → $fallback")
                mainHandler.post { webView.loadUrl(fallback) }
                return true
            }
            // Fallback: scheme=https trong #Intent → load trong WebView
            val webUrl = extractHttpsFromIntent(url)
            if (webUrl != null) {
                Log.i(TAG, "https from intent → $webUrl")
                mainHandler.post { webView.loadUrl(webUrl) }
                return true
            }
            Toast.makeText(context, "Không có app phù hợp để mở liên kết", Toast.LENGTH_LONG).show()
            true
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "ActivityNotFoundException intent: ${e.message}")
            val webUrl = extractHttpsFromIntent(url)
            if (webUrl != null) {
                mainHandler.post { webView.loadUrl(webUrl) }
            } else {
                Toast.makeText(context, "Không tìm thấy ứng dụng đích", Toast.LENGTH_SHORT).show()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Intent.parseUri failed: ${e.message}", e)
            true
        }
    }

    private fun extractHttpsFromIntent(intentUrl: String): String? {
        val schemeMatch = Regex("scheme=([^;]+)").find(intentUrl) ?: return null
        val hostMatch = Regex("intent://([^#]+)").find(intentUrl) ?: return null
        val scheme = schemeMatch.groupValues[1]
        if (scheme == "https" || scheme == "http") {
            return "$scheme://${hostMatch.groupValues[1]}"
        }
        return null
    }

    // ───────────────────── Lifecycle ─────────────────────

    /** True after destroy() was called. */
    fun isDestroyed(): Boolean = isDestroyed.get()

    /** Set when Chromium's renderer process died (OOM / low-memory killer / crash). */
    @Volatile var rendererGone: Boolean = false
        private set

    /**
     * True if engine can still drive the WebView.
     *
     * MUST NOT touch the WebView: this is called from NanoHTTPD worker threads, and any WebView
     * method off the UI thread throws. The old version did `webView.settings` here, the
     * exception was swallowed as "unusable", and ensureEngine() then destroyed and rebuilt the
     * WebView on EVERY HTTP request (webview_recreate_count: 23, page reset to about:blank,
     * webview_ready=false). Liveness is tracked with flags instead.
     */
    fun isUsable(): Boolean = !isDestroyed.get() && !rendererGone

    fun destroy() {
        if (isDestroyed.compareAndSet(false, true)) {
            mainHandler.post {
                try {
                    // destroy() while still attached to a window is an error + leaks the Activity
                    (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.clearHistory()
                    webView.removeAllViews()
                    webView.destroy()
                } catch (_: Exception) {}
            }
        }
    }

    /** Drop caches under memory pressure (called from Service.onTrimMemory). */
    fun trimMemory() {
        mainHandler.post {
            try { webView.clearCache(false) } catch (_: Exception) {}
        }
    }

    fun onResume() {
        mainHandler.post { webView.onResume() }
    }

    fun onPause() {
        mainHandler.post { webView.onPause() }
    }
}
