// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data

import androidx.room.withTransaction
import com.joaopires.ares.core.data.db.AresDatabase
import com.joaopires.ares.core.data.db.ExportedFeed
import com.joaopires.ares.core.data.db.FeedEntity
import com.joaopires.ares.core.data.db.SectionEntity
import com.joaopires.ares.core.data.model.FeedSummary
import com.joaopires.ares.core.data.model.NewEntry
import com.joaopires.ares.core.data.model.Section
import com.joaopires.ares.core.feed.Feed
import com.joaopires.ares.core.feed.Validators
import kotlinx.coroutines.flow.Flow
import java.time.Clock

public sealed interface SubscribeResult {
    public data class Subscribed(val feedId: Long, val newEntries: List<NewEntry>) : SubscribeResult
    public data class AlreadySubscribed(val feedId: Long) : SubscribeResult
}

public class FeedRepository(private val db: AresDatabase, private val clock: Clock) {

    public val feeds: Flow<List<FeedSummary>> = db.feedDao().observeSummaries()

    public val sections: Flow<List<Section>> = db.sectionDao().observeAll()

    public suspend fun subscribe(
        feed: Feed,
        validators: Validators,
        customTitle: String? = null,
        sectionId: Long? = null,
    ): SubscribeResult = db.withTransaction {
        db.feedDao().byUrl(feed.url)?.let { return@withTransaction SubscribeResult.AlreadySubscribed(it.id) }
        val now = clock.instant()
        val entity = FeedEntity(
            url = feed.url,
            entryKey = feed.url,
            title = feed.title,
            customTitle = customTitle?.trim()?.ifEmpty { null },
            siteUrl = feed.siteUrl,
            iconUrl = feed.iconUrl,
            description = feed.description,
            sectionId = sectionId,
            etag = validators.etag,
            lastModified = validators.lastModified,
            lastFetchedAt = now,
            lastSuccessAt = now,
            createdAt = now,
        )
        val id = db.feedDao().insert(entity)
        SubscribeResult.Subscribed(id, writeEntries(db, entity.copy(id = id), feed.entries, now))
    }

    public suspend fun updateFeed(feedId: Long, customTitle: String?, url: String, notify: Boolean, sectionId: Long?): Boolean =
        db.withTransaction {
            val feed = db.feedDao().byId(feedId) ?: return@withTransaction false
            val newUrl = url.trim()
            if (newUrl != feed.url && db.feedDao().byUrl(newUrl) != null) return@withTransaction false
            db.feedDao().update(
                feed.copy(
                    customTitle = customTitle?.trim()?.ifEmpty { null },
                    url = newUrl,
                    notify = notify,
                    sectionId = sectionId,
                    etag = if (newUrl != feed.url) null else feed.etag,
                    lastModified = if (newUrl != feed.url) null else feed.lastModified,
                ),
            )
            true
        }

    public suspend fun deleteFeed(feedId: Long) {
        db.withTransaction {
            val feed = db.feedDao().byId(feedId) ?: return@withTransaction
            db.entryDao().snapshotBookmarks(feedId, feed.customTitle ?: feed.title ?: feed.url, feed.iconUrl)
            db.entryDao().deleteUnbookmarked(feedId)
            db.feedDao().delete(feedId)
        }
    }

    public suspend fun exportList(): List<ExportedFeed> = db.feedDao().exportList()

    public suspend fun ensureSection(name: String): Long? = db.withTransaction {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withTransaction null
        db.sectionDao().byName(trimmed)?.id ?: db.sectionDao().insert(SectionEntity(name = trimmed))
    }

    public suspend fun createSection(name: String): Long? = db.withTransaction {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || db.sectionDao().byName(trimmed) != null) return@withTransaction null
        db.sectionDao().insert(SectionEntity(name = trimmed))
    }

    public suspend fun renameSection(sectionId: Long, name: String): Boolean = db.withTransaction {
        val trimmed = name.trim()
        val existing = db.sectionDao().byName(trimmed)
        if (trimmed.isEmpty() || (existing != null && existing.id != sectionId)) return@withTransaction false
        db.sectionDao().rename(sectionId, trimmed)
        true
    }

    public suspend fun deleteSection(sectionId: Long) {
        db.sectionDao().delete(sectionId)
    }

    public suspend fun setSectionFeeds(sectionId: Long, feedIds: List<Long>) {
        db.withTransaction {
            db.feedDao().removeFromSectionExcept(sectionId, feedIds)
            if (feedIds.isNotEmpty()) db.feedDao().assignSection(feedIds, sectionId)
        }
    }
}
