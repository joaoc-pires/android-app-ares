// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.article

import com.joaopires.ares.core.feed.isBlockedHost
import com.joaopires.ares.ui.images.imageRequestUrl
import java.net.URI

enum class RequestDecision { Allow, Block }

fun subresourceDecision(url: String, allowYouTube: Boolean): RequestDecision {
    if (url.startsWith("data:", ignoreCase = true)) return RequestDecision.Allow
    if (!url.startsWith("https://", ignoreCase = true)) return RequestDecision.Block
    val host = try {
        URI(url).host?.lowercase()
    } catch (_: Exception) {
        null
    } ?: return RequestDecision.Block
    if (isBlockedHost(host)) return RequestDecision.Block
    if (imageRequestUrl(url, allowYouTube) == null) return RequestDecision.Block
    return RequestDecision.Allow
}

sealed interface LinkAction {
    data class OpenEntry(val entryId: String) : LinkAction
    data class OpenVideo(val url: String) : LinkAction
    data class OpenAttachment(val url: String, val type: String?) : LinkAction
    data class OpenLink(val url: String) : LinkAction
    data object Ignore : LinkAction
}

fun classifyLink(url: String, watchUrl: String?, attachments: Map<String, String?>): LinkAction? = when {
    url.isBlank() || url.startsWith("javascript:", ignoreCase = true) || url.startsWith("about:", ignoreCase = true) -> LinkAction.Ignore
    url == watchUrl -> LinkAction.OpenVideo(url)
    url in attachments -> LinkAction.OpenAttachment(url, attachments[url])
    !url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true) -> LinkAction.OpenLink(url)
    else -> null
}
