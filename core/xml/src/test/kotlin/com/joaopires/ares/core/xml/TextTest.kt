// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.xml

import org.junit.Assert.assertEquals
import org.junit.Test

class TextTest {

    @Test
    fun decodesNamedAndNumericEntities() {
        assertEquals("\u00E9 \u2014 \u2019 \uD83D\uDE00", decodeEntities("&eacute; &#8212; &#x2019; &#x1F600;"))
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
    fun keepsValidSurrogatePairsWhenStripping() {
        assertEquals("ok\uD83D\uDE00ok", stripInvalidXmlChars("ok\u0000\uD83D\uDE00\uDC00ok\uFFFE"))
    }
}
