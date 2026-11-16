// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class TextTest {

    @Test
    fun decodesNamedAndNumericEntities() {
        assertEquals("é — ’ 😀", decodeEntities("&eacute; &#8212; &#x2019; &#x1F600;"))
    }

    @Test
    fun decodesAtMostThreeLevels() {
        assertEquals("A & B", decodeEntities("A &amp;amp;amp; B"))
        assertEquals("A &amp; B", decodeEntities("A &amp;amp;amp;amp; B"))
    }

    @Test
    fun leavesUnknownAndInvalidEntitiesAlone() {
        assertEquals("&bogus; &#0; &#xD800; & alone", decodeEntities("&bogus; &#0; &#xD800; & alone"))
    }

    @Test
    fun hasAllHtml4Entities() {
        assertEquals(253, htmlEntities.size)
    }

    @Test
    fun parsesRfc822AndIsoDates() {
        val expected = Instant.parse("2026-11-16T10:00:00Z")
        assertEquals(expected, parseDate("Mon, 16 Nov 2026 10:00:00 GMT"))
        assertEquals(expected, parseDate("Mon, 16 Nov 2026 11:00:00 +0100"))
        assertEquals(expected, parseDate("2026-11-16T10:00:00.000+00:00"))
        assertNull(parseDate("last tuesday"))
        assertNull(parseDate(" "))
    }

    @Test
    fun formatsRfc822InUtc() {
        assertEquals("Mon, 16 Nov 2026 19:00:00 GMT", formatRfc822(Instant.parse("2026-11-16T19:00:00Z")))
    }

    @Test
    fun keepsValidSurrogatePairsWhenStripping() {
        assertEquals("ok😀ok", stripInvalidXmlChars("ok\u0000😀\uDC00ok￾"))
    }
}
