// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares

import android.util.Xml
import com.joaopires.ares.core.feed.Feed
import com.joaopires.ares.core.feed.FeedParseError
import com.joaopires.ares.core.feed.FetchFailure
import com.joaopires.ares.core.feed.parseFeedDocument
import com.joaopires.ares.core.opml.OpmlDocument
import com.joaopires.ares.core.opml.OpmlException
import com.joaopires.ares.core.opml.OpmlFeed
import com.joaopires.ares.core.opml.OpmlReader
import com.joaopires.ares.core.opml.OpmlWriter
import com.joaopires.ares.core.opml.feeds
import com.joaopires.ares.core.opml.of
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class AndroidXmlParserTest {

    private fun bytes(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(name)) { "missing fixture $name" }.use { it.readBytes() }

    private fun parse(name: String, url: String = "https://x.example/feed", charset: String? = null): Feed =
        parseFeedDocument(bytes(name), url, { Xml.newPullParser() }, NOW, charset)

    private fun failureOf(name: String): FetchFailure = try {
        parse(name)
        fail("expected $name to be rejected")
        throw AssertionError()
    } catch (e: FeedParseError) {
        e.failure
    }

    private val opmlReader = OpmlReader({ Xml.newPullParser() })

    private fun readOpml(name: String): OpmlDocument = javaClass.classLoader!!.getResourceAsStream(name)!!.use { opmlReader.read(it) }

    @Test
    fun androidParserHasDoctypeProcessingOnByDefault() {
        assertTrue(Xml.newPullParser().getFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_DOCDECL))
    }

    @Test
    fun feedWithStylesheetAndHtmlEntities() {
        val feed = parse("feedpress-style.xml")
        assertEquals("Many Colours", feed.title)
        assertEquals(2, feed.entries.size)
        assertEquals("Hands-on with the new laptop\u2019s display", feed.entries[0].title)
    }

    @Test
    fun wordpressAtomYoutubeAndPodcastFeeds() {
        assertEquals("Example & Blog", parse("wordpress.xml").title)
        val atom = parse("atom-base.xml", "https://atom.example/blog/feed.atom")
        assertEquals("https://atom.example/blog/posts/one.html", atom.entries[0].url)
        assertTrue(atom.entries[0].contentHtml!!.contains("https://atom.example/blog/posts/two.html"))
        assertEquals("abcdefghijk", parse("youtube.xml").entries.single().videoId)
        assertEquals("Joana Host", parse("podcast.xml").entries.single().author)
        assertEquals(2, parse("rss1.rdf").entries.size)
    }

    @Test
    fun rss091WithExternalDoctype() {
        val feed = parse("rss091.xml")
        assertEquals("Old Caf\u00E9", feed.title)
        assertEquals("Vintage \u00A9 item", feed.entries.single().title)
    }

    @Test
    fun encodings() {
        assertEquals("Not\u00EDcias", parse("latin1.xml").title)
        assertEquals("Smart \u201Cquotes\u201D \u20AC", parse("cp1252-noheader.xml", charset = "windows-1252").title)
        assertEquals("BOM feed \u2713", parse("utf8-bom.xml").title)
        assertEquals("UTF-16 \u00E7\u00E3o", parse("utf16.xml").title)
    }

    @Test(timeout = 5_000)
    fun dangerousDoctypesAreRejected() {
        assertEquals(FetchFailure.Malformed, failureOf("internal-subset.xml"))
        assertEquals(FetchFailure.Malformed, failureOf("billion-laughs.xml"))
        assertEquals(FetchFailure.Malformed, failureOf("malformed.xml"))
        assertEquals(FetchFailure.NotAFeed, failureOf("html-page.html"))
    }

    @Test
    fun opmlWithCategoriesAndEntities() {
        assertEquals(4, readOpml("categories.opml").feeds().size)
        assertEquals("Caf\u00E9 \u00A9 Cr\u00E8me", readOpml("html-entities.opml").feeds().single().title)
        assertEquals("A & B", readOpml("ios-ares.opml").feeds().first().title)
    }

    @Test(timeout = 5_000)
    fun opmlDoctypesAreRejected() {
        listOf("xxe.opml", "billion-laughs.opml").forEach { name ->
            try {
                readOpml(name)
                fail("expected $name to be rejected")
            } catch (_: OpmlException.Malformed) {
            }
        }
    }

    @Test
    fun opmlRoundTripWithAndroidSerializer() {
        val feeds = listOf(
            OpmlFeed("https://a.example/feed?x=1&y=2", "A & B", "https://a.example/", null),
            OpmlFeed("https://k.example/feed", "Kotlin", null, "Android"),
        )
        val out = ByteArrayOutputStream()
        OpmlWriter({ Xml.newSerializer() }).write(OpmlDocument.of("Ares", feeds, NOW), out)
        assertEquals(feeds, opmlReader.read(ByteArrayInputStream(out.toByteArray())).feeds())
    }
}
