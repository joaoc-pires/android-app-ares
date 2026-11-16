// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.feed.normalize

import com.joaopires.ares.core.feed.Enclosure
import com.joaopires.ares.core.feed.Entry
import com.joaopires.ares.core.feed.Feed
import com.joaopires.ares.core.feed.Thumbnail
import com.joaopires.ares.core.feed.parse.RawFeed
import com.joaopires.ares.core.feed.parse.RawItem
import com.joaopires.ares.core.feed.parse.RawMedia
import com.joaopires.ares.core.xml.decodeEntities
import java.net.URI
import java.security.MessageDigest
import java.time.Instant

private const val EXCERPT_LENGTH = 300
private const val MAX_ITEMS = 5_000

internal fun normalize(raw: RawFeed, feedUrl: String, documentUrl: String, feedKey: String, now: Instant): Feed {
    val feedBase = resolve(documentUrl, raw.baseUrl) ?: documentUrl
    val siteUrl = resolve(feedBase, raw.siteUrl)?.takeIf { it.isHttp() } ?: hostRoot(documentUrl)
    val seen = HashSet<String>()
    val entries = raw.items.asSequence()
        .take(MAX_ITEMS)
        .map { normalizeItem(it, feedBase, siteUrl, feedKey, now) }
        .filter { seen.add(it.id) }
        .toList()
    return Feed(
        url = feedUrl,
        title = raw.title?.let { cleanTitle(it, raw.titleIsHtml) },
        siteUrl = siteUrl,
        description = raw.description?.let { htmlToText(it) }?.ifEmpty { null },
        iconUrl = resolve(feedBase, raw.iconUrl)?.takeIf { it.isHttp() }?.let(::upgrade),
        entries = entries,
    )
}

private fun normalizeItem(item: RawItem, feedBase: String, siteUrl: String?, feedKey: String, now: Instant): Entry {
    val link = resolve(feedBase, item.link)?.takeIf { it.isHttp() }
    val base = resolve(feedBase, item.baseUrl) ?: link ?: siteUrl ?: feedBase

    val bodyHtml = item.content?.let { if (item.contentIsHtml) it else plainTextToHtml(it) }
        ?: item.summary?.let { if (item.summaryIsHtml) it else plainTextToHtml(it) }
    val clean = bodyHtml?.let { cleanContent(it, base) }

    val title = item.title?.let { cleanTitle(it, item.titleIsHtml) }?.ifEmpty { null }
    val published = (item.published ?: item.updated)?.coerceAtMost(now)
    val updated = item.updated?.coerceAtMost(now)
    val videoId = item.videoId ?: link?.let(::youtubeVideoId)

    return Entry(
        id = entryId(feedKey, item, link, title, published, clean?.text),
        url = link,
        title = title,
        author = item.authors.map { cleanTitle(it, isHtml = true) }.filter { it.isNotEmpty() }.distinct().joinToString(", ").ifEmpty { null },
        contentHtml = clean?.html?.ifBlank { null },
        contentText = clean?.text?.ifEmpty { null },
        excerpt = clean?.text?.let(::excerpt)?.ifEmpty { null },
        publishedAt = published,
        updatedAt = updated,
        thumbnail = thumbnail(item.media + imageEnclosures(item), clean?.images.orEmpty(), base, videoId),
        videoId = videoId,
        enclosures = item.enclosures.mapNotNull { enclosure ->
            resolve(base, enclosure.url)?.takeIf { it.isHttp() }?.let { Enclosure(upgrade(it), enclosure.type, enclosure.length) }
        },
    )
}

private fun entryId(feedKey: String, item: RawItem, link: String?, title: String?, published: Instant?, text: String?): String {
    val key = item.guid?.trim()?.ifEmpty { null }
        ?: link
        ?: "content:" + sha256("${title.orEmpty()}|${published ?: ""}|${text.orEmpty().take(200)}")
    return sha256("$feedKey\n$key")
}

private fun imageEnclosures(item: RawItem): List<RawMedia> =
    item.enclosures.filter { it.type?.startsWith("image/") == true }.map { RawMedia(it.url, null, null, false) }

private fun thumbnail(media: List<RawMedia>, images: List<ContentImage>, base: String, videoId: String?): Thumbnail? {
    val candidates = media.sortedByDescending { it.isThumbnail }.map { Triple(it.url, it.width, it.height) } +
        images.map { Triple(it.url, it.width, it.height) }
    for ((url, width, height) in candidates) {
        val absolute = resolve(base, url)?.takeIf { it.isHttp() && !it.isEmojiImage() } ?: continue
        if ((width != null && width < 32) || (height != null && height < 32)) continue
        val (patternWidth, patternHeight) = dimensionsFromUrl(absolute)
        return Thumbnail(upgrade(absolute), width ?: patternWidth, height ?: patternHeight)
    }
    return videoId?.let { Thumbnail("https://i.ytimg.com/vi/$it/hqdefault.jpg", 480, 360) }
}

private fun dimensionsFromUrl(url: String): Pair<Int?, Int?> {
    Regex("""(?<![\d])(\d{2,4})x(\d{2,4})(?![\d])""").find(url)?.let {
        return it.groupValues[1].toInt() to it.groupValues[2].toInt()
    }
    val width = Regex("""[?&](?:w|width)=(\d{2,4})(?:&|$)""").find(url)?.groupValues?.get(1)?.toInt()
    val height = Regex("""[?&](?:h|height)=(\d{2,4})(?:&|$)""").find(url)?.groupValues?.get(1)?.toInt()
    return width to height
}

internal fun youtubeVideoId(url: String): String? {
    val patterns = listOf(
        Regex("""^https?://(?:www\.|m\.)?youtube\.com/watch\?(?:.*&)?v=([A-Za-z0-9_-]{11})"""),
        Regex("""^https?://youtu\.be/([A-Za-z0-9_-]{11})"""),
        Regex("""^https?://(?:www\.)?youtube\.com/(?:shorts|embed|live)/([A-Za-z0-9_-]{11})"""),
    )
    return patterns.firstNotNullOfOrNull { it.find(url)?.groupValues?.get(1) }
}

private fun cleanTitle(value: String, isHtml: Boolean): String {
    val text = if (isHtml) htmlToText(value) else value.collapseWhitespace()
    return decodeEntities(text).collapseWhitespace()
}

private fun excerpt(text: String): String {
    if (text.length <= EXCERPT_LENGTH) return text
    val cut = text.take(EXCERPT_LENGTH)
    val boundary = cut.lastIndexOf(' ').takeIf { it > EXCERPT_LENGTH / 2 } ?: cut.length
    return cut.take(boundary).trimEnd() + "\u2026"
}

internal fun resolve(base: String?, value: String?): String? {
    val target = value?.trim()?.ifEmpty { null } ?: return null
    return try {
        if (base.isNullOrBlank()) URI(target).toString() else URI(base).resolve(URI(target.replace(" ", "%20"))).toString()
    } catch (_: Exception) {
        if (target.isHttp()) target else null
    }
}

private fun hostRoot(url: String): String? = hostOf(url)?.let { "https://$it/" }

private fun String.isHttp(): Boolean = startsWith("https://", ignoreCase = true) || startsWith("http://", ignoreCase = true)

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
