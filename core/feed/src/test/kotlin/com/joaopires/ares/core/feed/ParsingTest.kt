// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.feed

import com.joaopires.ares.core.feed.normalize.normalize
import com.joaopires.ares.core.feed.parse.FeedParseException
import com.joaopires.ares.core.feed.parse.parseRawFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.security.MessageDigest
import java.time.Instant

internal val NOW: Instant = Instant.parse("2026-11-16T12:00:00Z")

internal fun fixtureBytes(name: String): ByteArray =
    requireNotNull(ParsingTest::class.java.classLoader.getResourceAsStream(name)) { "missing fixture $name" }.use { it.readBytes() }

internal fun parseFixture(name: String, url: String, charset: String? = null): Feed =
    normalize(parseRawFeed(fixtureBytes(name), charset, { KXmlParser() }), url, url, url, NOW)

internal fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

class ParsingTest {

    private fun failureOf(name: String): FetchFailure = try {
        parseFixture(name, "https://x.example/feed")
        fail("expected a failure for $name")
        throw AssertionError()
    } catch (e: FeedParseException) {
        e.failure
    }

    @Test
    fun wordpressFeedIsParsedAndCleaned() {
        val url = "https://blog.example/feed/"
        val feed = parseFixture("wordpress.xml", url)
        assertEquals("Example & Blog", feed.title)
        assertEquals("https://blog.example", feed.siteUrl)
        assertEquals("Just another blog", feed.description)

        val first = feed.entries[0]
        assertEquals(sha256("$url\nhttps://blog.example/?p=42"), first.id)
        assertEquals("https://blog.example/2026/11/hello/", first.url)
        assertEquals("Hello World & friends", first.title)
        assertEquals("Ana Silva", first.author)
        assertEquals(Instant.parse("2026-11-16T09:30:00Z"), first.publishedAt)

        val html = first.contentHtml!!
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("onclick"))
        assertFalse(html.contains("style="))
        assertFalse(html.contains("pixel.wp.com"))
        assertFalse(html.contains("track.gif"))
        assertFalse(html.contains("feedflare"))
        assertFalse(html.contains("<iframe"))
        assertFalse(html.contains("class=\"btn\""))
        assertTrue(html.contains("href=\"https://blog.example/about\""))
        assertTrue(html.contains("<a href=\"https://www.youtube.com/embed/dQw4w9WgXcQ\" class=\"ares-embed\">Embedded content: www.youtube.com</a>"))
        assertTrue(html.contains("src=\"https://images.example/inline.png\""))
        assertTrue(html.contains("srcset=\"https://blog.example/small.png 1x, https://blog.example/large.png 2x\""))
        assertTrue(html.contains("s.w.org/images/core/emoji"))

        assertEquals(
            Thumbnail("https://blog.example/wp-content/uploads/2026/11/hero-1200x630.jpg", 1200, 630),
            first.thumbnail,
        )
        assertTrue(first.excerpt!!.startsWith("Intro text. Body with relative link."))
        assertTrue(first.contentText!!.startsWith("Intro text. Body with relative link."))
        assertTrue(first.excerpt!!.length <= first.contentText!!.length)
        assertNull(first.videoId)

        val second = feed.entries[1]
        assertEquals("Ana Silva, Rui Costa", second.author)
        assertEquals("Plain &amp; simple", second.contentHtml)
    }

    @Test
    fun atomResolvesBaseUrlsAndPrefersAlternateLinks() {
        val feed = parseFixture("atom-base.xml", "https://atom.example/blog/feed.atom")
        assertEquals("Atom & Friends", feed.title)
        assertEquals("All the news", feed.description)
        assertEquals("https://atom.example/blog/", feed.siteUrl)
        assertEquals("https://atom.example/favicon.png", feed.iconUrl)

        val one = feed.entries[0]
        assertEquals("https://atom.example/blog/posts/one.html", one.url)
        assertEquals("Entry one", one.title)
        assertEquals("Feed Author", one.author)
        assertEquals(Instant.parse("2026-11-16T08:00:00Z"), one.publishedAt)
        assertEquals(Instant.parse("2026-11-16T08:30:00Z"), one.updatedAt)
        assertTrue(one.contentHtml!!.contains("href=\"https://atom.example/blog/posts/two.html\""))
        assertTrue(one.contentHtml!!.contains("&amp;"))
        assertEquals(Thumbnail("https://atom.example/blog/posts/img/pic.jpg", 800, 400), one.thumbnail)

        val two = feed.entries[1]
        assertEquals("https://atom.example/blog/posts/two.html", two.url)
        assertEquals("Guest Writer", two.author)
        assertEquals(Instant.parse("2026-11-15T12:00:00Z"), two.publishedAt)
        assertEquals("<p>Only a &lt;summary&gt; here</p>", two.contentHtml)
        assertEquals("Only a <summary> here", two.excerpt)

        val three = feed.entries[2]
        assertNull(three.contentHtml)
        assertNull(three.contentText)
        assertNull(three.excerpt)
    }

    @Test
    fun youtubeEntriesHaveVideoIdsAndThumbnails() {
        val feed = parseFixture("youtube.xml", "https://www.youtube.com/feeds/videos.xml?channel_id=UC123")
        assertEquals("Example Channel", feed.title)
        assertEquals("https://www.youtube.com/channel/UC123", feed.siteUrl)
        val video = feed.entries.single()
        assertEquals("abcdefghijk", video.videoId)
        assertEquals("A video & more", video.title)
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", video.url)
        assertEquals(Thumbnail("https://i1.ytimg.com/vi/abcdefghijk/hqdefault.jpg", 480, 360), video.thumbnail)
        assertEquals("<p>First line.</p><p>Second paragraph with a link https://example.com</p>", video.contentHtml)
    }

    @Test
    fun podcastEnclosuresAndItunesImages() {
        val feed = parseFixture("podcast.xml", "https://podcast.example/feed")
        assertEquals("https://podcast.example/cover.jpg", feed.iconUrl)
        val episode = feed.entries.single()
        assertEquals("Joana Host", episode.author)
        assertNull(episode.url)
        assertEquals(sha256("https://podcast.example/feed\nep-1"), episode.id)
        assertEquals(Instant.parse("2026-11-03T12:00:00Z"), episode.publishedAt)
        assertEquals("https://podcast.example/ep1.jpg", episode.thumbnail?.url)
        assertEquals(listOf(Enclosure("https://cdn.podcast.example/ep1.mp3", "audio/mpeg", 12345678)), episode.enclosures)
    }

    @Test
    fun substackStyleMediaContentIsTheThumbnail() {
        val feed = parseFixture("substack.xml", "https://news.substack.example/feed")
        assertEquals("Newsletter", feed.title)
        assertEquals("https://substack.example/icon.png", feed.iconUrl)
        assertEquals(Thumbnail("https://substack.example/media/cover.jpg", 1200, 600), feed.entries.single().thumbnail)
    }

    @Test
    fun rss1RdfFeed() {
        val feed = parseFixture("rss1.rdf", "https://rdf.example/index.rdf")
        assertEquals("RDF Site", feed.title)
        assertEquals("https://rdf.example/logo.png", feed.iconUrl)
        assertEquals(2, feed.entries.size)
        assertEquals("RDF Author", feed.entries[0].author)
        assertEquals(Instant.parse("2026-11-12T08:00:00Z"), feed.entries[0].publishedAt)
        assertEquals(sha256("https://rdf.example/index.rdf\nhttps://rdf.example/b"), feed.entries[1].id)
    }

    @Test
    fun rss091WithExternalDoctypeAndHtmlEntities() {
        val feed = parseFixture("rss091.xml", "https://old.example/rss")
        assertEquals("Old Caf\u00E9", feed.title)
        assertEquals("Vintage \u00A9 item", feed.entries.single().title)
    }

    @Test
    fun internalSubsetDoctypeIsRejected() {
        assertEquals(FetchFailure.Malformed, failureOf("internal-subset.xml"))
    }

    @Test(timeout = 2_000)
    fun entityBombIsRejectedQuickly() {
        assertEquals(FetchFailure.Malformed, failureOf("billion-laughs.xml"))
    }

    @Test
    fun jsonFeed11() {
        val feed = parseFixture("jsonfeed11.json", "https://json.example/feed.json")
        assertEquals("JSON Blog", feed.title)
        assertEquals("https://json.example/", feed.siteUrl)
        assertEquals("https://json.example/icon.png", feed.iconUrl)
        val first = feed.entries[0]
        assertEquals("Item Author", first.author)
        assertEquals("<p>Hello <strong>JSON</strong></p>", first.contentHtml)
        assertEquals(Thumbnail("https://json.example/1.jpg", null, null), first.thumbnail)
        assertEquals(Instant.parse("2026-11-13T15:00:00Z"), first.publishedAt)
        assertEquals(listOf(Enclosure("https://json.example/1.mp3", "audio/mpeg", 1000)), first.enclosures)
        val second = feed.entries[1]
        assertEquals(sha256("https://json.example/feed.json\n2"), second.id)
        assertEquals("https://elsewhere.example/2", second.url)
        assertEquals("Feed Author", second.author)
        assertNull(second.title)
        assertEquals("<p>Plain text</p><p>Second &lt;para&gt;</p>", second.contentHtml)
    }

    @Test
    fun jsonFeed10() {
        val entry = parseFixture("jsonfeed10.json", "https://oldjson.example/feed.json").entries.single()
        assertEquals("Solo", entry.author)
        assertEquals("<p>Body</p>", entry.contentHtml)
        assertEquals("Body", entry.excerpt)
    }

    @Test
    fun itemsWithoutIdsGetStableUniqueIdsAndDuplicatesAreDropped() {
        val first = parseFixture("no-guid.xml", "https://noid.example/feed")
        val second = parseFixture("no-guid.xml", "https://noid.example/feed")
        assertEquals(first.entries.map { it.id }, second.entries.map { it.id })
        assertEquals(listOf("Only a title", "Another title", "Duplicate one", "Future"), first.entries.map { it.title })
        assertNotEquals(first.entries[0].id, first.entries[1].id)
        assertNull(first.entries[0].publishedAt)
        assertEquals(NOW, first.entries[3].publishedAt)
    }

    @Test
    fun idsAreScopedToTheFeed() {
        val a = parseFixture("no-guid.xml", "https://one.example/feed").entries[2].id
        val b = parseFixture("no-guid.xml", "https://two.example/feed").entries[2].id
        assertNotEquals(a, b)
    }

    @Test
    fun htmlPagesAreNotFeeds() {
        assertEquals(FetchFailure.NotAFeed, failureOf("html-page.html"))
    }

    @Test
    fun malformedXmlIsReported() {
        assertEquals(FetchFailure.Malformed, failureOf("malformed.xml"))
    }

    @Test
    fun declaredLatin1() {
        val feed = parseFixture("latin1.xml", "https://latin.example/feed")
        assertEquals("Not\u00EDcias", feed.title)
        assertEquals("Ol\u00E1", feed.entries.single().title)
    }

    @Test
    fun headerCharsetIsUsedWhenTheDocumentDeclaresNone() {
        val feed = parseFixture("cp1252-noheader.xml", "https://cp.example/feed", charset = "windows-1252")
        assertEquals("Smart \u201Cquotes\u201D \u20AC", feed.title)
    }

    @Test
    fun utf8WithBom() {
        assertEquals("BOM feed \u2713", parseFixture("utf8-bom.xml", "https://bom.example/feed").title)
    }

    @Test
    fun utf16() {
        assertEquals("UTF-16 \u00E7\u00E3o", parseFixture("utf16.xml", "https://u16.example/feed").title)
    }
}
