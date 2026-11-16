// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.joaopires.ares.core.data.db.AresDatabase
import com.joaopires.ares.core.data.db.buildEntryQuery
import com.joaopires.ares.core.data.model.EntryListItem
import com.joaopires.ares.core.data.model.EntryQuery
import com.joaopires.ares.core.feed.DiscoveryOutcome
import com.joaopires.ares.core.feed.Entry
import com.joaopires.ares.core.feed.Feed
import com.joaopires.ares.core.feed.FetchOutcome
import com.joaopires.ares.core.feed.Validators
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

internal val NOW: Instant = Instant.parse("2026-11-16T12:00:00Z")

internal class MutableClock(var now: Instant = NOW) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
}

internal fun inMemoryDatabase(): AresDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AresDatabase::class.java)
        .allowMainThreadQueries()
        .build()

internal fun testSettings(): SettingsRepository {
    val dir: File = Files.createTempDirectory("ares-settings").toFile()
    return SettingsRepository.create(File(dir, "settings.preferences_pb"), CoroutineScope(Dispatchers.IO + SupervisorJob()))
}

internal fun entry(
    id: String,
    title: String? = "Title $id",
    publishedAt: Instant? = NOW,
    text: String? = "Body of $id",
) = Entry(
    id = id,
    url = "https://site.example/$id",
    title = title,
    author = null,
    contentHtml = text?.let { "<p>$it</p>" },
    contentText = text,
    excerpt = text,
    publishedAt = publishedAt,
    updatedAt = null,
    thumbnail = null,
    videoId = null,
    enclosures = emptyList(),
)

internal fun feed(url: String, vararg entries: Entry, title: String = "Feed $url") =
    Feed(url = url, title = title, siteUrl = url, description = null, iconUrl = "$url/icon.png", entries = entries.toList())

internal suspend fun AresDatabase.list(query: EntryQuery, now: Instant = NOW): List<EntryListItem> {
    val result = entryDao().pagingSource(buildEntryQuery(query, now))
        .load(PagingSource.LoadParams.Refresh(key = null, loadSize = 1_000, placeholdersEnabled = false))
    return (result as PagingSource.LoadResult.Page).data
}

internal class FakeFeedSource(private val respond: suspend (url: String, validators: Validators?, feedKey: String) -> FetchOutcome) : FeedSource {
    val calls = mutableListOf<Triple<String, Validators?, String>>()
    private val active = AtomicInteger()
    val maxActive = AtomicInteger()

    override suspend fun fetch(url: String, validators: Validators?, feedKey: String): FetchOutcome {
        synchronized(calls) { calls += Triple(url, validators, feedKey) }
        val now = active.incrementAndGet()
        maxActive.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        try {
            return respond(url, validators, feedKey)
        } finally {
            active.decrementAndGet()
        }
    }

    override suspend fun discover(url: String): DiscoveryOutcome = DiscoveryOutcome.NothingFound
}
