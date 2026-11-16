// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.feed.parse

import com.joaopires.ares.core.feed.FetchFailure
import com.joaopires.ares.core.xml.parseDate
import java.net.URI

internal fun interpretXmlFeed(root: XmlElement): RawFeed = when {
    root.name == "rss" && root.namespace in Ns.RSS -> parseRss(root.child(Ns.RSS, "channel") ?: root, root)
    root.name == "RDF" && root.namespace in Ns.RDF -> parseRdf(root)
    root.name == "feed" && root.namespace in Ns.ATOM -> parseAtom(root)
    else -> throw FeedParseException(FetchFailure.NotAFeed, "unsupported root element <${root.name}>")
}

private fun parseRss(channel: XmlElement, root: XmlElement): RawFeed {
    val items = channel.children(Ns.RSS, "item").ifEmpty { root.children(Ns.RSS, "item") }
    return channelFeed(channel, items)
}

private fun parseRdf(root: XmlElement): RawFeed {
    val channel = root.child(Ns.RSS, "channel") ?: throw FeedParseException(FetchFailure.NotAFeed, "RDF without channel")
    val image = root.child(Ns.RSS, "image")
    return channelFeed(channel, root.children(Ns.RSS, "item"), image)
}

private fun channelFeed(channel: XmlElement, items: List<XmlElement>, rdfImage: XmlElement? = null): RawFeed {
    val atomLinks = channel.children(Ns.ATOM, "link")
    val image = channel.child(Ns.RSS, "image") ?: rdfImage
    return RawFeed(
        title = channel.textOf(Ns.RSS, "title"),
        titleIsHtml = true,
        siteUrl = channel.textOf(Ns.RSS, "link") ?: atomLinks.alternateHref(),
        description = channel.textOf(Ns.RSS, "description"),
        iconUrl = image?.textOf(Ns.RSS, "url") ?: channel.child(Ns.ITUNES, "image")?.attr("href"),
        baseUrl = channel.attr("base", NS_XML),
        authors = listOfNotNull(channel.textOf(Ns.DC, "creator"), channel.textOf(Ns.ITUNES, "author")),
        items = items.map { rssItem(it) },
    )
}

private fun rssItem(item: XmlElement): RawItem {
    val guid = item.child(Ns.RSS, "guid")
    val guidText = guid?.text()?.trim()?.ifEmpty { null }
    val guidIsLink = guid?.attr("isPermaLink")?.equals("false", ignoreCase = true) != true
    val link = item.textOf(Ns.RSS, "link")
        ?: item.children(Ns.ATOM, "link").alternateHref()
        ?: guidText?.takeIf { guidIsLink && it.looksLikeHttpUrl() }
    val content = item.textOf(Ns.CONTENT, "encoded")
    return RawItem(
        guid = guidText ?: item.attr("about", Ns.RDF.first()),
        link = link,
        title = item.textOf(Ns.RSS, "title") ?: item.child(Ns.MEDIA, "title")?.text(),
        titleIsHtml = true,
        content = content,
        contentIsHtml = true,
        summary = item.textOf(Ns.RSS, "description") ?: mediaDescription(item),
        summaryIsHtml = item.textOf(Ns.RSS, "description") != null,
        authors = listOfNotNull(item.textOf(Ns.RSS, "author")?.let(::rssAuthorName)) +
            item.children(Ns.DC, "creator").mapNotNull { it.text().trim().ifEmpty { null } } +
            listOfNotNull(item.textOf(Ns.ITUNES, "author")),
        published = (item.textOf(Ns.RSS, "pubDate") ?: item.textOf(Ns.DC, "date"))?.let(::parseDate),
        updated = item.textOf(Ns.ATOM, "updated")?.let(::parseDate),
        baseUrl = item.attr("base", NS_XML),
        media = media(item) + listOfNotNull(item.child(Ns.ITUNES, "image")?.attr("href")?.let { RawMedia(it, null, null, true) }),
        enclosures = item.children(Ns.RSS, "enclosure").mapNotNull { enclosure ->
            enclosure.attr("url")?.trim()?.ifEmpty { null }?.let {
                RawEnclosure(it, enclosure.attr("type"), enclosure.attr("length")?.trim()?.toLongOrNull())
            }
        },
        videoId = item.textOf(Ns.YOUTUBE, "videoId"),
    )
}

private fun parseAtom(feed: XmlElement): RawFeed {
    val feedAuthors = feed.children(Ns.ATOM, "author").mapNotNull { it.textOf(Ns.ATOM, "name") }
    return RawFeed(
        title = feed.child(Ns.ATOM, "title")?.let(::atomText),
        titleIsHtml = feed.child(Ns.ATOM, "title")?.isAtomHtml() ?: false,
        siteUrl = feed.children(Ns.ATOM, "link").alternateHref(),
        description = (feed.child(Ns.ATOM, "subtitle") ?: feed.child(Ns.ATOM, "tagline"))?.let(::atomText),
        iconUrl = feed.textOf(Ns.ATOM, "icon") ?: feed.textOf(Ns.ATOM, "logo"),
        baseUrl = feed.attr("base", NS_XML),
        authors = feedAuthors,
        items = feed.children(Ns.ATOM, "entry").map { atomEntry(it, feedAuthors) },
    )
}

private fun atomEntry(entry: XmlElement, feedAuthors: List<String>): RawItem {
    val content = entry.child(Ns.ATOM, "content")
    val summary = entry.child(Ns.ATOM, "summary")
    val title = entry.child(Ns.ATOM, "title")
    val authors = entry.children(Ns.ATOM, "author").mapNotNull { it.textOf(Ns.ATOM, "name") }.ifEmpty { feedAuthors }
    val hasInlineContent = content != null && content.attr("src") == null
    return RawItem(
        guid = entry.textOf(Ns.ATOM, "id"),
        link = entry.children(Ns.ATOM, "link").alternateHref(),
        title = title?.let(::atomText) ?: entry.child(Ns.MEDIA, "group")?.child(Ns.MEDIA, "title")?.text(),
        titleIsHtml = title?.isAtomHtml() ?: false,
        content = if (hasInlineContent) atomText(content) else null,
        contentIsHtml = content?.isAtomHtml() ?: false,
        summary = summary?.let(::atomText) ?: mediaDescription(entry),
        summaryIsHtml = summary?.isAtomHtml() ?: false,
        authors = authors,
        published = (entry.textOf(Ns.ATOM, "published") ?: entry.textOf(Ns.ATOM, "issued"))?.let(::parseDate),
        updated = (entry.textOf(Ns.ATOM, "updated") ?: entry.textOf(Ns.ATOM, "modified"))?.let(::parseDate),
        baseUrl = content?.attr("base", NS_XML) ?: entry.attr("base", NS_XML),
        media = media(entry),
        enclosures = entry.children(Ns.ATOM, "link")
            .filter { it.attr("rel") == "enclosure" }
            .mapNotNull { link ->
                link.attr("href")?.let { RawEnclosure(it, link.attr("type"), link.attr("length")?.trim()?.toLongOrNull()) }
            },
        videoId = entry.textOf(Ns.YOUTUBE, "videoId"),
    )
}

private fun atomText(element: XmlElement): String {
    val type = element.attr("type")?.lowercase()
    if (type == "xhtml") {
        val div = element.elements().firstOrNull { it.name == "div" }
        return (div ?: element).innerXml().trim()
    }
    return element.text().trim()
}

private fun XmlElement.isAtomHtml(): Boolean =
    when (attr("type")?.lowercase()) {
        "html", "xhtml", "text/html", "application/xhtml+xml" -> true
        else -> false
    }

private fun mediaDescription(item: XmlElement): String? =
    (item.child(Ns.MEDIA, "description") ?: item.child(Ns.MEDIA, "group")?.child(Ns.MEDIA, "description"))
        ?.text()?.trim()?.ifEmpty { null }

private fun media(item: XmlElement): List<RawMedia> {
    val result = mutableListOf<RawMedia>()
    fun collect(element: XmlElement) {
        element.children(Ns.MEDIA, "thumbnail").forEach { thumbnail ->
            thumbnail.attr("url")?.let { result += RawMedia(it, thumbnail.intAttr("width"), thumbnail.intAttr("height"), true) }
        }
        element.children(Ns.MEDIA, "content").forEach { content ->
            val url = content.attr("url") ?: return@forEach
            val isImage = content.attr("medium") == "image" || content.attr("type")?.startsWith("image/") == true ||
                (content.attr("medium") == null && content.attr("type") == null && url.looksLikeImage())
            if (isImage) result += RawMedia(url, content.intAttr("width"), content.intAttr("height"), false)
            collect(content)
        }
        element.children(Ns.MEDIA, "group").forEach { collect(it) }
    }
    collect(item)
    return result
}

private fun List<XmlElement>.alternateHref(): String? {
    val alternate = firstOrNull { (it.attr("rel") ?: "alternate") == "alternate" && it.attr("href") != null }
    return (alternate ?: firstOrNull { it.attr("rel") == null && it.attr("href") != null })?.attr("href")?.trim()
}

private fun XmlElement.textOf(namespaces: Collection<String>, name: String): String? =
    child(namespaces, name)?.text()?.trim()?.ifEmpty { null }

private fun XmlElement.intAttr(name: String): Int? = attr(name)?.trim()?.toIntOrNull()?.takeIf { it > 0 }

private fun rssAuthorName(value: String): String {
    val match = Regex("""^\s*\S+@\S+\s*\((.+)\)\s*$""").matchEntire(value)
    return (match?.groupValues?.get(1) ?: value).trim()
}

private fun String.looksLikeHttpUrl(): Boolean = startsWith("http://") || startsWith("https://")

private fun String.looksLikeImage(): Boolean = try {
    URI(this).path.orEmpty().lowercase().let { path -> listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif").any { path.endsWith(it) } }
} catch (_: Exception) {
    false
}
