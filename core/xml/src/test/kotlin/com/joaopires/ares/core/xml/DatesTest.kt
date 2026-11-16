// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.xml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class DatesTest {

    private val cases = listOf(
        "Mon, 16 Nov 2026 10:00:00 GMT" to "2026-11-16T10:00:00Z",
        "Mon, 16 Nov 2026 10:00:00 +0000" to "2026-11-16T10:00:00Z",
        "Mon, 16 Nov 2026 11:00:00 +0100" to "2026-11-16T10:00:00Z",
        "Mon, 16 Nov 2026 05:00:00 EST" to "2026-11-16T10:00:00Z",
        "Mon, 16 Nov 2026 03:00:00 PDT" to "2026-11-16T10:00:00Z",
        "Mon, 16 Nov 2026 10:00:00 UT" to "2026-11-16T10:00:00Z",
        "Mon, 16 Nov 2026 10:00:00 Z" to "2026-11-16T10:00:00Z",
        "Mon, 16 Nov 2026 10:00:00 A" to "2026-11-16T10:00:00Z",
        "Mon, 16 Nov 2026 12:00:00 GMT+2" to "2026-11-16T10:00:00Z",
        "Mon, 16 Nov 2026 12:00:00 GMT+02:00" to "2026-11-16T10:00:00Z",
        "16 Nov 2026 10:00:00 GMT" to "2026-11-16T10:00:00Z",
        "Mon,16 Nov 2026 10:00:00 GMT" to "2026-11-16T10:00:00Z",
        "Monday, 16 November 2026 10:00:00 GMT" to "2026-11-16T10:00:00Z",
        "Mon, 6 Nov 2026 10:00:00 GMT" to "2026-11-06T10:00:00Z",
        "Mon, 16 Nov 26 10:00:00 GMT" to "2026-11-16T10:00:00Z",
        "Mon, 16 Nov 2026 10:00 GMT" to "2026-11-16T10:00:00Z",
        "Mon, 16 Nov 2026 10:00:00" to "2026-11-16T10:00:00Z",
        "Mon,  16  Nov  2026  10:00:00  GMT" to "2026-11-16T10:00:00Z",
        "Mon, 16 Sept 2026 10:00:00 GMT" to "2026-09-16T10:00:00Z",
        "Mon, 16-Nov-2026 10:00:00 GMT" to "2026-11-16T10:00:00Z",
        "16 Nov 2026" to "2026-11-16T00:00:00Z",
        "2026-11-16T10:00:00Z" to "2026-11-16T10:00:00Z",
        "2026-11-16T10:00:00.123Z" to "2026-11-16T10:00:00.123Z",
        "2026-11-16T11:00:00+01:00" to "2026-11-16T10:00:00Z",
        "2026-11-16T11:00:00+0100" to "2026-11-16T10:00:00Z",
        "2026-11-16T11:00:00+01" to "2026-11-16T10:00:00Z",
        "2026-11-16T10:00:00" to "2026-11-16T10:00:00Z",
        "2026-11-16 10:00:00" to "2026-11-16T10:00:00Z",
        "2026-11-16T10:00Z" to "2026-11-16T10:00:00Z",
        "2026-11-16" to "2026-11-16T00:00:00Z",
        "2026/11/16 10:00:00" to "2026-11-16T10:00:00Z",
        "2026-11-16T10:00:00.000+00:00" to "2026-11-16T10:00:00Z",
        "  2026-11-16T10:00:00Z  " to "2026-11-16T10:00:00Z",
    )

    @Test
    fun parsesRealWorldFormats() {
        cases.forEach { (input, expected) ->
            assertEquals("input: '$input'", Instant.parse(expected), parseDate(input))
        }
    }

    @Test
    fun rejectsNonsense() {
        listOf("", " ", "last tuesday", "2026-13-45", "Mon, 32 Nov 2026 10:00:00 GMT", "Mon, 16 Foo 2026 10:00:00 GMT", "16 Nov 2026 10:00:00 XYZ")
            .forEach { assertNull("input: '$it'", parseDate(it)) }
    }

    @Test
    fun formatsRfc822InUtc() {
        assertEquals("Mon, 16 Nov 2026 19:00:00 GMT", formatRfc822(Instant.parse("2026-11-16T19:00:00Z")))
    }
}
