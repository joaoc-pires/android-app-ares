// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.addfeed

import android.content.Intent
import com.joaopires.ares.notifications.ACTION_OPEN_ENTRY
import java.net.URI

sealed interface IncomingLink {
    data class AddFeed(val url: String) : IncomingLink
    data class ImportOpml(val uri: String) : IncomingLink
    data class OpenEntry(val entryId: String) : IncomingLink
}

val FEED_MIME_TYPES = setOf("application/rss+xml", "application/atom+xml", "application/feed+json")
val OPML_MIME_TYPES = setOf("text/x-opml", "application/x-opml", "text/xml", "application/xml")

private val urlInText = Regex("""(?i)\b(?:feeds?:(?:https?:)?//|https?://)[^\s<>"'`]+""")

fun firstUrlIn(text: String): String? =
    urlInText.find(text)?.value?.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '>')

fun incomingLink(action: String?, data: String?, type: String?, text: String?, stream: String?, entryId: String? = null): IncomingLink? {
    if (action == ACTION_OPEN_ENTRY) return entryId?.takeIf { it.isNotBlank() }?.let(IncomingLink::OpenEntry)
    val mime = type?.lowercase()?.substringBefore(';')?.trim()
    return when (action) {
        Intent.ACTION_SEND -> when {
            stream != null && (mime in OPML_MIME_TYPES || stream.endsWith(".opml", ignoreCase = true)) -> IncomingLink.ImportOpml(stream)
            text != null -> firstUrlIn(text)?.let(::normalizeFeedInput)?.let { IncomingLink.AddFeed(it) }
            else -> null
        }
        Intent.ACTION_VIEW -> {
            val uri = data ?: return null
            when (uri.substringBefore(':').lowercase()) {
                "feed", "feeds" -> normalizeFeedInput(uri)?.let { IncomingLink.AddFeed(it) }
                "https", "http" -> if (mime in FEED_MIME_TYPES) normalizeFeedInput(uri)?.let { IncomingLink.AddFeed(it) } else null
                "content", "file" ->
                    if (mime in OPML_MIME_TYPES || uri.endsWith(".opml", ignoreCase = true)) IncomingLink.ImportOpml(uri) else null
                else -> null
            }
        }
        else -> null
    }
}

fun normalizeFeedInput(input: String): String? {
    var url = input.trim()
    if (url.isEmpty() || url.any { it.isWhitespace() }) return null
    url = when {
        url.startsWith("feed://", ignoreCase = true) -> "https://" + url.substring(7)
        url.startsWith("feeds://", ignoreCase = true) -> "https://" + url.substring(8)
        url.startsWith("feed:", ignoreCase = true) -> url.substring(5)
        url.startsWith("feeds:", ignoreCase = true) -> url.substring(6)
        else -> url
    }
    if (!url.contains("://")) url = "https://$url"
    if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) return null
    val host = try {
        URI(url).host
    } catch (_: Exception) {
        null
    }
    return if (host.isNullOrBlank() || !host.contains('.')) null else url
}
