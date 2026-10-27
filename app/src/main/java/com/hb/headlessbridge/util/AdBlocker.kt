package com.hb.headlessbridge.util

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Host-based ad / tracker blocker used inside WebViewClient.shouldInterceptRequest.
 *
 * Matching is done on the request HOST (exact or sub-domain), not with `contains` / regex over
 * the whole URL. The old version blocked any URL containing "adjust.com", "/ad/", "/banner/",
 * "/pixel/" … which broke ordinary pages, and it also blocked main-frame navigations.
 */
object AdBlocker {

    /** Registrable domains: the domain itself and every sub-domain is blocked. */
    private val blockedDomains = setOf(
        "doubleclick.net", "googleadservices.com", "googlesyndication.com",
        "adservice.google.com", "pagead2.googlesyndication.com",
        "adnxs.com", "adsrvr.org", "advertising.com", "adform.net",
        "adsymptotic.com", "adtechus.com", "amazon-adsystem.com",
        "criteo.com", "criteo.net", "outbrain.com", "taboola.com",
        "scorecardresearch.com", "quantserve.com", "moatads.com",
        "pubmatic.com", "rubiconproject.com", "openx.net",
        "smartadserver.com", "yieldmo.com", "casalemedia.com",
        "connect.facebook.net", "pixel.facebook.com",
        "analytics.twitter.com", "ads.twitter.com",
        "hotjar.com", "mouseflow.com", "fullstory.com",
        "nr-data.net", "segment.io",
        "2mdn.net", "adsafeprotected.com", "serving-sys.com",
        "spotxchange.com", "teads.tv", "stickyadstv.com",
        "yieldlab.net", "adition.com", "adscale.de"
    )

    /** Specific tracker URL prefixes on otherwise legitimate hosts. */
    private val blockedUrlPrefixes = listOf(
        "https://www.facebook.com/tr",
        "http://www.facebook.com/tr"
    )

    fun shouldBlock(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        for (prefix in blockedUrlPrefixes) {
            if (lower.startsWith(prefix)) return true
        }
        val host: String = try {
            Uri.parse(lower).host ?: return false
        } catch (_: Exception) {
            return false
        }
        for (domain in blockedDomains) {
            if (host == domain || host.endsWith(".$domain")) return true
        }
        return false
    }

    fun intercept(request: WebResourceRequest?): WebResourceResponse? {
        if (request == null) return null
        // Never break the page the caller explicitly navigated to.
        if (request.isForMainFrame) return null
        val url = request.url?.toString() ?: return null
        return if (shouldBlock(url)) blockedResponse() else null
    }

    /** Fresh response per call – a WebResourceResponse wraps a one-shot InputStream. */
    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Blocked",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0))
        )
}
