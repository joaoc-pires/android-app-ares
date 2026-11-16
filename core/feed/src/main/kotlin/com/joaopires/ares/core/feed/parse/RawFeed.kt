// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.feed.parse

import java.time.Instant

internal data class RawFeed(
    val title: String?,
    val titleIsHtml: Boolean,
    val siteUrl: String?,
    val description: String?,
    val iconUrl: String?,
    val baseUrl: String?,
    val authors: List<String>,
    val items: List<RawItem>,
)

internal data class RawItem(
    val guid: String?,
    val link: String?,
    val title: String?,
    val titleIsHtml: Boolean,
    val content: String?,
    val contentIsHtml: Boolean,
    val summary: String?,
    val summaryIsHtml: Boolean,
    val authors: List<String>,
    val published: Instant?,
    val updated: Instant?,
    val baseUrl: String?,
    val media: List<RawMedia>,
    val enclosures: List<RawEnclosure>,
    val videoId: String?,
)

internal data class RawMedia(val url: String, val width: Int?, val height: Int?, val isThumbnail: Boolean)

internal data class RawEnclosure(val url: String, val type: String?, val length: Long?)

internal class FeedParseException(val failure: com.joaopires.ares.core.feed.FetchFailure, message: String) : Exception(message)
