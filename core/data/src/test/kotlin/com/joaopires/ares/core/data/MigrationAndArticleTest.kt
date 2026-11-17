// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.joaopires.ares.core.data.db.AresDatabase
import com.joaopires.ares.core.feed.ArticleOutcome
import com.joaopires.ares.core.feed.ExtractedArticle
import com.joaopires.ares.core.feed.FetchFailure
import com.joaopires.ares.core.feed.FetchOutcome
import com.joaopires.ares.core.feed.Validators
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AresDatabase::class.java)

    @Test
    fun version1DataSurvivesTheMigrationTo2() {
        helper.createDatabase("migration-test", 1).apply {
            execSQL("INSERT INTO feeds (id, url, entryKey, notify, consecutiveFailures, status, createdAt) VALUES (1, 'https://a.example/feed', 'https://a.example/feed', 0, 0, 'Active', 0)")
            execSQL(
                "INSERT INTO entries (id, feedId, url, title, author, contentHtml, contentText, excerpt, publishedAt, firstSeenAt, sortDate, updatedAt, " +
                    "thumbnailUrl, thumbnailWidth, thumbnailHeight, videoId, isRead, isBookmarked, feedTitleSnapshot, feedIconSnapshot) " +
                    "VALUES ('e1', 1, 'https://a.example/1', 'Kept', NULL, '<p>x</p>', 'x', 'x', 1000, 1000, 1000, NULL, NULL, NULL, NULL, NULL, 1, 1, NULL, NULL)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate("migration-test", 2, true)
        db.query("SELECT title, isRead, isBookmarked, fullContentHtml FROM entries WHERE id = 'e1'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("Kept", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals(1, cursor.getInt(2))
            assertNull(cursor.getString(3))
        }
        db.close()
    }
}

@RunWith(RobolectricTestRunner::class)
class ArticleRepositoryTest {

    private val clock = MutableClock()

    @Test
    fun fullArticlesAreCachedAndReused() = runBlocking<Unit> {
        val db = inMemoryDatabase()
        val feeds = FeedRepository(db, clock)
        val entries = EntryRepository(db, clock)
        val source = FakeFeedSource { _, _, _ -> FetchOutcome.NotModified(null) }
        source.onFetchArticle = { ArticleOutcome.Extracted(ExtractedArticle("<p>Full text</p>", "T", null)) }
        feeds.subscribe(feed("https://a.example/feed", entry("a")), Validators(null, null))
        val articles = ArticleRepository(db, source)

        assertNull(entries.detail("a").first()!!.fullContentHtml)
        assertEquals(FullArticleResult.Loaded, articles.loadFullArticle("a"))
        assertEquals("<p>Full text</p>", entries.detail("a").first()!!.fullContentHtml)
        db.close()
    }

    @Test
    fun failuresAndMissingLinksAreReported() = runBlocking<Unit> {
        val db = inMemoryDatabase()
        val source = FakeFeedSource { _, _, _ -> FetchOutcome.NotModified(null) }
        FeedRepository(db, clock).subscribe(feed("https://a.example/feed", entry("a")), Validators(null, null))
        val articles = ArticleRepository(db, source)

        assertEquals(FullArticleResult.NotExtractable, articles.loadFullArticle("a"))
        source.onFetchArticle = { ArticleOutcome.Failed(FetchFailure.Timeout) }
        assertEquals(FullArticleResult.Failed(FetchFailure.Timeout), articles.loadFullArticle("a"))
        source.onFetchArticle = { throw IllegalStateException("boom") }
        assertEquals(FullArticleResult.Failed(FetchFailure.Network), articles.loadFullArticle("a"))
        assertEquals(FullArticleResult.NoLink, articles.loadFullArticle("missing"))
        db.close()
    }

    @Test
    fun linksAreMatchedToStoredEntries() = runBlocking<Unit> {
        val db = inMemoryDatabase()
        FeedRepository(db, clock).subscribe(feed("https://a.example/feed", entry("post")), Validators(null, null))
        val articles = ArticleRepository(db, FakeFeedSource { _, _, _ -> FetchOutcome.NotModified(null) })
        assertEquals("post", articles.entryIdForUrl("https://site.example/post"))
        assertEquals("post", articles.entryIdForUrl("http://site.example/post"))
        assertEquals("post", articles.entryIdForUrl("https://site.example/post/"))
        assertEquals("post", articles.entryIdForUrl("https://site.example/post#comments"))
        assertNull(articles.entryIdForUrl("https://other.example/post"))
        db.close()
    }
}
