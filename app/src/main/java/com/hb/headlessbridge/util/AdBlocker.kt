package com.hb.headlessbridge.util

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.regex.Pattern

/**
 * Simple but effective ad / tracker blocker using host lists + regex.
 * Used inside WebViewClient.shouldInterceptRequest.
 */
object AdBlocker {

    private val blockedHosts = setOf(
        // Common ad / tracker domains
        "doubleclick.net", "googleadservices.com", "googlesyndication.com",
        "adservice.google.com", "pagead2.googlesyndication.com",
        "adnxs.com", "adsrvr.org", "advertising.com", "adform.net",
        "adsymptotic.com", "adtechus.com", "amazon-adsystem.com",
        "criteo.com", "criteo.net", "outbrain.com", "taboola.com",
        "scorecardresearch.com", "quantserve.com", "moatads.com",
        "pubmatic.com", "rubiconproject.com", "openx.net",
        "smartadserver.com", "yieldmo.com", "casalemedia.com",
        "facebook.com/tr", "connect.facebook.net", "pixel.facebook.com",
        "analytics.twitter.com", "ads.twitter.com",
        "hotjar.com", "mouseflow.com", "fullstory.com",
        "newrelic.com", "nr-data.net", "segment.io", "segment.com",
        "mixpanel.com", "amplitude.com", "optimizely.com",
        "branch.io", "appsflyer.com", "adjust.com",
        "2mdn.net", "adsafeprotected.com", "serving-sys.com",
        "spotxchange.com", "teads.tv", "stickyadstv.com",
        "yieldlab.net", "adition.com", "adscale.de"
    )

    private val blockedPatterns = listOf(
        Pattern.compile(".*[./]ads?[./].*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*[./]banner[./].*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*[./]sponsor[./].*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*[./]tracker[./].*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*[./]pixel[./].*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*[./]beacon[./].*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*doubleclick.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*googlesyndication.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*pagead.*", Pattern.CASE_INSENSITIVE),
        Pattern.compile(".*adservice.*", Pattern.CASE_INSENSITIVE)
    )

    private val emptyResponse: WebResourceResponse by lazy {
        WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }

    fun shouldBlock(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        // Host check
        for (host in blockedHosts) {
            if (lower.contains(host)) return true
        }
        // Pattern check
        for (p in blockedPatterns) {
            if (p.matcher(lower).matches()) return true
        }
        return false
    }

    fun intercept(request: WebResourceRequest?): WebResourceResponse? {
        if (request == null) return null
        val url = request.url?.toString() ?: return null
        return if (shouldBlock(url)) emptyResponse else null
    }
}
