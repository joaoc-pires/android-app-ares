// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.opml

import java.net.URI
import java.time.Instant

public data class OpmlDocument(
    val title: String?,
    val dateCreated: Instant?,
    val outlines: List<OpmlOutline>,
) {
    public companion object
}

public data class OpmlOutline(
    val text: String,
    val title: String? = null,
    val type: String? = null,
    val xmlUrl: String? = null,
    val htmlUrl: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val children: List<OpmlOutline> = emptyList(),
)

public data class OpmlFeed(
    val url: String,
    val title: String,
    val siteUrl: String?,
    val section: String?,
)

public fun OpmlDocument.feeds(): List<OpmlFeed> {
    val result = LinkedHashMap<String, OpmlFeed>()

    fun visit(outline: OpmlOutline, section: String?) {
        val url = outline.xmlUrl
        if (url != null && url !in result) {
            result[url] = OpmlFeed(
                url = url,
                title = outline.text.ifBlank { null }
                    ?: outline.title?.ifBlank { null }
                    ?: hostOf(url)
                    ?: url,
                siteUrl = outline.htmlUrl?.ifBlank { null },
                section = section,
            )
        }
        val childSection = if (url == null) outline.text.ifBlank { null } ?: section else section
        outline.children.forEach { visit(it, childSection) }
    }

    outlines.forEach { visit(it, null) }
    return result.values.toList()
}

public fun OpmlDocument.Companion.of(title: String, feeds: List<OpmlFeed>, now: Instant): OpmlDocument {
    val byTitle = compareBy<OpmlFeed> { it.title.lowercase() }
    val (unsectioned, sectioned) = feeds.partition { it.section.isNullOrBlank() }
    val outlines = unsectioned.sortedWith(byTitle).map { it.toOutline() } +
        sectioned
            .groupBy { it.section!! }
            .toSortedMap(compareBy { it.lowercase() })
            .map { (section, sectionFeeds) ->
                OpmlOutline(
                    text = section,
                    children = sectionFeeds.sortedWith(byTitle).map { it.toOutline() },
                )
            }
    return OpmlDocument(title = title, dateCreated = now, outlines = outlines)
}

private fun OpmlFeed.toOutline(): OpmlOutline = OpmlOutline(
    text = title,
    title = title,
    type = "rss",
    xmlUrl = url,
    htmlUrl = siteUrl,
)

private fun hostOf(url: String): String? = try {
    URI(url).host?.ifBlank { null }
} catch (_: Exception) {
    null
}
