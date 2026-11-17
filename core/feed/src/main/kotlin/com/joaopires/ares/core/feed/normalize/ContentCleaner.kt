// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.feed.normalize

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import java.net.URI

internal data class CleanContent(val html: String, val text: String, val images: List<ContentImage>)

internal data class ContentImage(val url: String, val width: Int?, val height: Int?)

private const val MAX_HTML_CHARS = 2 * 1024 * 1024

private val trackerHosts = setOf(
    "feeds.feedburner.com", "feedproxy.google.com", "pixel.wp.com", "stats.wp.com",
    "www.google-analytics.com", "google-analytics.com", "ssl.google-analytics.com",
    "www.googletagmanager.com", "googletagmanager.com", "stats.g.doubleclick.net", "ad.doubleclick.net",
    "pixel.quantserve.com", "sb.scorecardresearch.com", "b.scorecardresearch.com", "www.facebook.com",
)

private val safelist: Safelist = Safelist.relaxed()
    .addTags("figure", "figcaption", "picture", "source", "video", "audio", "hr", "s", "mark", "del", "ins", "abbr", "time", "details", "summary")
    .addAttributes("a", "class", "title")
    .addAttributes("img", "srcset", "sizes", "loading")
    .addAttributes("source", "src", "srcset", "type", "media", "sizes")
    .addAttributes("video", "src", "poster", "controls", "width", "height")
    .addAttributes("audio", "src", "controls")
    .addAttributes("td", "colspan", "rowspan")
    .addAttributes("th", "colspan", "rowspan", "scope")
    .addAttributes("time", "datetime")
    .addAttributes("abbr", "title")
    .addProtocols("a", "href", "http", "https", "mailto")
    .addProtocols("img", "src", "http", "https")
    .addProtocols("source", "src", "http", "https")
    .addProtocols("video", "src", "http", "https")
    .addProtocols("video", "poster", "http", "https")
    .addProtocols("audio", "src", "http", "https")

internal fun cleanContent(html: String, baseUrl: String?): CleanContent {
    val dirty = Jsoup.parseBodyFragment(html.take(MAX_HTML_CHARS), baseUrl.orEmpty())
    prepare(dirty)
    val clean = Cleaner(safelist).clean(dirty)
    finish(clean)
    clean.outputSettings().prettyPrint(false)
    val images = clean.select("img[src]").mapNotNull { img ->
        img.attr("src").takeIf { it.isNotBlank() && !it.isEmojiImage() }?.let {
            ContentImage(it, img.intAttr("width"), img.intAttr("height"))
        }
    }
    return CleanContent(clean.body().html(), clean.body().text().collapseWhitespace(), images)
}

internal fun htmlToText(html: String): String = Jsoup.parseBodyFragment(html).body().text().collapseWhitespace()

internal fun plainTextToHtml(text: String): String =
    text.trim().split(Regex("\\n\\s*\\n")).joinToString("") { paragraph ->
        "<p>" + org.jsoup.nodes.Entities.escape(paragraph.trim()).replace("\n", "<br>") + "</p>"
    }

private fun prepare(document: Document) {
    document.select("script, style, noscript, link, meta, form, input, button, select, textarea, object, embed, svg, canvas, template").remove()
    document.select("div.feedflare, a[href*=feeds.feedburner.com/~ff], a[href*=feeds.feedburner.com/~a]").remove()
    document.select("img").forEach { img ->
        val src = img.absUrl("src")
        if (isTracker(src, img)) img.remove() else if (src.isNotEmpty()) img.attr("src", src)
    }
    document.select("img[srcset], source[srcset]").forEach { it.attr("srcset", resolveSrcset(it.attr("srcset"), it)) }
    document.select("iframe").forEach { iframe ->
        val src = iframe.absUrl("src")
        if (src.startsWith("https://") || src.startsWith("http://")) {
            val card = document.createElement("p")
            card.appendElement("a")
                .attr("href", src)
                .addClass("ares-embed")
                .text("Embedded content: ${hostOf(src) ?: src}")
            iframe.replaceWith(card)
        } else {
            iframe.remove()
        }
    }
}

private fun finish(document: Document) {
    document.select("a[href]").forEach { a ->
        if (!a.hasClass("ares-embed")) a.removeAttr("class")
    }
    document.select("img[src], source[src], video[src], audio[src]").forEach { it.attr("src", upgrade(it.attr("src"))) }
    document.select("video[poster]").forEach { it.attr("poster", upgrade(it.attr("poster"))) }
    document.select("img[srcset], source[srcset]").forEach { element ->
        element.attr("srcset", element.attr("srcset").split(",").joinToString(", ") { upgrade(it.trim()) })
    }
}

private fun resolveSrcset(srcset: String, element: Element): String =
    srcset.split(",").mapNotNull { candidate ->
        val parts = candidate.trim().split(Regex("\\s+"), limit = 2)
        val url = parts.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val absolute = try {
            if (element.baseUri().isEmpty()) url else URI(element.baseUri()).resolve(url).toString()
        } catch (_: Exception) {
            url
        }
        (listOf(absolute) + parts.drop(1)).joinToString(" ")
    }.joinToString(", ")

private fun isTracker(src: String, img: Element): Boolean {
    val width = img.intAttr("width")
    val height = img.intAttr("height")
    if ((width != null && width <= 1) || (height != null && height <= 1)) return true
    val host = hostOf(src) ?: return false
    return host in trackerHosts || src.contains("/~r/") || src.contains("/~ff/")
}

internal fun upgrade(url: String): String = if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

internal fun hostOf(url: String): String? = try {
    URI(url).host?.lowercase()
} catch (_: Exception) {
    null
}

internal fun String.isEmojiImage(): Boolean = contains("s.w.org/images/core/emoji")

private fun Element.intAttr(name: String): Int? = attr(name).trim().removeSuffix("px").toIntOrNull()?.takeIf { it >= 0 }

private val whitespaceRun = Regex("[\\s\\u00A0]+")

internal fun String.collapseWhitespace(): String = replace(whitespaceRun, " ").trim()
