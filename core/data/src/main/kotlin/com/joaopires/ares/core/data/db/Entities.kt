// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

public enum class FeedStatus { Active, Gone }

@Entity(
    tableName = "sections",
    indices = [Index(value = ["name"], unique = true)],
)
public data class SectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
)

@Entity(
    tableName = "feeds",
    foreignKeys = [
        ForeignKey(entity = SectionEntity::class, parentColumns = ["id"], childColumns = ["sectionId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["url"], unique = true), Index("sectionId")],
)
public data class FeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val entryKey: String,
    val title: String? = null,
    val customTitle: String? = null,
    val siteUrl: String? = null,
    val iconUrl: String? = null,
    val description: String? = null,
    val sectionId: Long? = null,
    val notify: Boolean = false,
    val etag: String? = null,
    val lastModified: String? = null,
    val lastFetchedAt: Instant? = null,
    val lastSuccessAt: Instant? = null,
    val lastError: String? = null,
    val consecutiveFailures: Int = 0,
    val status: FeedStatus = FeedStatus.Active,
    val createdAt: Instant,
)

@Entity(
    tableName = "entries",
    foreignKeys = [
        ForeignKey(entity = FeedEntity::class, parentColumns = ["id"], childColumns = ["feedId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["feedId", "sortDate"]), Index("sortDate"), Index("isRead"), Index("isBookmarked")],
)
public data class EntryEntity(
    @PrimaryKey val id: String,
    val feedId: Long?,
    val url: String?,
    val title: String?,
    val author: String?,
    val contentHtml: String?,
    val contentText: String?,
    val excerpt: String?,
    val publishedAt: Instant?,
    val firstSeenAt: Instant,
    val sortDate: Instant,
    val updatedAt: Instant?,
    val thumbnailUrl: String?,
    val thumbnailWidth: Int?,
    val thumbnailHeight: Int?,
    val videoId: String?,
    val isRead: Boolean = false,
    val isBookmarked: Boolean = false,
    val feedTitleSnapshot: String? = null,
    val feedIconSnapshot: String? = null,
)

@Fts4(contentEntity = EntryEntity::class)
@Entity(tableName = "entries_fts")
public data class EntryFts(
    val title: String?,
    val author: String?,
    val contentText: String?,
)

@Entity(
    tableName = "enclosures",
    foreignKeys = [
        ForeignKey(entity = EntryEntity::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("entryId")],
)
public data class EnclosureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: String,
    val url: String,
    val type: String?,
    val length: Long?,
)

@Entity(tableName = "filters")
public data class FilterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expression: String,
)

@Entity(tableName = "entry_tombstones", indices = [Index("deletedAt")])
public data class TombstoneEntity(
    @PrimaryKey val entryId: String,
    val feedId: Long?,
    val deletedAt: Instant,
)
