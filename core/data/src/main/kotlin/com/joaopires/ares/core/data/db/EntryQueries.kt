// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import com.joaopires.ares.core.data.model.EntryQuery
import com.joaopires.ares.core.data.model.EntryScope
import java.time.Instant

internal const val TODAY_HOURS = 24L

internal fun buildEntryQuery(query: EntryQuery, now: Instant): SimpleSQLiteQuery {
    val conditions = mutableListOf<String>()
    val args = mutableListOf<Any>()
    when (val scope = query.scope) {
        EntryScope.All -> Unit
        EntryScope.Unread -> conditions += "e.isRead = 0"
        EntryScope.Today -> {
            conditions += "e.sortDate >= ?"
            args += now.minusSeconds(TODAY_HOURS * 3600).toEpochMilli()
        }
        EntryScope.Bookmarks -> conditions += "e.isBookmarked = 1"
        is EntryScope.Feed -> {
            conditions += "e.feedId = ?"
            args += scope.feedId
        }
        is EntryScope.Section -> {
            conditions += "f.sectionId = ?"
            args += scope.sectionId
        }
    }
    if (query.unreadOnly && query.scope != EntryScope.Unread) conditions += "e.isRead = 0"
    ftsMatchExpression(query.search)?.let {
        conditions += "e.rowid IN (SELECT rowid FROM entries_fts WHERE entries_fts MATCH ?)"
        args += it
    }
    val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
    val sql = """
        SELECT e.id, e.feedId, e.url, e.title, e.author, e.excerpt, e.sortDate, e.thumbnailUrl, e.thumbnailWidth,
               e.thumbnailHeight, e.videoId, e.isRead, e.isBookmarked,
               COALESCE(f.customTitle, f.title, e.feedTitleSnapshot) AS feedTitle,
               COALESCE(f.iconUrl, e.feedIconSnapshot) AS feedIconUrl,
               f.siteUrl AS feedSiteUrl
        FROM entries e LEFT JOIN feeds f ON f.id = e.feedId
        $where
        ORDER BY e.sortDate DESC, e.id
    """.trimIndent()
    return SimpleSQLiteQuery(sql, args.toTypedArray())
}

internal fun ftsMatchExpression(search: String?): String? {
    val terms = search.orEmpty()
        .split(Regex("\\s+"))
        .map { term -> term.filter { it.isLetterOrDigit() } }
        .filter { it.isNotEmpty() }
    if (terms.isEmpty()) return null
    return terms.joinToString(" ") { "\"$it*\"" }
}
