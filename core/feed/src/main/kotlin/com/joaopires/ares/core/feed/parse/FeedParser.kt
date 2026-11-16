// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.feed.parse

import com.joaopires.ares.core.feed.FetchFailure
import org.xmlpull.v1.XmlPullParser
import java.nio.charset.Charset

internal enum class FeedFormat { Xml, Json, Html, Unknown }

internal fun detectFormat(bytes: ByteArray): FeedFormat {
    val prefix = decodePrefix(bytes, 4096).trimStart('\uFEFF', '\u00EF', '\u00BB', '\u00BF', ' ', '\t', '\r', '\n')
    return when {
        prefix.startsWith("{") -> FeedFormat.Json
        prefix.isEmpty() -> FeedFormat.Unknown
        Regex("^(<\\?xml[^>]*>\\s*)?(<!--.*?-->\\s*)*<!DOCTYPE\\s+html", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).containsMatchIn(prefix) ||
            Regex("^(<!--.*?-->\\s*)*<html[\\s>]", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).containsMatchIn(prefix) -> FeedFormat.Html
        prefix.startsWith("<") -> FeedFormat.Xml
        else -> FeedFormat.Unknown
    }
}

internal fun parseRawFeed(bytes: ByteArray, headerCharset: String?, newParser: () -> XmlPullParser): RawFeed =
    when (detectFormat(bytes)) {
        FeedFormat.Json -> parseJsonFeed(decodeText(bytes, headerCharset))
        FeedFormat.Xml -> interpretXmlFeed(parseXmlTree(bytes, xmlCharset(bytes, headerCharset), newParser))
        FeedFormat.Html, FeedFormat.Unknown -> throw FeedParseException(FetchFailure.NotAFeed, "content is not a feed")
    }

private fun xmlCharset(bytes: ByteArray, headerCharset: String?): String? {
    if (hasBom(bytes)) return null
    val declaration = Regex("^<\\?xml[^>]*encoding\\s*=\\s*[\"'][^\"']+[\"']").containsMatchIn(decodePrefix(bytes, 512))
    return if (declaration) null else headerCharset?.takeIf { isSupported(it) }
}

internal fun decodeText(bytes: ByteArray, headerCharset: String?): String = when {
    bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
        String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    else -> String(bytes, headerCharset?.takeIf { isSupported(it) }?.let { Charset.forName(it) } ?: Charsets.UTF_8)
}

private fun hasBom(bytes: ByteArray): Boolean =
    (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) ||
        (bytes.size >= 2 && ((bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) || (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())))

private fun isSupported(name: String): Boolean = try {
    Charset.isSupported(name)
} catch (_: Exception) {
    false
}
