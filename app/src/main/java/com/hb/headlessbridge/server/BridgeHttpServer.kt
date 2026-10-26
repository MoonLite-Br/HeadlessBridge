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
 * All endpoints except /help, /ping, /key require X-Auth-Key header or ?key= query.
 */
class BridgeHttpServer(
    private val context: Context,
    private val port: Int,
    private val engineProvider: () -> WebViewEngine?,
    private val headlessController: HeadlessController
) : NanoHTTPD(port) {

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
            return jsonError(401, "Unauthorized – provide X-Auth-Key header or ?key=")
        }

        log("$method $uri from $clientIp")

        val response = try {
            when {
                uri == "/help" || uri.endsWith("/help") -> serveHelp()
                uri == "/ping" -> servePing()
                uri == "/key" -> serveKey(session)
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
        return uri == "/help" || uri == "/ping" || uri == "/key" || uri.endsWith("/help")
    }

    private fun isAuthorized(session: IHTTPSession, rawBody: String): Boolean {
        val expected = SecretKeyManager.getKey(context)
        // Header
        val headerKey = session.headers["x-auth-key"]
            ?: session.headers["X-Auth-Key"]
        if (headerKey != null && headerKey == expected) return true
        // Query param
        val params = session.parameters
        val queryKey = params["key"]?.firstOrNull()
        if (queryKey != null && queryKey == expected) return true
        return false
    }

    // ───────────── Endpoint implementations ─────────────

    private fun serveHelp(): Response {
        val html = """
            <!DOCTYPE html><html><head><meta charset="utf-8"><title>HeadlessBridge API</title>
            <style>body{font-family:monospace;background:#1e1e1e;color:#ddd;padding:20px}
            h1{color:#1A73E8} code{background:#333;padding:2px 6px;border-radius:3px}
            .ep{margin:8px 0} .m{color:#4CAF50}</style></head><body>
            <h1>HeadlessBridge API</h1>
            <p>Auth: header <code>X-Auth-Key: &lt;key&gt;</code> or query <code>?key=</code></p>
            <p>Port: $port · Version: 1.0.0</p>
            <div class="ep"><span class="m">GET</span>  /help – this page</div>
            <div class="ep"><span class="m">GET</span>  /ping – health ping</div>
            <div class="ep"><span class="m">GET</span>  /key – secret key (localhost only)</div>
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
            429 -> Response.Status.TOO_MANY_REQUESTS
            502 -> Response.Status.BAD_GATEWAY
            503 -> Response.Status.SERVICE_UNAVAILABLE
            504 -> Response.Status.GATEWAY_TIMEOUT
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
