// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data

import com.joaopires.ares.core.data.db.AresDatabase
import com.joaopires.ares.core.data.db.EnclosureEntity
import com.joaopires.ares.core.data.db.EntryContentUpdate
import com.joaopires.ares.core.data.db.EntryEntity
import com.joaopires.ares.core.data.db.FeedEntity
import com.joaopires.ares.core.data.model.NewEntry
import com.joaopires.ares.core.feed.Entry
import java.time.Instant

private const val SQL_CHUNK = 500

internal fun matchesAnyFilter(title: String?, text: String?, expressions: List<String>): Boolean =
    expressions.any { expression ->
        title?.contains(expression, ignoreCase = true) == true || text?.contains(expression, ignoreCase = true) == true
    }

internal fun cleanExpressions(expressions: List<String>): List<String> =
    expressions.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

internal suspend fun writeEntries(db: AresDatabase, feed: FeedEntity, entries: List<Entry>, now: Instant): List<NewEntry> {
    val dao = db.entryDao()
    val expressions = cleanExpressions(db.filterDao().expressions())
    val ids = entries.map { it.id }
    val existing = ids.chunked(SQL_CHUNK).flatMap { dao.existing(it) }.associateBy { it.id }
    val tombstoned = ids.chunked(SQL_CHUNK).flatMap { dao.tombstoned(it) }.toSet()

    val fresh = entries.filter { it.id !in existing && it.id !in tombstoned && !matchesAnyFilter(it.title, it.contentText, expressions) }
    val updates = entries.filter { it.id in existing }

    dao.insert(fresh.map { it.toEntity(feed.id, now) })
    dao.updateContent(updates.map { it.toUpdate(feed.id, existing.getValue(it.id).firstSeenAt) })

    val touched = (fresh + updates).map { it.id }
    touched.chunked(SQL_CHUNK).forEach { dao.deleteEnclosures(it) }
    dao.insertEnclosures(
        (fresh + updates).flatMap { entry -> entry.enclosures.map { EnclosureEntity(entryId = entry.id, url = it.url, type = it.type, length = it.length) } },
    )

    val feedTitle = feed.customTitle ?: feed.title ?: feed.url
    return fresh.map { NewEntry(it.id, feed.id, feedTitle, it.title, feed.notify) }
}

private fun Entry.toEntity(feedId: Long, now: Instant) = EntryEntity(
    id = id,
    feedId = feedId,
    url = url,
    title = title,
    author = author,
    contentHtml = contentHtml,
    contentText = contentText,
    excerpt = excerpt,
    publishedAt = publishedAt,
    firstSeenAt = now,
    sortDate = publishedAt ?: now,
    updatedAt = updatedAt,
    thumbnailUrl = thumbnail?.url,
    thumbnailWidth = thumbnail?.width,
    thumbnailHeight = thumbnail?.height,
    videoId = videoId,
)

private fun Entry.toUpdate(feedId: Long, firstSeenAt: Instant) = EntryContentUpdate(
    id = id,
    feedId = feedId,
    url = url,
    title = title,
    author = author,
    contentHtml = contentHtml,
    contentText = contentText,
    excerpt = excerpt,
    publishedAt = publishedAt,
    sortDate = publishedAt ?: firstSeenAt,
    updatedAt = updatedAt,
    thumbnailUrl = thumbnail?.url,
    thumbnailWidth = thumbnail?.width,
    thumbnailHeight = thumbnail?.height,
    videoId = videoId,
    feedTitleSnapshot = null,
    feedIconSnapshot = null,
)
