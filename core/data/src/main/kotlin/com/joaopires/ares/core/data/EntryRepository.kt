// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.joaopires.ares.core.data.db.AresDatabase
import com.joaopires.ares.core.data.db.TODAY_HOURS
import com.joaopires.ares.core.data.db.buildEntryQuery
import com.joaopires.ares.core.data.model.EntryDetail
import com.joaopires.ares.core.data.model.EntryEnclosure
import com.joaopires.ares.core.data.model.EntryListItem
import com.joaopires.ares.core.data.model.EntryQuery
import com.joaopires.ares.core.data.model.EntryScope
import com.joaopires.ares.core.data.model.HomeCounts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.Duration
import java.time.Instant

private const val PAGE_SIZE = 30
private const val SQL_BATCH = 500
private const val COUNTS_REFRESH_MILLIS = 60_000L
private val TOMBSTONE_LIFETIME: Duration = Duration.ofDays(365)

public class EntryRepository(
    private val db: AresDatabase,
    private val clock: Clock,
    private val events: EntryEvents = EntryEvents(),
) {

    public fun entries(query: EntryQuery): Flow<PagingData<EntryListItem>> =
        Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)) {
            db.entryDao().pagingSource(buildEntryQuery(query, clock.instant()))
        }.flow

    @OptIn(ExperimentalCoroutinesApi::class)
    public fun counts(): Flow<HomeCounts> =
        flow {
            while (true) {
                emit(clock.instant())
                delay(COUNTS_REFRESH_MILLIS)
            }
        }.flatMapLatest { now -> db.entryDao().observeCounts(todayStart(now)) }

    public fun detail(entryId: String): Flow<EntryDetail?> = db.entryDao().observeDetail(entryId)

    public fun enclosures(entryId: String): Flow<List<EntryEnclosure>> = db.entryDao().observeEnclosures(entryId)

    public suspend fun setRead(entryId: String, read: Boolean) {
        db.entryDao().setRead(entryId, read)
        if (read) events.emit(EntryEvent.MarkedRead(listOf(entryId)))
    }

    public suspend fun existingIds(entryIds: Collection<String>): Set<String> =
        entryIds.chunked(SQL_BATCH).flatMapTo(HashSet()) { db.entryDao().existingIds(it) }

    public suspend fun setBookmarked(entryId: String, bookmarked: Boolean) {
        db.withTransaction {
            db.entryDao().setBookmarked(entryId, bookmarked)
            if (!bookmarked) db.entryDao().deleteIfOrphan(entryId)
        }
    }

    public suspend fun markAllRead(scope: EntryScope): List<String> = db.withTransaction {
        val dao = db.entryDao()
        val ids = when (scope) {
            EntryScope.All, EntryScope.Unread -> dao.unreadIds()
            EntryScope.Today -> dao.unreadIdsSince(todayStart(clock.instant()))
            EntryScope.Bookmarks -> dao.unreadBookmarkIds()
            is EntryScope.Feed -> dao.unreadIdsInFeed(scope.feedId)
            is EntryScope.Section -> dao.unreadIdsInSection(scope.sectionId)
        }
        ids.chunked(SQL_BATCH).forEach { dao.setReadMany(it, true) }
        ids
    }.also { ids -> if (ids.isNotEmpty()) events.emit(EntryEvent.MarkedRead(ids)) }

    public suspend fun markUnread(entryIds: List<String>) {
        db.withTransaction { entryIds.chunked(SQL_BATCH).forEach { db.entryDao().setReadMany(it, false) } }
    }

    public suspend fun applyRetention(retentionDays: Int?): Int = applyRetention(db, retentionDays, clock.instant())

    public suspend fun deleteCache(): Int = db.withTransaction {
        val deleted = db.entryDao().deleteAllUnbookmarked()
        db.feedDao().resetValidators()
        deleted
    }.also { events.emit(EntryEvent.Removed) }
}

internal fun todayStart(now: Instant): Instant = now.minusSeconds(TODAY_HOURS * 3600)

internal suspend fun applyRetention(db: AresDatabase, retentionDays: Int?, now: Instant): Int {
    if (retentionDays == null || retentionDays <= 0) return 0
    val cutoff = now.minus(Duration.ofDays(retentionDays.toLong()))
    return db.withTransaction {
        db.entryDao().tombstoneExpired(cutoff, now)
        val deleted = db.entryDao().deleteExpired(cutoff)
        db.entryDao().pruneTombstones(now.minus(TOMBSTONE_LIFETIME))
        deleted
    }
}
