// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.feed.parse

import com.joaopires.ares.core.feed.FetchFailure
import com.joaopires.ares.core.xml.parseDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun parseJsonFeed(text: String): RawFeed {
    val root = try {
        Json.parseToJsonElement(text)
    } catch (e: Exception) {
        throw FeedParseException(FetchFailure.Malformed, "malformed JSON: ${e.message}")
    } as? JsonObject ?: throw FeedParseException(FetchFailure.NotAFeed, "JSON root is not an object")

    if (root.string("version")?.startsWith("https://jsonfeed.org/version/") != true) {
        throw FeedParseException(FetchFailure.NotAFeed, "not a JSON Feed")
    }
    val feedAuthors = authors(root)
    return RawFeed(
        title = root.string("title"),
        titleIsHtml = false,
        siteUrl = root.string("home_page_url"),
        description = root.string("description"),
        iconUrl = root.string("icon") ?: root.string("favicon"),
        baseUrl = null,
        authors = feedAuthors,
        items = (root["items"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map { item ->
            val html = item.string("content_html")
            val image = item.string("image") ?: item.string("banner_image")
            RawItem(
                guid = item.string("id"),
                link = item.string("url") ?: item.string("external_url"),
                title = item.string("title"),
                titleIsHtml = false,
                content = html ?: item.string("content_text"),
                contentIsHtml = html != null,
                summary = item.string("summary"),
                summaryIsHtml = false,
                authors = authors(item).ifEmpty { feedAuthors },
                published = item.string("date_published")?.let(::parseDate),
                updated = item.string("date_modified")?.let(::parseDate),
                baseUrl = null,
                media = listOfNotNull(image?.let { RawMedia(it, null, null, true) }),
                enclosures = (item["attachments"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { attachment ->
                    attachment.string("url")?.let {
                        RawEnclosure(it, attachment.string("mime_type"), attachment.string("size_in_bytes")?.toLongOrNull())
                    }
                },
                videoId = null,
            )
        },
    )
}

private fun authors(element: JsonObject): List<String> {
    val list = (element["authors"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { it.string("name") }
    return list.ifEmpty { listOfNotNull((element["author"] as? JsonObject)?.string("name")) }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
