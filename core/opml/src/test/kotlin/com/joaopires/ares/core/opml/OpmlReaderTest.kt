// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.opml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant

class OpmlReaderTest {

    private val reader = OpmlReader({ KXmlParser() })

    private fun fixture(name: String): InputStream =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing fixture $name" }

    private fun read(name: String): OpmlDocument = fixture(name).use { reader.read(it) }

    private inline fun <reified T : OpmlException> assertFails(block: () -> Unit): T {
        try {
            block()
        } catch (e: OpmlException) {
            if (e is T) return e
            fail("expected ${T::class.simpleName} but got ${e::class.simpleName}: ${e.message}")
        }
        fail("expected ${T::class.simpleName} but nothing was thrown")
        throw AssertionError()
    }

    @Test
    fun readsFlatList() {
        val document = read("flat.opml")
        assertEquals("Flat subscriptions", document.title)
        assertEquals(Instant.parse("2026-11-16T10:00:00Z"), document.dateCreated)
        assertEquals(
            listOf(
                OpmlFeed("https://alpha.example/feed.xml", "Alpha", "https://alpha.example/", null),
                OpmlFeed("https://beta.example/atom.xml", "Beta", null, null),
                OpmlFeed("https://gamma.example/rss", "Gamma", null, null),
            ),
            document.feeds(),
        )
    }

    @Test
    fun findsFeedsInsideCategories() {
        val feeds = read("categories.opml").feeds()
        assertEquals(
            listOf(
                OpmlFeed("https://news.example/feed", "Daily News", "https://news.example", "News"),
                OpmlFeed("https://world.example/rss.xml", "World Report", "https://world.example", "News"),
                OpmlFeed("https://gadgets.example/feed/", "Gadgets", "https://gadgets.example", "Tech"),
                OpmlFeed("https://loose.example/feed", "Loose Feed", null, null),
            ),
            feeds,
        )
    }

    @Test
    fun keepsNestedTreeAndUsesClosestFolderAsSection() {
        val document = read("nested.opml")
        val tech = document.outlines.single()
        assertEquals("Tech", tech.text)
        val android = tech.children[1]
        assertEquals("Android", android.text)
        assertEquals("Kotlin", android.children[1].text)
        assertEquals(
            listOf(
                "https://top.example/feed" to "Tech",
                "https://android.example/feed" to "Android",
                "https://kotlin.example/feed" to "Kotlin",
            ),
            document.feeds().map { it.url to it.section },
        )
    }

    @Test
    fun decodesIosAresEscaping() {
        val document = read("ios-ares.opml")
        assertEquals("Ares - RSS Reader", document.title)
        assertEquals(Instant.parse("2026-11-01T09:30:00Z"), document.dateCreated)
        val feed = document.feeds().first()
        assertEquals("A & B", feed.title)
        assertEquals("https://ab.example/feed?a=1&b=2", feed.url)
        assertEquals(2, document.feeds().size)
    }

    @Test
    fun isLenientAboutVersionCaseAndAttributes() {
        val document = read("lenient.opml")
        assertEquals("Lenient", document.title)
        assertEquals(
            listOf(
                OpmlFeed("https://titleonly.example/feed", "Title Only", null, null),
                OpmlFeed("https://urlattr.example/atom", "Url Attribute", null, null),
            ),
            document.feeds(),
        )
    }

    @Test
    fun keepsWholeTitleAcrossChunks() {
        assertEquals("Tom & Jerry <Cartoons> — Feeds", read("title-chunks.opml").title)
    }

    @Test
    fun acceptsHtmlEntities() {
        val document = read("html-entities.opml")
        assertEquals("Café News", document.title)
        assertEquals("Café © Crème", document.feeds().single().title)
    }

    @Test
    fun readsDeclaredLatin1Encoding() {
        val document = read("latin1.opml")
        assertEquals("Olá Português", document.title)
        assertEquals("Notícias", document.feeds().single().title)
    }

    @Test
    fun readsUtf8WithBom() {
        val document = read("bom-utf8.opml")
        assertEquals("BOM 📰", document.title)
        assertEquals("Jornal 📰", document.feeds().single().title)
    }

    @Test
    fun rejectsDoctypeSoExternalEntitiesAreNeverResolved() {
        val error = assertFails<OpmlException.Malformed> { read("xxe.opml") }
        assertTrue(error.message!!.contains("DOCTYPE"))
    }

    @Test(timeout = 2_000)
    fun rejectsEntityBombQuickly() {
        assertFails<OpmlException.Malformed> { read("billion-laughs.opml") }
    }

    @Test
    fun rejectsFilesOverTheSizeLimit() {
        val header = """<?xml version="1.0" encoding="UTF-8"?><opml version="2.0"><head><title>"""
        val bytes = (header + "a".repeat(11 * 1024 * 1024) + "</title></head><body/></opml>").toByteArray()
        val error = assertFails<OpmlException.TooLarge> { reader.read(ByteArrayInputStream(bytes)) }
        assertEquals(OpmlException.TooLarge.Limit.Bytes, error.limit)
    }

    @Test
    fun rejectsTooDeepNesting() {
        val depth = 65
        val xml = "<opml version=\"2.0\"><body>" + "<outline text=\"x\">".repeat(depth) + "</outline>".repeat(depth) + "</body></opml>"
        val error = assertFails<OpmlException.TooLarge> { reader.read(ByteArrayInputStream(xml.toByteArray())) }
        assertEquals(OpmlException.TooLarge.Limit.Depth, error.limit)
    }

    @Test
    fun rejectsTooManyOutlines() {
        val xml = "<opml version=\"2.0\"><body>" + "<outline text=\"x\"/>".repeat(20_001) + "</body></opml>"
        val error = assertFails<OpmlException.TooLarge> { reader.read(ByteArrayInputStream(xml.toByteArray())) }
        assertEquals(OpmlException.TooLarge.Limit.Outlines, error.limit)
    }

    @Test
    fun reportsLineAndColumnForMalformedXml() {
        val error = assertFails<OpmlException.Malformed> { read("malformed.opml") }
        assertTrue("line was ${error.line}", error.line >= 6)
        assertTrue("column was ${error.column}", error.column > 0)
    }

    @Test
    fun rejectsDocumentsThatAreNotOpml() {
        assertFails<OpmlException.NotOpml> { read("not-opml.xml") }
    }

    @Test
    fun rejectsEmptyInput() {
        assertFails<OpmlException> { reader.read(ByteArrayInputStream(ByteArray(0))) }
    }

    @Test
    fun readsEmptyBody() {
        val document = read("empty-body.opml")
        assertNull(document.title)
        assertTrue(document.outlines.isEmpty())
        assertTrue(document.feeds().isEmpty())
    }

    @Test
    fun keepsFirstOfDuplicateFeedsAndTrimsUrls() {
        assertEquals(
            listOf(
                OpmlFeed("https://shared.example/feed", "Shared (first)", null, "First"),
                OpmlFeed("https://unique.example/feed", "Unique", null, "Second"),
            ),
            read("duplicates.opml").feeds(),
        )
    }

    @Test
    fun fallsBackToHostWhenFeedHasNoTitle() {
        val xml = "<opml version=\"2.0\"><body><outline type=\"rss\" xmlUrl=\"https://untitled.example/feed\"/></body></opml>"
        assertEquals("untitled.example", reader.read(ByteArrayInputStream(xml.toByteArray())).feeds().single().title)
    }
}
