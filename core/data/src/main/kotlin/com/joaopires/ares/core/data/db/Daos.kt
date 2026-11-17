// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import com.joaopires.ares.core.data.model.EntryDetail
import com.joaopires.ares.core.data.model.EntryEnclosure
import com.joaopires.ares.core.data.model.EntryListItem
import com.joaopires.ares.core.data.model.FeedSettings
import com.joaopires.ares.core.data.model.FeedSummary
import com.joaopires.ares.core.data.model.Filter
import com.joaopires.ares.core.data.model.HomeCounts
import com.joaopires.ares.core.data.model.Section
import kotlinx.coroutines.flow.Flow
import java.time.Instant

internal data class ExistingEntry(val id: String, val firstSeenAt: Instant)

public data class ExportedFeed(val url: String, val title: String, val siteUrl: String?, val section: String?)

internal data class FilterScanRow(val rowId: Long, val id: String, val title: String?, val contentText: String?)

internal data class EntryContentUpdate(
    val id: String,
    val feedId: Long,
    val url: String?,
    val title: String?,
    val author: String?,
    val contentHtml: String?,
    val contentText: String?,
    val excerpt: String?,
    val publishedAt: Instant?,
    val sortDate: Instant,
    val updatedAt: Instant?,
    val thumbnailUrl: String?,
    val thumbnailWidth: Int?,
    val thumbnailHeight: Int?,
    val videoId: String?,
    val feedTitleSnapshot: String?,
    val feedIconSnapshot: String?,
)

@Dao
internal interface SectionDao {
    @Query("SELECT id, name FROM sections ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Section>>

    @Query("SELECT * FROM sections WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): SectionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(section: SectionEntity): Long

    @Query("UPDATE sections SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String): Int

    @Query("DELETE FROM sections WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
internal interface FeedDao {
    @Query(
        """
        SELECT f.id, f.url, COALESCE(f.customTitle, f.title, f.url) AS title, f.customTitle, f.siteUrl, f.iconUrl,
               f.sectionId, f.notify, f.status, f.lastError,
               (SELECT COUNT(*) FROM entries e WHERE e.feedId = f.id AND e.isRead = 0) AS unreadCount
        FROM feeds f
        ORDER BY COALESCE(f.customTitle, f.title, f.url) COLLATE NOCASE
        """,
    )
    fun observeSummaries(): Flow<List<FeedSummary>>

    @Query("SELECT * FROM feeds ORDER BY id")
    suspend fun all(): List<FeedEntity>

    @Query("SELECT * FROM feeds WHERE id = :id")
    suspend fun byId(id: Long): FeedEntity?

    @Query(
        """
        SELECT id, url, title, customTitle, siteUrl, iconUrl, sectionId, status, lastSuccessAt, lastError
        FROM feeds WHERE id = :id
        """,
    )
    fun observeSettings(id: Long): Flow<FeedSettings?>

    @Query("SELECT id FROM feeds WHERE sectionId = :sectionId")
    suspend fun idsInSection(sectionId: Long): List<Long>

    @Query(
        """
        SELECT f.url, COALESCE(f.customTitle, f.title, f.url) AS title, f.siteUrl, s.name AS section
        FROM feeds f LEFT JOIN sections s ON s.id = f.sectionId
        ORDER BY COALESCE(f.customTitle, f.title, f.url) COLLATE NOCASE
        """,
    )
    suspend fun exportList(): List<ExportedFeed>

    @Query("SELECT * FROM feeds WHERE url = :url LIMIT 1")
    suspend fun byUrl(url: String): FeedEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(feed: FeedEntity): Long

    @Update
    suspend fun update(feed: FeedEntity)

    @Query("DELETE FROM feeds WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE feeds SET sectionId = :sectionId WHERE id IN (:feedIds)")
    suspend fun assignSection(feedIds: List<Long>, sectionId: Long?)

    @Query("UPDATE feeds SET sectionId = NULL WHERE sectionId = :sectionId AND id NOT IN (:keep)")
    suspend fun removeFromSectionExcept(sectionId: Long, keep: List<Long>)

    @Query("UPDATE feeds SET etag = NULL, lastModified = NULL")
    suspend fun resetValidators()
}

@Dao
internal interface EntryDao {
    @RawQuery(observedEntities = [EntryEntity::class, FeedEntity::class])
    fun pagingSource(query: SupportSQLiteQuery): PagingSource<Int, EntryListItem>

    @Query(
        """
        SELECT (SELECT COUNT(*) FROM entries WHERE isRead = 0) AS unread,
               (SELECT COUNT(*) FROM entries WHERE sortDate >= :since) AS today,
               (SELECT COUNT(*) FROM entries WHERE isBookmarked = 1) AS bookmarks
        """,
    )
    fun observeCounts(since: Instant): Flow<HomeCounts>

    @Query(
        """
        SELECT e.id, e.feedId, e.url, e.title, e.author, e.contentHtml, e.fullContentHtml, e.publishedAt, e.sortDate, e.thumbnailUrl, e.videoId,
               e.isRead, e.isBookmarked,
               COALESCE(f.customTitle, f.title, e.feedTitleSnapshot) AS feedTitle,
               COALESCE(f.iconUrl, e.feedIconSnapshot) AS feedIconUrl,
               f.siteUrl AS feedSiteUrl
        FROM entries e LEFT JOIN feeds f ON f.id = e.feedId
        WHERE e.id = :id
        """,
    )
    fun observeDetail(id: String): Flow<EntryDetail?>

    @Query("SELECT url FROM entries WHERE id = :id")
    suspend fun urlOf(id: String): String?

    @Query("UPDATE entries SET fullContentHtml = :html WHERE id = :id")
    suspend fun setFullContent(id: String, html: String)

    @Query("SELECT id FROM entries WHERE url IN (:urls) LIMIT 1")
    suspend fun idForUrl(urls: List<String>): String?

    @Query("SELECT url, type, length FROM enclosures WHERE entryId = :entryId ORDER BY id")
    fun observeEnclosures(entryId: String): Flow<List<EntryEnclosure>>

    @Query("SELECT id, firstSeenAt FROM entries WHERE id IN (:ids)")
    suspend fun existing(ids: List<String>): List<ExistingEntry>

    @Query("SELECT entryId FROM entry_tombstones WHERE entryId IN (:ids)")
    suspend fun tombstoned(ids: List<String>): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entries: List<EntryEntity>)

    @Update(entity = EntryEntity::class)
    suspend fun updateContent(updates: List<EntryContentUpdate>)

    @Insert
    suspend fun insertEnclosures(enclosures: List<EnclosureEntity>)

    @Query("DELETE FROM enclosures WHERE entryId IN (:entryIds)")
    suspend fun deleteEnclosures(entryIds: List<String>)

    @Query("UPDATE entries SET isRead = :read WHERE id = :id")
    suspend fun setRead(id: String, read: Boolean)

    @Query("UPDATE entries SET isBookmarked = :bookmarked WHERE id = :id")
    suspend fun setBookmarked(id: String, bookmarked: Boolean)

    @Query("DELETE FROM entries WHERE id = :id AND feedId IS NULL AND isBookmarked = 0")
    suspend fun deleteIfOrphan(id: String)

    @Query("SELECT id FROM entries WHERE isRead = 0")
    suspend fun unreadIds(): List<String>

    @Query("SELECT id FROM entries WHERE isRead = 0 AND feedId = :feedId")
    suspend fun unreadIdsInFeed(feedId: Long): List<String>

    @Query("SELECT id FROM entries WHERE isRead = 0 AND feedId IN (SELECT id FROM feeds WHERE sectionId = :sectionId)")
    suspend fun unreadIdsInSection(sectionId: Long): List<String>

    @Query("SELECT id FROM entries WHERE isRead = 0 AND sortDate >= :since")
    suspend fun unreadIdsSince(since: Instant): List<String>

    @Query("SELECT id FROM entries WHERE isRead = 0 AND isBookmarked = 1")
    suspend fun unreadBookmarkIds(): List<String>

    @Query("UPDATE entries SET isRead = :read WHERE id IN (:ids)")
    suspend fun setReadMany(ids: List<String>, read: Boolean)

    @Query("UPDATE entries SET feedTitleSnapshot = :title, feedIconSnapshot = :iconUrl WHERE feedId = :feedId AND isBookmarked = 1")
    suspend fun snapshotBookmarks(feedId: Long, title: String?, iconUrl: String?)

    @Query("DELETE FROM entries WHERE feedId = :feedId AND isBookmarked = 0")
    suspend fun deleteUnbookmarked(feedId: Long)

    @Query("DELETE FROM entries WHERE isBookmarked = 0")
    suspend fun deleteAllUnbookmarked(): Int

    @Query(
        """
        INSERT OR REPLACE INTO entry_tombstones (entryId, feedId, deletedAt)
        SELECT id, feedId, :now FROM entries WHERE isRead = 1 AND isBookmarked = 0 AND sortDate < :cutoff
        """,
    )
    suspend fun tombstoneExpired(cutoff: Instant, now: Instant)

    @Query("DELETE FROM entries WHERE isRead = 1 AND isBookmarked = 0 AND sortDate < :cutoff")
    suspend fun deleteExpired(cutoff: Instant): Int

    @Query("DELETE FROM entry_tombstones WHERE deletedAt < :cutoff")
    suspend fun pruneTombstones(cutoff: Instant)

    @Query("SELECT rowid AS rowId, id, title, contentText FROM entries WHERE isBookmarked = 0 AND rowid > :after ORDER BY rowid LIMIT :limit")
    suspend fun scanForFilters(after: Long, limit: Int): List<FilterScanRow>

    @Query("DELETE FROM entries WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

@Dao
internal interface FilterDao {
    @Query("SELECT id, expression FROM filters ORDER BY id")
    fun observeAll(): Flow<List<Filter>>

    @Query("SELECT expression FROM filters")
    suspend fun expressions(): List<String>

    @Insert
    suspend fun insert(filter: FilterEntity): Long

    @Query("UPDATE filters SET expression = :expression WHERE id = :id")
    suspend fun update(id: Long, expression: String)

    @Query("DELETE FROM filters WHERE id = :id")
    suspend fun delete(id: Long)
}
