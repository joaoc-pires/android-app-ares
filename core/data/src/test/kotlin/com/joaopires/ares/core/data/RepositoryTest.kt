// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data

import app.cash.turbine.test
import com.joaopires.ares.core.data.db.AresDatabase
import com.joaopires.ares.core.data.model.EntryQuery
import com.joaopires.ares.core.data.model.EntryScope
import com.joaopires.ares.core.data.model.HomeCounts
import com.joaopires.ares.core.feed.Feed
import com.joaopires.ares.core.feed.Validators
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class RepositoryTest {

    private lateinit var db: AresDatabase
    private val clock = MutableClock()
    private lateinit var feeds: FeedRepository
    private lateinit var entries: EntryRepository
    private lateinit var filters: FilterRepository

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        feeds = FeedRepository(db, clock)
        entries = EntryRepository(db, clock)
        filters = FilterRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun subscribe(feed: Feed, sectionId: Long? = null): Long =
        (feeds.subscribe(feed, Validators("\"e\"", null), sectionId = sectionId) as SubscribeResult.Subscribed).feedId

    private suspend fun ids(query: EntryQuery, now: java.time.Instant = NOW) = db.list(query, now).map { it.id }

    @Test
    fun subscribingTwiceReturnsTheExistingFeed() = runBlocking {
        val id = subscribe(feed("https://a.example/feed", entry("a")))
        assertEquals(SubscribeResult.AlreadySubscribed(id), feeds.subscribe(feed("https://a.example/feed"), Validators(null, null)))
    }

    @Test
    fun feedSummariesIncludeUnreadCountsAndDisplayTitles() = runBlocking {
        val id = subscribe(feed("https://a.example/feed", entry("a"), entry("b"), title = "Zed"))
        subscribe(feed("https://b.example/feed", entry("c"), title = "alpha"))
        entries.setRead("a", true)
        feeds.updateFeed(id, customTitle = "Beta", url = "https://a.example/feed", notify = true, sectionId = null)
        val summaries = feeds.feeds.first()
        assertEquals(listOf("alpha", "Beta"), summaries.map { it.title })
        assertEquals(1, summaries[1].unreadCount)
        assertTrue(summaries[1].notify)
    }

    @Test
    fun changingTheUrlToAnExistingFeedIsRefused() = runBlocking {
        val a = subscribe(feed("https://a.example/feed"))
        subscribe(feed("https://b.example/feed"))
        assertFalse(feeds.updateFeed(a, null, "https://b.example/feed", false, null))
        assertTrue(feeds.updateFeed(a, null, "https://c.example/feed", false, null))
        assertNull(db.feedDao().byId(a)!!.etag)
    }

    @Test
    fun deletingAFeedKeepsBookmarksWithItsTitle() = runBlocking {
        val url = "https://a.example/feed"
        val id = subscribe(feed(url, entry("keep"), entry("drop"), title = "Gone Feed"))
        entries.setBookmarked("keep", true)
        feeds.deleteFeed(id)

        val remaining = db.list(EntryQuery(EntryScope.Bookmarks)).single()
        assertEquals("keep", remaining.id)
        assertNull(remaining.feedId)
        assertEquals("Gone Feed", remaining.feedTitle)
        assertEquals("$url/icon.png", remaining.feedIconUrl)
        assertEquals(listOf("keep"), ids(EntryQuery(EntryScope.All)))
    }

    @Test
    fun unbookmarkingAnOrphanDeletesIt() = runBlocking {
        val id = subscribe(feed("https://a.example/feed", entry("keep")))
        entries.setBookmarked("keep", true)
        feeds.deleteFeed(id)
        entries.setBookmarked("keep", false)
        assertTrue(ids(EntryQuery(EntryScope.All)).isEmpty())
    }

    @Test
    fun resubscribingReattachesKeptBookmarks() = runBlocking {
        val url = "https://a.example/feed"
        val first = subscribe(feed(url, entry("keep")))
        entries.setBookmarked("keep", true)
        feeds.deleteFeed(first)
        val second = subscribe(feed(url, entry("keep")))
        val item = db.list(EntryQuery(EntryScope.Feed(second))).single()
        assertEquals(second, item.feedId)
        assertTrue(item.isBookmarked)
    }

    @Test
    fun sectionsAreUniqueAndDeletingOneKeepsItsFeeds() = runBlocking {
        val section = feeds.createSection("Tech")!!
        assertNull(feeds.createSection("  tech "))
        val a = subscribe(feed("https://a.example/feed"), sectionId = section)
        val b = subscribe(feed("https://b.example/feed"))
        feeds.setSectionFeeds(section, listOf(b))
        assertNull(db.feedDao().byId(a)!!.sectionId)
        assertEquals(section, db.feedDao().byId(b)!!.sectionId)

        val other = feeds.createSection("News")!!
        assertFalse(feeds.renameSection(other, "TECH"))
        assertTrue(feeds.renameSection(other, "World"))

        feeds.deleteSection(section)
        assertNull(db.feedDao().byId(b)!!.sectionId)
        assertEquals(listOf("World"), feeds.sections.first().map { it.name })
    }

    @Test
    fun listsAreScopedAndSortedNewestFirst() = runBlocking {
        val section = feeds.createSection("S")!!
        val a = subscribe(
            feed(
                "https://a.example/feed",
                entry("new", publishedAt = NOW.minusSeconds(60)),
                entry("old", publishedAt = NOW.minus(Duration.ofDays(3))),
                entry("undated", publishedAt = null),
            ),
            sectionId = section,
        )
        subscribe(feed("https://b.example/feed", entry("other", publishedAt = NOW.minus(Duration.ofHours(23)))))
        entries.setRead("new", true)
        entries.setBookmarked("old", true)

        assertEquals(listOf("undated", "new", "other", "old"), ids(EntryQuery(EntryScope.All)))
        assertEquals(listOf("undated", "other", "old"), ids(EntryQuery(EntryScope.Unread)))
        assertEquals(listOf("undated", "new", "other"), ids(EntryQuery(EntryScope.Today)))
        assertEquals(listOf("undated", "other"), ids(EntryQuery(EntryScope.Today, unreadOnly = true)))
        assertEquals(listOf("old"), ids(EntryQuery(EntryScope.Bookmarks)))
        assertEquals(listOf("undated", "new", "old"), ids(EntryQuery(EntryScope.Feed(a))))
        assertEquals(listOf("undated", "old"), ids(EntryQuery(EntryScope.Section(section), unreadOnly = true)))
        assertEquals(listOf("undated", "new"), ids(EntryQuery(EntryScope.Today), now = NOW.plus(Duration.ofHours(2))))
    }

    @Test
    fun searchUsesFullTextInEveryScope() = runBlocking {
        val a = subscribe(
            feed(
                "https://a.example/feed",
                entry("k", title = "Kotlin coroutines explained", text = "Structured concurrency"),
                entry("c", title = "Compose news", text = "Material design and kotlin"),
            ),
        )
        subscribe(feed("https://b.example/feed", entry("x", title = "Other kotlin post")))
        assertEquals(setOf("k", "c", "x"), ids(EntryQuery(EntryScope.All, search = "kotlin")).toSet())
        assertEquals(listOf("k"), ids(EntryQuery(EntryScope.All, search = "corout")))
        assertEquals(listOf("k"), ids(EntryQuery(EntryScope.All, search = "concurrency structured")))
        assertEquals(setOf("k", "c"), ids(EntryQuery(EntryScope.Feed(a), search = "KOTLIN")).toSet())
        assertTrue(ids(EntryQuery(EntryScope.All, search = "C++ \"quoted\" AND OR NEAR(")).isEmpty())
        assertEquals(3, ids(EntryQuery(EntryScope.All, search = "   ")).size)
    }

    @Test
    fun countsFollowChanges() = runBlocking {
        subscribe(feed("https://a.example/feed", entry("a"), entry("b", publishedAt = NOW.minus(Duration.ofDays(2)))))
        entries.counts().test {
            assertEquals(HomeCounts(unread = 2, today = 1, bookmarks = 0), awaitItem())
            entries.setBookmarked("b", true)
            assertEquals(HomeCounts(unread = 2, today = 1, bookmarks = 1), awaitItem())
            entries.setRead("a", true)
            assertEquals(HomeCounts(unread = 1, today = 1, bookmarks = 1), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun markAllReadHonoursTheScope() = runBlocking {
        val section = feeds.createSection("S")!!
        val a = subscribe(feed("https://a.example/feed", entry("a1"), entry("a2")), sectionId = section)
        subscribe(feed("https://b.example/feed", entry("b1", publishedAt = NOW.minus(Duration.ofDays(5)))))
        assertEquals(2, entries.markAllRead(EntryScope.Feed(a)))
        assertEquals(listOf("b1"), ids(EntryQuery(EntryScope.Unread)))
        assertEquals(0, entries.markAllRead(EntryScope.Today))
        assertEquals(1, entries.markAllRead(EntryScope.All))
        assertTrue(ids(EntryQuery(EntryScope.Unread)).isEmpty())
    }

    @Test
    fun filtersNeverDeleteBookmarksAndIgnoreCase() = runBlocking {
        subscribe(
            feed(
                "https://a.example/feed",
                entry("cafe", title = "Le CAF\u00C9 du coin"),
                entry("saved", title = "Caf\u00E9 bookmarked"),
                entry("other", title = "Unrelated"),
            ),
        )
        entries.setBookmarked("saved", true)
        val filter = filters.add()
        filters.update(filter, "caf\u00E9")
        filters.add("   ")
        assertEquals(1, filters.countMatching())
        assertEquals(1, filters.applyToExisting())
        assertEquals(setOf("saved", "other"), ids(EntryQuery(EntryScope.All)).toSet())
        filters.delete(filter)
        assertEquals(0, filters.countMatching())
    }

    @Test
    fun deleteCacheKeepsBookmarksAndResetsValidators() = runBlocking {
        val id = subscribe(feed("https://a.example/feed", entry("a"), entry("b")))
        entries.setBookmarked("b", true)
        assertEquals(1, entries.deleteCache())
        assertEquals(listOf("b"), ids(EntryQuery(EntryScope.All)))
        assertNull(db.feedDao().byId(id)!!.etag)
    }

    @Test
    fun retentionOnlyRemovesOldReadUnbookmarkedEntries() = runBlocking {
        val old = NOW.minus(Duration.ofDays(91))
        subscribe(
            feed(
                "https://a.example/feed",
                entry("oldRead", publishedAt = old),
                entry("oldUnread", publishedAt = old),
                entry("oldSaved", publishedAt = old),
                entry("recentRead", publishedAt = NOW.minus(Duration.ofDays(10))),
            ),
        )
        listOf("oldRead", "oldSaved", "recentRead").forEach { entries.setRead(it, true) }
        entries.setBookmarked("oldSaved", true)
        assertEquals(0, entries.applyRetention(null))
        assertEquals(1, entries.applyRetention(90))
        assertEquals(setOf("oldUnread", "oldSaved", "recentRead"), ids(EntryQuery(EntryScope.All)).toSet())
    }

    @Test
    fun detailIncludesFeedInformation() = runBlocking {
        subscribe(feed("https://a.example/feed", entry("a"), title = "Feed A"))
        val detail = entries.detail("a").first()!!
        assertEquals("Feed A", detail.feedTitle)
        assertEquals("<p>Body of a</p>", detail.contentHtml)
        assertTrue(entries.enclosures("a").first().isEmpty())
    }
}
