package com.hb.headlessbridge.server

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hb.headlessbridge.util.SecretKeyManager
import com.hb.headlessbridge.webview.WebViewEngine
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight HTTP bridge server (NanoHTTPD).
 * Auth: Authorization: Bearer <token>
 * Public: /help, /ping only. Bound to 127.0.0.1.
 */
class BridgeHttpServer(
    private val context: Context,
    private val port: Int,
    private val engineProvider: () -> WebViewEngine?,
    private val headlessController: HeadlessController
) : NanoHTTPD("127.0.0.1", port) {  // ONLY localhost – no LAN exposure

    interface HeadlessController {
        fun setHeadless(on: Boolean)
        fun isHeadless(): Boolean
        fun setAntiPopup(on: Boolean)
        fun setDestroyAds(on: Boolean)
        fun getStatus(): Map<String, Any?>
        fun requestStop()
    }

    companion object {
        private const val TAG = "BridgeHttpServer"
        private val gson = Gson()
        private val startTime = AtomicLong(SystemClock.elapsedRealtime())
        private val logBuffer = ConcurrentLinkedDeque<String>()
        private const val MAX_LOG_LINES = 200

        fun log(msg: String) {
            val line = "${System.currentTimeMillis()} $msg"
            logBuffer.addLast(line)
            while (logBuffer.size > MAX_LOG_LINES) logBuffer.pollFirst()
            Log.d(TAG, msg)
        }
    }

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val rateLimiter = RateLimiter(60) // 60 req / min / IP

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method
        val clientIp = session.remoteIpAddress ?: "unknown"
        val start = SystemClock.elapsedRealtime()

        // Rate limit
        if (!rateLimiter.allow(clientIp)) {
            return jsonError(429, "Rate limit exceeded (60 req/min)")
        }

        // Parse body once
        val bodyMap = mutableMapOf<String, String>()
        try {
            session.parseBody(bodyMap)
        } catch (_: Exception) {}
        val rawBody = bodyMap["postData"] ?: ""

        // Auth check
        val needsAuth = !isPublicEndpoint(uri)
        if (needsAuth && !isAuthorized(session, rawBody)) {
            log("AUTH FAIL $method $uri from $clientIp")
            return jsonError(401, "Unauthorized – provide Authorization: Bearer <token>")
        }

        log("$method $uri from $clientIp")

        val response = try {
            when {
                uri == "/help" || uri.endsWith("/help") -> serveHelp()
                uri == "/ping" -> servePing()
                uri == "/health" -> serveHealth()
                uri == "/navigate" && (method == Method.POST || method == Method.GET) -> serveNavigate(session, rawBody)
                uri == "/eval" && method == Method.POST -> serveEval(rawBody)
                uri == "/cookies" && method == Method.GET -> serveCookiesGet()
                uri == "/cookies/set" && method == Method.POST -> serveCookiesSet(rawBody)
                uri == "/cookies/clear" && method == Method.POST -> serveCookiesClear()
                uri == "/html" -> serveHtml()
                uri == "/title" -> serveTitle()
                uri == "/ua" && method == Method.GET -> serveUaGet()
                uri == "/ua" && method == Method.POST -> serveUaSet(rawBody)
                uri == "/curl" && method == Method.POST -> serveCurl(rawBody)
                uri == "/click" && method == Method.POST -> serveClick(rawBody)
                uri == "/type" && method == Method.POST -> serveType(rawBody)
                uri == "/scroll" && method == Method.POST -> serveScroll(rawBody)
                uri == "/screenshot" && method == Method.POST -> serveScreenshot()
                uri == "/headless" && method == Method.POST -> serveHeadless(rawBody)
                uri == "/ads/block" && method == Method.POST -> serveAdsBlock(rawBody)
                uri == "/popup/block" && method == Method.POST -> servePopupBlock(rawBody)
                uri == "/debug/wifi" && method == Method.POST -> serveDebugWifi(rawBody)
                uri == "/logs" -> serveLogs()
                uri == "/stop" && method == Method.POST -> serveStop()
                // File transfer
                uri == "/file/upload" && method == Method.POST -> serveFileUpload(rawBody)
                uri == "/file/list" && method == Method.GET -> serveFileList(session)
                uri == "/file/download" && method == Method.GET -> serveFileDownload(session)
                uri == "/file/delete" && method == Method.POST -> serveFileDelete(rawBody)
                uri == "/file/write" && method == Method.POST -> serveFileWrite(rawBody)
                // Input helpers
                uri == "/paste" && method == Method.POST -> servePaste(rawBody)
                uri == "/input" && method == Method.POST -> serveInput(rawBody)
                else -> jsonError(404, "Unknown endpoint: $uri")
            }
        } catch (e: Exception) {
            log("ERROR $uri: ${e.message}")
            jsonError(500, e.message ?: "Internal error")
        }

        val elapsed = SystemClock.elapsedRealtime() - start
        log("$method $uri → ${response.status.requestStatus} (${elapsed}ms)")
        return response
    }

    private fun isPublicEndpoint(uri: String): Boolean {
        return uri == "/help" || uri == "/ping" || uri.endsWith("/help")
    }

    private fun isAuthorized(session: IHTTPSession, rawBody: String): Boolean {
        // Origin check – block DNS rebinding
        val origin = session.headers["origin"] ?: session.headers["Origin"]
        if (origin != null) {
            val okOrigin = origin == "null" ||
                origin.startsWith("http://127.0.0.1") ||
                origin.startsWith("http://localhost") ||
                origin.startsWith("https://127.0.0.1") ||
                origin.startsWith("https://localhost")
            if (!okOrigin) {
                log("Blocked bad Origin: $origin")
                return false
            }
        }

        val expected = SecretKeyManager.getKey(context)
        // NanoHTTPD lowercases header names → always use lowercase key
        val auth = session.headers["authorization"]
        var provided: String? = null
        if (auth != null) {
            // "Bearer <token>" or "bearer <token>"
            provided = auth.trim()
                .removePrefix("Bearer")
                .removePrefix("bearer")
                .trim()
        }
        if (provided.isNullOrBlank()) {
            provided = session.headers["x-auth-key"]?.trim()
        }
        if (provided.isNullOrBlank()) {
            provided = session.parameters["key"]?.firstOrNull()?.trim()
        }

        if (provided.isNullOrBlank()) {
            if (com.hb.headlessbridge.BuildConfig.DEBUG) {
                Log.w("BridgeAuth", "No token in request. expected=${SecretKeyManager.maskedKey(expected)}")
            }
            return false
        }

        val ok = constantTimeEquals(provided, expected)
        if (com.hb.headlessbridge.BuildConfig.DEBUG) {
            Log.d(
                "BridgeAuth",
                "auth ok=$ok recv_len=${provided.length} exp_len=${expected.length} " +
                    "recv=${SecretKeyManager.maskedKey(provided)} exp=${SecretKeyManager.maskedKey(expected)}"
            )
        }
        return ok
    }

    /** Constant-time compare to avoid timing attacks. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        val ba = a.toByteArray(Charsets.UTF_8)
        val bb = b.toByteArray(Charsets.UTF_8)
        return java.security.MessageDigest.isEqual(ba, bb)
    }

    /** True if Activity is visible OR user enabled ALLOW_BACKGROUND_DEBUG. */
    private fun allowSensitiveOps(): Boolean {
        if (SecretKeyManager.isAllowBackgroundDebug(context)) return true
        return com.hb.headlessbridge.MainActivity.isInForeground
    }

    // ───────────── Endpoint implementations ─────────────

    private fun serveHelp(): Response {
        val html = """
            <!DOCTYPE html><html><head><meta charset="utf-8"><title>HeadlessBridge API</title>
            <style>body{font-family:monospace;background:#1e1e1e;color:#ddd;padding:20px}
            h1{color:#1A73E8} code{background:#333;padding:2px 6px;border-radius:3px}
            .ep{margin:8px 0} .m{color:#4CAF50}</style></head><body>
            <h1>HeadlessBridge API</h1>
            <p>Auth: header <code>Authorization: Bearer &lt;token&gt;</code> (token only in app UI)</p>
            <p>Port: $port · Version: 1.0.0</p>
            <div class="ep"><span class="m">GET</span>  /help – this page</div>
            <div class="ep"><span class="m">GET</span>  /ping – health ping</div>
                        <div class="ep"><span class="m">GET</span>  /health – battery, RAM, headless status</div>
            <div class="ep"><span class="m">POST</span> /navigate – {url, referer?, headers?}</div>
            <div class="ep"><span class="m">GET</span>  /navigate?url=...</div>
            <div class="ep"><span class="m">POST</span> /eval – {js}</div>
            <div class="ep"><span class="m">GET</span>  /cookies</div>
            <div class="ep"><span class="m">POST</span> /cookies/set – {url, name, value, domain?, path?}</div>
            <div class="ep"><span class="m">POST</span> /cookies/clear</div>
            <div class="ep"><span class="m">GET</span>  /html</div>
            <div class="ep"><span class="m">GET</span>  /title</div>
            <div class="ep"><span class="m">GET</span>  /ua</div>
            <div class="ep"><span class="m">POST</span> /ua – {ua}</div>
            <div class="ep"><span class="m">POST</span> /curl – {method, url, headers?, body?, followRedirects?}</div>
            <div class="ep"><span class="m">POST</span> /click – {selector} or {x,y}</div>
            <div class="ep"><span class="m">POST</span> /type – {selector, text}</div>
            <div class="ep"><span class="m">POST</span> /scroll – {x, y}</div>
            <div class="ep"><span class="m">POST</span> /screenshot – base64 PNG</div>
            <div class="ep"><span class="m">POST</span> /headless – {on:true|false}</div>
            <div class="ep"><span class="m">POST</span> /ads/block – {on:bool}</div>
            <div class="ep"><span class="m">POST</span> /popup/block – {on:bool}</div>
            <div class="ep"><span class="m">POST</span> /debug/wifi – {enable, port}</div>
            <div class="ep"><span class="m">GET</span>  /logs</div>
            <div class="ep"><span class="m">POST</span> /stop</div>
            <h2>File transfer</h2>
            <div class="ep"><span class="m">POST</span> /file/upload – {name, data_base64, path?, public?}</div>
            <div class="ep"><span class="m">GET</span>  /file/list?path=</div>
            <div class="ep"><span class="m">GET</span>  /file/download?path= – returns data_base64</div>
            <div class="ep"><span class="m">POST</span> /file/write – {path, content|data_base64}</div>
            <div class="ep"><span class="m">POST</span> /file/delete – {path}</div>
            <h2>Input</h2>
            <div class="ep"><span class="m">POST</span> /type – {selector, text}</div>
            <div class="ep"><span class="m">POST</span> /input – {selector, text, clear?}</div>
            <div class="ep"><span class="m">POST</span> /paste – {text, selector?}</div>
            </body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun servePing(): Response {
        val uptime = (SystemClock.elapsedRealtime() - startTime.get()) / 1000
        val data = mapOf(
            "ok" to true,
            "version" to "1.0.0",
            "uptime" to uptime,
            "port" to port
        )
        return jsonOk(data)
    }

    private fun serveKey(session: IHTTPSession): Response {
        // Only allow from localhost
        val ip = session.remoteIpAddress
        if (ip != "127.0.0.1" && ip != "::1" && ip != "localhost") {
            return jsonError(403, " /key only available from localhost")
        }
        val key = SecretKeyManager.getKey(context)
        return jsonOk(mapOf("key" to key, "port" to port))
    }

    private fun serveHealth(): Response {
        val status = headlessController.getStatus()
        return jsonOk(status)
    }

    private fun serveNavigate(session: IHTTPSession, rawBody: String): Response {
        if (!allowSensitiveOps()) return jsonError(403, "App must be foreground or enable ALLOW_BACKGROUND_DEBUG")
        val engine = engineProvider() ?: return jsonError(503, "WebView not ready")
        val url = when (session.method) {
            Method.GET -> session.parameters["url"]?.firstOrNull()
            else -> {
                val json = parseJson(rawBody)
                json?.get("url")?.asString
            }
        } ?: return jsonError(400, "Missing url")

        val ok = engine.navigate(url)
        return if (ok) {
            jsonOk(mapOf(
                "url" to engine.getCurrentUrl(),
                "title" to engine.getTitle()
            ))
        } else {
            jsonError(504, "Navigation timeout or failed")
        }
    }

    private fun serveEval(rawBody: String): Response {
        if (!allowSensitiveOps()) return jsonError(403, "App must be foreground or enable ALLOW_BACKGROUND_DEBUG")
        val engine = engineProvider() ?: return jsonError(503, "WebView not ready")
        val json = parseJson(rawBody) ?: return jsonError(400, "Invalid JSON")
        val js = json.get("js")?.asString ?: return jsonError(400, "Missing js")
        val result = engine.evaluateJs(js)
        return jsonOk(mapOf("result" to result))
    }

    private fun serveCookiesGet(): Response {
        val engine = engineProvider() ?: return jsonError(503, "WebView not ready")
        val cookies = engine.getAllCookies()
        return jsonOk(mapOf("cookies" to cookies))
    }

    private fun serveCookiesSet(rawBody: String): Response {
        val engine = engineProvider() ?: return jsonError(503, "WebView not ready")
        val json = parseJson(rawBody) ?: return jsonError(400, "Invalid JSON")
        val url = json.get("url")?.asString ?: return jsonError(400, "Missing url")
        val name = json.get("name")?.asString ?: return jsonError(400, "Missing name")
        val value = json.get("value")?.asString ?: ""
        val domain = json.get("domain")?.asString
        val path = json.get("path")?.asString ?: "/"
        val cookie = buildString {
            append("$name=$value")
            if (domain != null) append("; Domain=$domain")
            append("; Path=$path")
        }
        engine.setCookie(url, cookie)
        return jsonOk(mapOf("set" to true))
    }

    private fun serveCookiesClear(): Response {
        val engine = engineProvider() ?: return jsonError(503, "WebView not ready")
        engine.clearAllCookies()
        return jsonOk(mapOf("cleared" to true))
    }

    private fun serveHtml(): Response {
        val engine = engineProvider() ?: return jsonError(503, "WebView not ready")
        val html = engine.getHtml()
        return jsonOk(mapOf("html" to html))
    }

    private fun serveTitle(): Response {
        val engine = engineProvider() ?: return jsonError(503, "WebView not ready")
        return jsonOk(mapOf("title" to engine.getTitle()))
    }

    private fun serveUaGet(): Response {
        val engine = engineProvider() ?: return jsonError(503, "WebView not ready")
        return jsonOk(mapOf("ua" to engine.getUserAgent()))
    }

    private fun serveUaSet(rawBody: String): Response {
        val engine = engineProvider() ?: return jsonError(503, "WebView not ready")
        val json = parseJson(rawBody) ?: return jsonError(400, "Invalid JSON")
        val ua = json.get("ua")?.asString ?: return jsonError(400, "Missing ua")
        engine.setUserAgent(ua)
        return jsonOk(mapOf("ua" to ua))
    }

    private fun serveCurl(rawBody: String): Response {
        val json = parseJson(rawBody) ?: return jsonError(400, "Invalid JSON")
        val method = json.get("method")?.asString ?: "GET"
        val url = json.get("url")?.asString ?: return jsonError(400, "Missing url")
        val follow = json.get("followRedirects")?.asBoolean ?: true

        val client = if (follow) okHttp else okHttp.newBuilder().followRedirects(false).build()
        val builder = Request.Builder().url(url)

        // Headers
        if (json.has("headers") && json.get("headers").isJsonObject) {
            val headers = json.getAsJsonObject("headers")
            for ((k, v) in headers.entrySet()) {
                builder.addHeader(k, v.asString)
            }
        }

        val bodyStr = json.get("body")?.asString
        when (method.uppercase()) {
            "POST", "PUT", "PATCH" -> {
                val mediaType = "application/json; charset=utf-8".toMediaType()
                builder.method(method.uppercase(), (bodyStr ?: "").toRequestBody(mediaType))
            }
            "DELETE" -> builder.delete()
            else -> builder.get()
        }

        return try {
            val resp = client.newCall(builder.build()).execute()
            val respBody = resp.body?.string() ?: ""
            val respHeaders = mutableMapOf<String, String>()
            for (name in resp.headers.names()) {
                respHeaders[name] = resp.header(name) ?: ""
            }
            jsonOk(mapOf(
                "status" to resp.code,
                "headers" to respHeaders,
                "body" to respBody
            ))
        } catch (e: IOException) {
            jsonError(502, "curl failed: ${e.message}")
        }
    }

    private fun serveClick(rawBody: String): Response {
        val engine = engineProvider() ?: return jsonError(503, "WebView not ready")
        val json = parseJson(rawBody) ?: return jsonError(400, "Invalid JSON")
        val selector = json.get("selector")?.asString
        if (selector != null) {
            val ok = engine.clickSelector(selector)
            return jsonOk(mapOf("clicked" to ok, "selector" to selector))
        }
        val x = json.get("x")?.asFloat
        val y = json.get("y")?.asFloat
        if (x != null && y != null) {
            engine.clickCoordinates(x, y)
            return jsonOk(mapOf("clicked" to true, "x" to x, "y" to y))
        }
        return jsonError(400, "Provide selector or x,y")
    }

    private fun serveType(rawBody: String): Response {
        val engine = engineProvider() ?: return jsonError(503, "WebView not ready")
        val json = parseJson(rawBody) ?: return jsonError(400, "Invalid JSON")
        val selector = json.get("selector")?.asString ?: return jsonError(400, "Missing selector")
        val text = json.get("text")?.asString ?: ""
        val ok = engine.typeText(selector, text)
        return jsonOk(mapOf("typed" to ok))
    }

    private fun serveScroll(rawBody: String): Response {
        val engine = engineProvider() ?: return jsonError(503, "WebView not ready")
        val json = parseJson(rawBody) ?: return jsonError(400, "Invalid JSON")
        val x = json.get("x")?.asInt ?: 0
        val y = json.get("y")?.asInt ?: 0
        engine.scrollTo(x, y)
        return jsonOk(mapOf("scrolled" to true, "x" to x, "y" to y))
    }

    private fun serveScreenshot(): Response {
        val engine = engineProvider() ?: return jsonError(503, "WebView not ready")
        val b64 = engine.takeScreenshotBase64()
            ?: return jsonError(500, "Screenshot failed")
        return jsonOk(mapOf("png_base64" to b64))
    }

    private fun serveHeadless(rawBody: String): Response {
        val json = parseJson(rawBody) ?: return jsonError(400, "Invalid JSON")
        val on = json.get("on")?.asBoolean ?: return jsonError(400, "Missing on")
        headlessController.setHeadless(on)
        return jsonOk(mapOf("headless" to on))
    }

    private fun serveAdsBlock(rawBody: String): Response {
        val json = parseJson(rawBody) ?: return jsonError(400, "Invalid JSON")
        val on = json.get("on")?.asBoolean ?: return jsonError(400, "Missing on")
        headlessController.setDestroyAds(on)
        return jsonOk(mapOf("ads_block" to on))
    }

    private fun servePopupBlock(rawBody: String): Response {
        val json = parseJson(rawBody) ?: return jsonError(400, "Invalid JSON")
        val on = json.get("on")?.asBoolean ?: return jsonError(400, "Missing on")
        headlessController.setAntiPopup(on)
        return jsonOk(mapOf("popup_block" to on))
    }

    private fun serveDebugWifi(rawBody: String): Response {
        // Placeholder – actual pairing handled in UI / WirelessDebugHelper
        val json = parseJson(rawBody)
        return jsonOk(mapOf(
            "message" to "Use Settings → Wireless Debug dialog to pair. This endpoint acknowledges request.",
            "received" to (json?.toString() ?: "{}")
        ))
    }

    private fun serveLogs(): Response {
        val lines = logBuffer.toList()
        return jsonOk(mapOf("logs" to lines))
    }

    private fun serveStop(): Response {
        headlessController.requestStop()
        return jsonOk(mapOf("stopping" to true))
    }

    // ───────────── File transfer ─────────────

    private fun baseFilesDir(): java.io.File {
        // Prefer external app-specific dir (no permission needed on modern Android)
        val ext = context.getExternalFilesDir(null)
        return ext ?: context.filesDir
    }

    private fun resolveSafePath(relative: String?): java.io.File {
        val base = baseFilesDir()
        if (relative.isNullOrBlank() || relative == "/" || relative == ".") {
            return base
        }
        // Prevent path traversal
        val cleaned = relative.replace("..", "").trimStart('/')
        val target = java.io.File(base, cleaned)
        val canonical = target.canonicalFile
        if (!canonical.path.startsWith(base.canonicalPath)) {
            throw SecurityException("Path traversal blocked")
        }
        return canonical
    }

    private fun serveFileUpload(rawBody: String): Response {
        val json = parseJson(rawBody) ?: return jsonError(400, "Invalid JSON")
        val name = json.get("name")?.asString ?: return jsonError(400, "Missing name")
        val b64 = json.get("data_base64")?.asString ?: return jsonError(400, "Missing data_base64")
        val subdir = json.get("path")?.asString // optional subdirectory
        val toPublic = json.get("public")?.asBoolean ?: false

        return try {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            val dir = if (toPublic) {
                // Try public Downloads (may fail without permission)
                java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), "HeadlessBridge").also { it.mkdirs() }
            } else {
                resolveSafePath(subdir).also { if (!it.isDirectory) it.mkdirs() }
            }
            val file = java.io.File(dir, name)
            file.writeBytes(bytes)
            jsonOk(mapOf(
                "saved" to true,
                "path" to file.absolutePath,
                "size" to bytes.size
            ))
        } catch (e: Exception) {
            jsonError(500, "Upload failed: ${e.message}")
        }
    }

    private fun serveFileList(session: IHTTPSession): Response {
        val pathParam = session.parameters["path"]?.firstOrNull()
        return try {
            val dir = resolveSafePath(pathParam)
            if (!dir.exists()) return jsonError(404, "Path not found")
            if (!dir.isDirectory) return jsonError(400, "Not a directory")
            val files = dir.listFiles()?.map { f ->
                mapOf(
                    "name" to f.name,
                    "path" to f.absolutePath,
                    "size" to f.length(),
                    "is_dir" to f.isDirectory,
                    "modified" to f.lastModified()
                )
            } ?: emptyList()
            jsonOk(mapOf("path" to dir.absolutePath, "files" to files))
        } catch (e: Exception) {
            jsonError(500, e.message ?: "List failed")
        }
    }

    private fun serveFileDownload(session: IHTTPSession): Response {
        val pathParam = session.parameters["path"]?.firstOrNull()
            ?: return jsonError(400, "Missing path query")
        return try {
            val file = resolveSafePath(pathParam)
            if (!file.exists() || !file.isFile) return jsonError(404, "File not found")
            if (file.length() > 25 * 1024 * 1024) return jsonError(413, "File too large (>25MB)")
            val b64 = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
            jsonOk(mapOf(
                "name" to file.name,
                "path" to file.absolutePath,
                "size" to file.length(),
                "data_base64" to b64
            ))
        } catch (e: Exception) {
            jsonError(500, e.message ?: "Download failed")
        }
    }

    private fun serveFileDelete(rawBody: String): Response {
        val json = parseJson(rawBody) ?: return jsonError(400, "Invalid JSON")
        val path = json.get("path")?.asString ?: return jsonError(400, "Missing path")
        return try {
            val file = resolveSafePath(path)
            if (!file.exists()) return jsonError(404, "Not found")
            val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
            jsonOk(mapOf("deleted" to ok, "path" to file.absolutePath))
        } catch (e: Exception) {
            jsonError(500, e.message ?: "Delete failed")
        }
    }

    private fun serveFileWrite(rawBody: String): Response {
        // Write plain text (or base64) to a file – convenient for scripts
        val json = parseJson(rawBody) ?: return jsonError(400, "Invalid JSON")
        val path = json.get("path")?.asString ?: return jsonError(400, "Missing path")
        val content = json.get("content")?.asString
        val b64 = json.get("data_base64")?.asString
        if (content == null && b64 == null) return jsonError(400, "Provide content or data_base64")
        return try {
            val file = resolveSafePath(path)
            file.parentFile?.mkdirs()
            if (b64 != null) {
                file.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            } else {
                file.writeText(content!!)
            }
            jsonOk(mapOf("written" to true, "path" to file.absolutePath, "size" to file.length()))
        } catch (e: Exception) {
            jsonError(500, e.message ?: "Write failed")
        }
    }

    // ───────────── Input helpers ─────────────

    private fun servePaste(rawBody: String): Response {
        val engine = engineProvider() ?: return jsonError(503, "WebView not ready")
        val json = parseJson(rawBody) ?: return jsonError(400, "Invalid JSON")
        val text = json.get("text")?.asString ?: return jsonError(400, "Missing text")
        val selector = json.get("selector")?.asString

        // Set system clipboard
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("hb_paste", text))

        if (selector != null) {
            // Focus element then paste via JS execCommand or value set
            val escaped = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
            val js = """
                (function(){
                    var el = document.querySelector('$selector');
                    if(!el) return false;
                    el.focus();
                    if(el.isContentEditable){
                        document.execCommand('insertText', false, '$escaped');
                    } else {
                        el.value = (el.value || '') + '$escaped';
                        el.dispatchEvent(new Event('input', {bubbles:true}));
                    }
                    return true;
                })()
            """.trimIndent()
            val ok = engine.evaluateJs(js) == "true"
            return jsonOk(mapOf("pasted" to ok, "via" to "selector"))
        }
        // Fallback: just put on clipboard – user can Ctrl+V
        return jsonOk(mapOf("pasted" to true, "via" to "clipboard", "note" to "Text on clipboard, focus field and paste"))
    }

    private fun serveInput(rawBody: String): Response {
        // Generic text input: prefer selector, else coordinates + type
        val engine = engineProvider() ?: return jsonError(503, "WebView not ready")
        val json = parseJson(rawBody) ?: return jsonError(400, "Invalid JSON")
        val text = json.get("text")?.asString ?: return jsonError(400, "Missing text")
        val selector = json.get("selector")?.asString
        val clear = json.get("clear")?.asBoolean ?: true

        if (selector != null) {
            val escaped = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
            val clearJs = if (clear) "el.value = '';" else ""
            val js = """
                (function(){
                    var el = document.querySelector('$selector');
                    if(!el) return false;
                    el.focus();
                    $clearJs
                    el.value = '$escaped';
                    el.dispatchEvent(new Event('input', {bubbles:true}));
                    el.dispatchEvent(new Event('change', {bubbles:true}));
                    return true;
                })()
            """.trimIndent()
            val ok = engine.evaluateJs(js) == "true"
            return jsonOk(mapOf("typed" to ok, "selector" to selector))
        }
        return jsonError(400, "Provide selector (recommended) or use /type")
    }


    // ───────────── Helpers ─────────────

    private fun parseJson(raw: String): JsonObject? {
        if (raw.isBlank()) return null
        return try {
            JsonParser.parseString(raw).asJsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun jsonOk(data: Any?): Response {
        val map = mapOf("ok" to true, "data" to data)
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            gson.toJson(map)
        )
    }

    private fun jsonError(code: Int, message: String): Response {
        val status = when (code) {
            400 -> Response.Status.BAD_REQUEST
            401 -> Response.Status.UNAUTHORIZED
            403 -> Response.Status.FORBIDDEN
            404 -> Response.Status.NOT_FOUND
            503 -> Response.Status.SERVICE_UNAVAILABLE
            else -> Response.Status.INTERNAL_ERROR
        }
        val map = mapOf("ok" to false, "error" to message)
        return newFixedLengthResponse(status, "application/json", gson.toJson(map))
    }

    /** Simple token-bucket style rate limiter per IP */
    private class RateLimiter(private val maxPerMinute: Int) {
        private val hits = mutableMapOf<String, MutableList<Long>>()
        private val lock = Any()

        fun allow(ip: String): Boolean {
            synchronized(lock) {
                val now = System.currentTimeMillis()
                val window = now - 60_000
                val list = hits.getOrPut(ip) { mutableListOf() }
                list.removeAll { it < window }
                if (list.size >= maxPerMinute) return false
                list.add(now)
                return true
            }
        }
    }
}
