// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data

import com.joaopires.ares.core.data.db.AresDatabase
import com.joaopires.ares.core.data.db.FeedStatus
import com.joaopires.ares.core.data.model.EntryQuery
import com.joaopires.ares.core.data.model.EntryScope
import com.joaopires.ares.core.feed.Feed
import com.joaopires.ares.core.feed.FetchFailure
import com.joaopires.ares.core.feed.FetchOutcome
import com.joaopires.ares.core.feed.Validators
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
class RefreshManagerTest {

    private lateinit var db: AresDatabase
    private val clock = MutableClock()
    private val settings = testSettings()
    private lateinit var feeds: FeedRepository
    private lateinit var entries: EntryRepository

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        feeds = FeedRepository(db, clock)
        entries = EntryRepository(db, clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun manager(source: FeedSource) = RefreshManager(db, source, settings, clock)

    private suspend fun subscribe(feed: Feed, validators: Validators = Validators(null, null)): Long =
        (feeds.subscribe(feed, validators) as SubscribeResult.Subscribed).feedId

    @Test
    fun refreshKeepsReadAndBookmarkStateAndReportsNewEntries() = runBlocking {
        val url = "https://a.example/feed"
        subscribe(feed(url, entry("a"), entry("b")))
        entries.setRead("a", true)
        entries.setBookmarked("b", true)

        val source = FakeFeedSource { _, _, _ ->
            FetchOutcome.Updated(feed(url, entry("a", title = "Renamed"), entry("b"), entry("c")), Validators("\"v2\"", null), null)
        }
        val summary = manager(source).refreshAll()

        assertEquals(listOf("c"), summary.newEntries.map { it.id })
        val all = db.list(EntryQuery(EntryScope.All)).associateBy { it.id }
        assertTrue(all.getValue("a").isRead)
        assertEquals("Renamed", all.getValue("a").title)
        assertTrue(all.getValue("b").isBookmarked)
        assertFalse(all.getValue("c").isRead)
        assertEquals("\"v2\"", db.feedDao().byUrl(url)!!.etag)
    }

    @Test
    fun sendsSavedValidatorsAndEntryKey() = runBlocking {
        val url = "https://a.example/feed"
        subscribe(feed(url, entry("a")), Validators("\"v1\"", "Mon, 16 Nov 2026 10:00:00 GMT"))
        val source = FakeFeedSource { _, _, _ -> FetchOutcome.NotModified(null) }
        manager(source).refreshAll()
        assertEquals(Triple(url, Validators("\"v1\"", "Mon, 16 Nov 2026 10:00:00 GMT"), url), source.calls.single())
    }

    @Test
    fun notModifiedOnlyUpdatesFetchTime() = runBlocking {
        val url = "https://a.example/feed"
        val id = subscribe(feed(url, entry("a")))
        clock.now = NOW.plusSeconds(3600)
        manager(FakeFeedSource { _, _, _ -> FetchOutcome.NotModified(null) }).refreshAll()
        val stored = db.feedDao().byId(id)!!
        assertEquals(clock.now, stored.lastFetchedAt)
        assertEquals(1, db.list(EntryQuery(EntryScope.All)).size)
    }

    @Test
    fun movedFeedsKeepTheirEntryKey() = runBlocking {
        val oldUrl = "https://old.example/feed"
        val newUrl = "https://new.example/feed"
        val id = subscribe(feed(oldUrl, entry("a")))
        val source = FakeFeedSource { _, _, _ -> FetchOutcome.Updated(feed(newUrl, entry("a")), Validators(null, null), newUrl) }
        manager(source).refreshAll()
        val stored = db.feedDao().byId(id)!!
        assertEquals(newUrl, stored.url)
        assertEquals(oldUrl, stored.entryKey)
        manager(source).refreshAll()
        assertEquals(oldUrl, source.calls.last().third)
    }

    @Test
    fun goneFeedsAreMarkedAndSkipped() = runBlocking {
        val id = subscribe(feed("https://a.example/feed", entry("a")))
        val source = FakeFeedSource { _, _, _ -> FetchOutcome.Gone }
        manager(source).refreshAll()
        assertEquals(FeedStatus.Gone, db.feedDao().byId(id)!!.status)
        manager(source).refreshAll()
        assertEquals(1, source.calls.size)
    }

    @Test
    fun failuresAreRecorded() = runBlocking {
        val id = subscribe(feed("https://a.example/feed", entry("a")))
        val source = FakeFeedSource { _, _, _ -> FetchOutcome.Failed(FetchFailure.Http(500)) }
        val summary = manager(source).refreshAll()
        manager(source).refreshAll()
        val stored = db.feedDao().byId(id)!!
        assertEquals(1, summary.failed)
        assertEquals("Http:500", stored.lastError)
        assertEquals(2, stored.consecutiveFailures)

        manager(FakeFeedSource { _, _, _ -> FetchOutcome.NotModified(null) }).refreshAll()
        assertNull(db.feedDao().byId(id)!!.lastError)
        assertEquals(0, db.feedDao().byId(id)!!.consecutiveFailures)
    }

    @Test
    fun filteredEntriesAreNotSaved() = runBlocking {
        val url = "https://a.example/feed"
        subscribe(feed(url))
        FilterRepository(db).add("  sponsored ")
        val source = FakeFeedSource { _, _, _ ->
            FetchOutcome.Updated(feed(url, entry("ad", title = "A SPONSORED post"), entry("ok"), entry("ad2", text = "this is Sponsored content")), Validators(null, null), null)
        }
        assertEquals(listOf("ok"), manager(source).refreshAll().newEntries.map { it.id })
    }

    @Test
    fun concurrencyIsLimitedGloballyAndPerHost() = runBlocking {
        repeat(12) { subscribe(feed("https://host$it.example/feed")) }
        repeat(5) { subscribe(feed("https://same.example/feed$it")) }
        val sameHost = java.util.concurrent.atomic.AtomicInteger()
        val maxSameHost = java.util.concurrent.atomic.AtomicInteger()
        val source = FakeFeedSource { url, _, _ ->
            val isSame = url.contains("same.example")
            if (isSame) maxSameHost.accumulateAndGet(sameHost.incrementAndGet()) { a, b -> maxOf(a, b) }
            delay(50)
            if (isSame) sameHost.decrementAndGet()
            FetchOutcome.NotModified(null)
        }
        val summary = manager(source).refreshAll()
        assertEquals(17, summary.refreshed)
        assertTrue("max concurrent was ${source.maxActive.get()}", source.maxActive.get() <= 6)
        assertTrue("max per host was ${maxSameHost.get()}", maxSameHost.get() <= 2)
    }

    @Test
    fun automaticRefreshSkipsRecentlyFetchedFeeds() = runBlocking {
        subscribe(feed("https://a.example/feed"))
        val source = FakeFeedSource { _, _, _ -> FetchOutcome.NotModified(null) }
        clock.now = NOW.plus(Duration.ofMinutes(2))
        manager(source).refreshAll(force = false)
        assertEquals(0, source.calls.size)
        clock.now = NOW.plus(Duration.ofMinutes(6))
        manager(source).refreshAll(force = false)
        assertEquals(1, source.calls.size)
    }

    @Test
    fun retentionRunsAndTombstonesStopEntriesComingBack() = runBlocking {
        val url = "https://a.example/feed"
        val old = entry("old", publishedAt = NOW.minus(Duration.ofDays(100)))
        subscribe(feed(url, old, entry("fresh")))
        entries.setRead("old", true)
        val source = FakeFeedSource { _, _, _ -> FetchOutcome.Updated(feed(url, old, entry("fresh")), Validators(null, null), null) }

        manager(source).refreshAll()
        assertEquals(listOf("fresh"), db.list(EntryQuery(EntryScope.All)).map { it.id })

        val second = manager(source).refreshAll()
        assertTrue(second.newEntries.isEmpty())
        assertEquals(listOf("fresh"), db.list(EntryQuery(EntryScope.All)).map { it.id })
    }

    @Test
    fun cancellationResetsProgress() = runBlocking {
        subscribe(feed("https://a.example/feed"))
        val started = CompletableDeferred<Unit>()
        val manager = manager(FakeFeedSource { _, _, _ ->
            started.complete(Unit)
            awaitCancellation()
        })
        val job = async { manager.refreshAll() }
        started.await()
        assertEquals(RefreshProgress.Running(0, 1), manager.progress.value)
        job.cancel()
        withTimeout(2_000) { runCatching { job.await() } }
        assertEquals(RefreshProgress.Idle, manager.progress.value)
    }
}
