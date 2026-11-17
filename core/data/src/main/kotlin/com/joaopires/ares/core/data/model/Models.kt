// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data.model

import com.joaopires.ares.core.data.db.FeedStatus
import java.time.Instant

public data class Section(val id: Long, val name: String)

public data class FeedSummary(
    val id: Long,
    val url: String,
    val title: String,
    val customTitle: String?,
    val siteUrl: String?,
    val iconUrl: String?,
    val sectionId: Long?,
    val notify: Boolean,
    val status: FeedStatus,
    val lastError: String?,
    val unreadCount: Int,
)

public data class EntryListItem(
    val id: String,
    val feedId: Long?,
    val url: String?,
    val title: String?,
    val author: String?,
    val excerpt: String?,
    val sortDate: Instant,
    val thumbnailUrl: String?,
    val thumbnailWidth: Int?,
    val thumbnailHeight: Int?,
    val videoId: String?,
    val isRead: Boolean,
    val isBookmarked: Boolean,
    val feedTitle: String?,
    val feedIconUrl: String?,
    val feedSiteUrl: String?,
)

public data class EntryDetail(
    val id: String,
    val feedId: Long?,
    val url: String?,
    val title: String?,
    val author: String?,
    val contentHtml: String?,
    val fullContentHtml: String?,
    val publishedAt: Instant?,
    val sortDate: Instant,
    val thumbnailUrl: String?,
    val videoId: String?,
    val isRead: Boolean,
    val isBookmarked: Boolean,
    val feedTitle: String?,
    val feedIconUrl: String?,
    val feedSiteUrl: String?,
)

public data class EntryEnclosure(val url: String, val type: String?, val length: Long?)

public data class HomeCounts(val unread: Int, val today: Int, val bookmarks: Int)

public sealed interface EntryScope {
    public data object All : EntryScope
    public data object Unread : EntryScope
    public data object Today : EntryScope
    public data object Bookmarks : EntryScope
    public data class Feed(val feedId: Long) : EntryScope
    public data class Section(val sectionId: Long) : EntryScope
}

public data class EntryQuery(
    val scope: EntryScope,
    val unreadOnly: Boolean = false,
    val search: String? = null,
)

public data class NewEntry(val id: String, val feedId: Long, val feedTitle: String, val title: String?, val notify: Boolean)

public data class Filter(val id: Long, val expression: String)
