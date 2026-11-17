// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data

import androidx.room.withTransaction
import com.joaopires.ares.core.data.db.AresDatabase
import com.joaopires.ares.core.data.db.ExportedFeed
import com.joaopires.ares.core.data.db.FeedEntity
import com.joaopires.ares.core.data.db.FeedStatus
import com.joaopires.ares.core.data.db.SectionEntity
import com.joaopires.ares.core.data.model.FeedSettings
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

public enum class UpdateFeedResult { Saved, UrlTaken, NotFound }

public sealed interface SaveSectionResult {
    public data class Saved(val sectionId: Long) : SaveSectionResult
    public data object EmptyName : SaveSectionResult
    public data object NameTaken : SaveSectionResult
    public data object NotFound : SaveSectionResult
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

    public fun feedSettings(feedId: Long): Flow<FeedSettings?> = db.feedDao().observeSettings(feedId)

    public suspend fun sectionFeedIds(sectionId: Long): List<Long> = db.feedDao().idsInSection(sectionId)

    public suspend fun updateFeed(
        feedId: Long,
        customTitle: String?,
        url: String,
        sectionId: Long?,
        notify: Boolean? = null,
    ): UpdateFeedResult = db.withTransaction {
        val feed = db.feedDao().byId(feedId) ?: return@withTransaction UpdateFeedResult.NotFound
        val newUrl = url.trim()
        if (newUrl != feed.url && db.feedDao().byUrl(newUrl) != null) return@withTransaction UpdateFeedResult.UrlTaken
        db.feedDao().update(
            feed.copy(
                customTitle = customTitle?.trim()?.ifEmpty { null },
                url = newUrl,
                notify = notify ?: feed.notify,
                sectionId = sectionId,
                etag = if (newUrl != feed.url) null else feed.etag,
                lastModified = if (newUrl != feed.url) null else feed.lastModified,
            ),
        )
        UpdateFeedResult.Saved
    }

    public suspend fun reactivate(feedId: Long) {
        db.withTransaction {
            val feed = db.feedDao().byId(feedId) ?: return@withTransaction
            db.feedDao().update(feed.copy(status = FeedStatus.Active, lastError = null, consecutiveFailures = 0))
        }
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

    public suspend fun saveSection(sectionId: Long?, name: String, feedIds: List<Long>): SaveSectionResult = db.withTransaction {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withTransaction SaveSectionResult.EmptyName
        val existing = db.sectionDao().byName(trimmed)
        if (existing != null && existing.id != sectionId) return@withTransaction SaveSectionResult.NameTaken
        val id = if (sectionId == null) {
            db.sectionDao().insert(SectionEntity(name = trimmed))
        } else {
            if (db.sectionDao().rename(sectionId, trimmed) == 0) return@withTransaction SaveSectionResult.NotFound
            sectionId
        }
        db.feedDao().removeFromSectionExcept(id, feedIds)
        if (feedIds.isNotEmpty()) db.feedDao().assignSection(feedIds, id)
        SaveSectionResult.Saved(id)
    }

    public suspend fun deleteSection(sectionId: Long) {
        db.sectionDao().delete(sectionId)
    }

}
