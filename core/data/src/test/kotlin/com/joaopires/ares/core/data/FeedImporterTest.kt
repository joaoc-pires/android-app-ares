// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data

import com.joaopires.ares.core.data.db.AresDatabase
import com.joaopires.ares.core.data.db.ExportedFeed
import com.joaopires.ares.core.data.db.FeedStatus
import com.joaopires.ares.core.feed.FetchFailure
import com.joaopires.ares.core.feed.FetchOutcome
import com.joaopires.ares.core.feed.Validators
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class FeedImporterTest {

    private lateinit var db: AresDatabase
    private val clock = MutableClock()
    private lateinit var feeds: FeedRepository

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        feeds = FeedRepository(db, clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun source(failing: Map<String, FetchFailure> = emptyMap()) = FakeFeedSource { url, _, _ ->
        failing[url]?.let { FetchOutcome.Failed(it) }
            ?: FetchOutcome.Updated(feed(url, entry("$url#1"), title = "Feed title for $url"), Validators(null, null), null)
    }

    @Test
    fun importsFeedsIntoSectionsAndReportsFailures() = runBlocking<Unit> {
        val existing = feeds.createSection("tech")!!
        val importer = FeedImporter(feeds, source(mapOf("https://bad.example/rss" to FetchFailure.InsecureOnly)))
        val summary = importer.import(
            listOf(
                ImportCandidate("https://a.example/feed", "My A", "Tech"),
                ImportCandidate("https://b.example/feed", null, "News"),
                ImportCandidate("https://c.example/feed", "Feed title for https://c.example/feed", null),
                ImportCandidate("https://bad.example/rss", "Bad", "News"),
                ImportCandidate(" https://a.example/feed ", "Duplicate", "Tech"),
            ),
        )
        assertEquals(3, summary.imported)
        assertEquals(listOf(ImportFailure("https://bad.example/rss", "Bad", FetchFailure.InsecureOnly)), summary.failures)

        val sections = feeds.sections.first().associate { it.name to it.id }
        assertEquals(setOf("tech", "News"), sections.keys)
        assertEquals(existing, sections["tech"])

        val byUrl = feeds.feeds.first().associateBy { it.url }
        assertEquals("My A", byUrl.getValue("https://a.example/feed").title)
        assertEquals("My A", byUrl.getValue("https://a.example/feed").customTitle)
        assertEquals(existing, byUrl.getValue("https://a.example/feed").sectionId)
        assertEquals(sections["News"], byUrl.getValue("https://b.example/feed").sectionId)
        assertEquals(null, byUrl.getValue("https://c.example/feed").customTitle)
        assertEquals(ImportProgress.Idle, importer.progress.value)
    }

    @Test
    fun alreadyFollowedFeedsAreCountedSeparately() = runBlocking<Unit> {
        feeds.subscribe(feed("https://a.example/feed"), Validators(null, null))
        val summary = FeedImporter(feeds, source()).import(listOf(ImportCandidate("https://a.example/feed", null, null)))
        assertEquals(ImportSummary(imported = 0, alreadySubscribed = 1, failures = emptyList()), summary)
    }

    @Test
    fun unexpectedErrorsBecomeFailures() = runBlocking<Unit> {
        val importer = FeedImporter(feeds, FakeFeedSource { _, _, _ -> throw IllegalStateException("boom") })
        val summary = importer.import(listOf(ImportCandidate("https://a.example/feed", "A", null)))
        assertEquals(listOf(ImportFailure("https://a.example/feed", "A", FetchFailure.Network)), summary.failures)
    }

    @Test
    fun concurrencyIsLimited() = runBlocking<Unit> {
        val sameHost = AtomicInteger()
        val maxSameHost = AtomicInteger()
        val source = FakeFeedSource { url, _, _ ->
            val same = url.contains("same.example")
            if (same) maxSameHost.accumulateAndGet(sameHost.incrementAndGet()) { a, b -> maxOf(a, b) }
            delay(30)
            if (same) sameHost.decrementAndGet()
            FetchOutcome.Updated(feed(url), Validators(null, null), null)
        }
        val candidates = (1..12).map { ImportCandidate("https://h$it.example/feed", null, null) } +
            (1..5).map { ImportCandidate("https://same.example/f$it", null, null) }
        assertEquals(17, FeedImporter(feeds, source).import(candidates).imported)
        assertTrue(source.maxActive.get() <= 6)
        assertTrue(maxSameHost.get() <= 2)
    }

    @Test
    fun exportListIncludesSectionsAndDisplayTitles() = runBlocking<Unit> {
        val section = feeds.createSection("Tech")!!
        feeds.subscribe(feed("https://a.example/feed", title = "Alpha"), Validators(null, null), customTitle = "A & B", sectionId = section)
        feeds.subscribe(feed("https://b.example/feed", title = "Beta"), Validators(null, null))
        assertEquals(
            listOf(
                ExportedFeed("https://a.example/feed", "A & B", "https://a.example/feed", "Tech"),
                ExportedFeed("https://b.example/feed", "Beta", "https://b.example/feed", null),
            ),
            feeds.exportList(),
        )
    }
}

@RunWith(RobolectricTestRunner::class)
class FeedSettingsRepositoryTest {

    @Test
    fun settingsSectionMembershipAndReactivation() = runBlocking<Unit> {
        val db = inMemoryDatabase()
        val feeds = FeedRepository(db, MutableClock())
        val section = feeds.createSection("Tech")!!
        val a = (feeds.subscribe(feed("https://a.example/feed", title = "Alpha"), Validators(null, null), sectionId = section) as SubscribeResult.Subscribed).feedId
        val b = (feeds.subscribe(feed("https://b.example/feed"), Validators(null, null)) as SubscribeResult.Subscribed).feedId

        val settings = feeds.feedSettings(a).first()!!
        assertEquals("Alpha", settings.title)
        assertEquals(section, settings.sectionId)
        assertEquals(listOf(a), feeds.sectionFeedIds(section))

        RefreshManager(db, FakeFeedSource { _, _, _ -> FetchOutcome.Gone }, testSettings(), MutableClock()).refreshFeed(b)
        assertEquals(FeedStatus.Gone, feeds.feedSettings(b).first()!!.status)
        feeds.reactivate(b)
        val reactivated = feeds.feedSettings(b).first()!!
        assertEquals(FeedStatus.Active, reactivated.status)
        assertEquals(null, reactivated.lastError)
        db.close()
    }
}
