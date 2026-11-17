// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.feed

private val blockedDomains = setOf(
    "google-analytics.com",
    "googletagmanager.com",
    "googletagservices.com",
    "googlesyndication.com",
    "googleadservices.com",
    "doubleclick.net",
    "fonts.googleapis.com",
    "fonts.gstatic.com",
    "feedburner.com",
    "feedproxy.google.com",
    "pixel.wp.com",
    "stats.wp.com",
    "quantserve.com",
    "scorecardresearch.com",
    "connect.facebook.net",
)

public fun isBlockedHost(host: String): Boolean {
    val normalized = host.lowercase().trimEnd('.')
    return blockedDomains.any { normalized == it || normalized.endsWith(".$it") }
}
