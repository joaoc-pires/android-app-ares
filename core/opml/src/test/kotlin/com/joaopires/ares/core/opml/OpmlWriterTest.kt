// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.opml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.kxml2.io.KXmlSerializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant

class OpmlWriterTest {

    private val writer = OpmlWriter({ KXmlSerializer() })
    private val reader = OpmlReader({ KXmlParser() })
    private val now = Instant.parse("2026-11-16T19:00:00Z")

    private fun write(document: OpmlDocument): String =
        ByteArrayOutputStream().also { writer.write(document, it) }.toString(Charsets.UTF_8)

    @Test
    fun roundTripsFeedsWithSections() {
        val feeds = listOf(
            OpmlFeed("https://a.example/feed", "Apples & Pears", "https://a.example/", null),
            OpmlFeed("https://z.example/feed?x=1&y=<2>", "zebra \"quoted\" 🦓", null, null),
            OpmlFeed("https://k.example/feed", "Kotlin", "https://k.example/", "Android"),
            OpmlFeed("https://c.example/feed", "Compose", null, "Android"),
            OpmlFeed("https://n.example/feed", "News", null, "Écoles & Co"),
        )
        val document = OpmlDocument.of("Ares", feeds, now)

        val parsed = reader.read(ByteArrayInputStream(write(document).toByteArray()))

        assertEquals("Ares", parsed.title)
        assertEquals(now, parsed.dateCreated)
        assertEquals(
            listOf(
                feeds[0],
                feeds[1],
                feeds[3],
                feeds[2],
                feeds[4],
            ),
            parsed.feeds(),
        )
    }

    @Test
    fun sortsUnsectionedFeedsFirstThenSectionsByName() {
        val document = OpmlDocument.of(
            "Ares",
            listOf(
                OpmlFeed("https://3", "charlie", null, "Zed"),
                OpmlFeed("https://2", "Bravo", null, null),
                OpmlFeed("https://1", "alpha", null, "apple"),
                OpmlFeed("https://0", "Alpha", null, null),
            ),
            now,
        )
        assertEquals(listOf("Alpha", "Bravo", "apple", "Zed"), document.outlines.map { it.text })
    }

    @Test
    fun writesExpectedDocument() {
        val document = OpmlDocument.of(
            "Ares",
            listOf(
                OpmlFeed("https://a.example/feed?a=1&b=2", "A & B", "https://a.example/", null),
                OpmlFeed("https://s.example/feed", "Sectioned", null, "Tech"),
            ),
            now,
        )
        val expected = """
            <?xml version='1.0' encoding='UTF-8' ?>
            <opml version="2.0">
              <head>
                <title>Ares</title>
                <dateCreated>Mon, 16 Nov 2026 19:00:00 GMT</dateCreated>
              </head>
              <body>
                <outline text="A &amp; B" type="rss" title="A &amp; B" xmlUrl="https://a.example/feed?a=1&amp;b=2" htmlUrl="https://a.example/" />
                <outline text="Tech">
                  <outline text="Sectioned" type="rss" title="Sectioned" xmlUrl="https://s.example/feed" />
                </outline>
              </body>
            </opml>
        """.trimIndent()
        assertEquals(expected, write(document).replace("\r\n", "\n").trim())
    }

    @Test
    fun stripsCharactersThatAreInvalidInXml() {
        val document = OpmlDocument.of("Bad\u0001Title", listOf(OpmlFeed("https://x.example/feed", "Bell\u0007", null, null)), now)
        val xml = write(document)
        assertFalse(xml.contains("&#1;"))
        assertFalse(xml.contains("&#7;"))
        val parsed = reader.read(ByteArrayInputStream(xml.toByteArray()))
        assertEquals("BadTitle", parsed.title)
        assertEquals("Bell", parsed.feeds().single().title)
    }

    @Test
    fun keepsUnknownAttributesWhenRewritingParsedDocuments() {
        val xml = "<opml version=\"2.0\"><body><outline text=\"F\" type=\"rss\" xmlUrl=\"https://f.example/feed\" category=\"/news\"/></body></opml>"
        val rewritten = write(reader.read(ByteArrayInputStream(xml.toByteArray())))
        assertTrue(rewritten.contains("category=\"/news\""))
        assertEquals(1, Regex("xmlUrl=").findAll(rewritten).count())
    }
}
