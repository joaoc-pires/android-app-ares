// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data

import com.joaopires.ares.core.data.db.AresDatabase
import com.joaopires.ares.core.data.model.EntryScope
import com.joaopires.ares.core.feed.FetchOutcome
import com.joaopires.ares.core.feed.Validators
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class NotificationDataTest {

    private lateinit var db: AresDatabase
    private val clock = MutableClock()
    private val events = EntryEvents()
    private lateinit var feeds: FeedRepository
    private lateinit var entries: EntryRepository

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        feeds = FeedRepository(db, clock, events)
        entries = EntryRepository(db, clock, events)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun subscribe(url: String, vararg ids: String): Long =
        (feeds.subscribe(feed(url, *ids.map { entry(it) }.toTypedArray()), Validators(null, null)) as SubscribeResult.Subscribed).feedId

    @Test
    fun newFeedsNotifyByDefaultAndCanBeSwitchedIndividuallyOrAllAtOnce() = runBlocking<Unit> {
        val a = subscribe("https://a.example/feed")
        val b = subscribe("https://b.example/feed")
        assertTrue(feeds.feedSettings(a).first()!!.notify)

        feeds.setNotify(a, false)
        assertFalse(feeds.feedSettings(a).first()!!.notify)
        assertTrue(feeds.feedSettings(b).first()!!.notify)

        feeds.setNotifyForAll(false)
        assertTrue(feeds.feeds.first().none { it.notify })
        feeds.setNotifyForAll(true)
        assertTrue(feeds.feeds.first().all { it.notify })
    }

    @Test
    fun readingAndRemovingEntriesIsAnnounced() = runBlocking<Unit> {
        val received = mutableListOf<EntryEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { events.events.collect { received += it } }
        val a = subscribe("https://a.example/feed", "a1", "a2")
        subscribe("https://b.example/feed", "b1")
        assertEquals(setOf("a1", "b1"), entries.existingIds(listOf("a1", "b1", "missing")))

        entries.setRead("a1", true)
        entries.setRead("a1", false)
        entries.markAllRead(EntryScope.Feed(a))
        entries.markAllRead(EntryScope.Feed(a))
        feeds.deleteFeed(a)
        entries.deleteCache()
        yield()

        assertEquals(
            listOf(setOf("a1"), setOf("a1", "a2"), EntryEvent.Removed, EntryEvent.Removed),
            received.map { if (it is EntryEvent.MarkedRead) it.entryIds.toSet() else it },
        )
        assertTrue(entries.existingIds(listOf("a1", "b1")).isEmpty())
        collector.cancel()
    }

    @Test
    fun refreshCanSkipRecentlyFetchedFeeds() = runBlocking<Unit> {
        subscribe("https://a.example/feed")
        val source = FakeFeedSource { _, _, _ -> FetchOutcome.NotModified(null) }
        val refresh = RefreshManager(db, source, testSettings(), clock)

        clock.now = clock.now.plus(Duration.ofMinutes(10))
        assertEquals(0, refresh.refreshAll(force = false, olderThan = Duration.ofMinutes(15)).refreshed)
        assertEquals(1, refresh.refreshAll(force = false).refreshed)
    }

    @Test
    fun backgroundRefreshDefaultsToHourlyAndCanBeTurnedOff() = runBlocking<Unit> {
        val settings = testSettings()
        assertEquals(60, settings.current().backgroundRefreshMinutes)
        settings.update { it.copy(backgroundRefreshMinutes = null) }
        assertEquals(null, settings.current().backgroundRefreshMinutes)
        settings.update { it.copy(backgroundRefreshMinutes = 180) }
        assertEquals(180, settings.current().backgroundRefreshMinutes)
    }
}
