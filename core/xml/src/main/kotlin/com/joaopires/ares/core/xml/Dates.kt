// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.xml

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

public fun formatRfc822(instant: Instant): String =
    DateTimeFormatter.RFC_1123_DATE_TIME.format(instant.atOffset(ZoneOffset.UTC))

public fun parseDate(value: String): Instant? {
    val text = value.trim().replace(Regex("\\s+"), " ")
    if (text.isEmpty()) return null
    return parseIso(text) ?: parseRfc822(text)
}

private val isoPattern = Regex(
    """^(\d{4})[-/](\d{1,2})[-/](\d{1,2})(?:[T ](\d{1,2}):(\d{2})(?::(\d{2})(?:[.,](\d{1,9}))?)?)? ?(Z|z|UTC|GMT|[+-]\d{2}(?::?\d{2})?)?$"""
)

private val rfc822Pattern = Regex(
    """^(?:[A-Za-z]{2,9}\.?,? ?)?(\d{1,2})[ -]([A-Za-z]{3,9})\.?[ -](\d{2,4})(?:,? (\d{1,2}):(\d{2})(?::(\d{2}))?(?:\.\d{1,9})?)? ?(.*)$"""
)

private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

private val namedZones = mapOf(
    "" to 0, "Z" to 0, "UT" to 0, "UTC" to 0, "GMT" to 0, "WET" to 0,
    "EST" to -5 * 60, "EDT" to -4 * 60,
    "CST" to -6 * 60, "CDT" to -5 * 60,
    "MST" to -7 * 60, "MDT" to -6 * 60,
    "PST" to -8 * 60, "PDT" to -7 * 60,
    "BST" to 60, "CET" to 60, "CEST" to 2 * 60, "WEST" to 60, "EET" to 2 * 60, "EEST" to 3 * 60,
)

private fun parseIso(text: String): Instant? {
    val match = isoPattern.matchEntire(text) ?: return null
    val g = match.groupValues
    val nanos = g[7].takeIf { it.isNotEmpty() }?.padEnd(9, '0')?.toInt() ?: 0
    val offset = zoneOffsetMinutes(g[8]) ?: return null
    return build(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toIntOrZero(), g[5].toIntOrZero(), g[6].toIntOrZero(), nanos, offset)
}

private fun parseRfc822(text: String): Instant? {
    val match = rfc822Pattern.matchEntire(text) ?: return null
    val g = match.groupValues
    val month = months.indexOf(g[2].take(3).lowercase()).takeIf { it >= 0 }?.plus(1) ?: return null
    val year = g[3].toInt().let {
        when {
            g[3].length == 4 -> it
            g[3].length == 2 -> if (it < 50) 2000 + it else 1900 + it
            else -> return null
        }
    }
    val offset = zoneOffsetMinutes(g[7].trim()) ?: return null
    return build(year, month, g[1].toInt(), g[4].toIntOrZero(), g[5].toIntOrZero(), g[6].toIntOrZero(), 0, offset)
}

private fun zoneOffsetMinutes(zone: String): Int? {
    namedZones[zone.uppercase()]?.let { return it }
    val numeric = Regex("""^(?:GMT|UTC|UT)?([+-])(\d{1,2})(?::?(\d{2}))?$""", RegexOption.IGNORE_CASE).matchEntire(zone)
    if (numeric != null) {
        val sign = if (numeric.groupValues[1] == "-") -1 else 1
        val hours = numeric.groupValues[2].toInt()
        val minutes = numeric.groupValues[3].toIntOrZero()
        if (hours > 18 || minutes > 59) return null
        return sign * (hours * 60 + minutes)
    }
    if (zone.length == 1 && zone[0].isLetter() && zone[0].uppercaseChar() != 'J') return 0
    return null
}

private fun build(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, nanos: Int, offsetMinutes: Int): Instant? =
    try {
        LocalDateTime.of(year, month, day, hour, minute, if (second == 60) 59 else second, nanos)
            .toInstant(ZoneOffset.ofTotalSeconds(offsetMinutes * 60))
    } catch (_: Exception) {
        null
    }

private fun String.toIntOrZero(): Int = if (isEmpty()) 0 else toInt()
