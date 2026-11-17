// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.opml

import com.joaopires.ares.core.xml.LimitExceededException
import com.joaopires.ares.core.xml.LimitedInputStream
import com.joaopires.ares.core.xml.decodeEntities
import com.joaopires.ares.core.xml.defineHtmlEntities
import com.joaopires.ares.core.xml.isLimitExceeded
import com.joaopires.ares.core.xml.parseDate
import com.joaopires.ares.core.xml.prepareForUntrustedInput
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.InputStream

public class OpmlReader(
    private val newParser: () -> XmlPullParser,
    private val limits: OpmlLimits = OpmlLimits(),
) {

    public fun read(input: InputStream): OpmlDocument {
        val parser = newParser()
        try {
            parser.prepareForUntrustedInput(processNamespaces = false)
            parser.setInput(LimitedInputStream(input, limits.maxBytes), null)
            parser.defineHtmlEntities()
            return readDocument(parser)
        } catch (_: LimitExceededException) {
            throw OpmlException.TooLarge(OpmlException.TooLarge.Limit.Bytes)
        } catch (e: XmlPullParserException) {
            if (e.isLimitExceeded()) {
                throw OpmlException.TooLarge(OpmlException.TooLarge.Limit.Bytes)
            }
            throw OpmlException.Malformed(e.lineNumber, e.columnNumber, e.message ?: "invalid XML", e)
        } catch (e: RuntimeException) {
            throw OpmlException.Malformed(parser.lineNumber, parser.columnNumber, e.message ?: "unexpected parser error", e)
        }
    }

    private fun readDocument(parser: XmlPullParser): OpmlDocument {
        var event = parser.nextToken()
        while (event != XmlPullParser.START_TAG) {
            when (event) {
                XmlPullParser.DOCDECL ->
                    throw OpmlException.Malformed(parser.lineNumber, parser.columnNumber, "DOCTYPE declarations are not allowed")
                XmlPullParser.END_DOCUMENT -> throw OpmlException.NotOpml()
            }
            event = parser.nextToken()
        }
        if (!parser.name.equals("opml", ignoreCase = true)) throw OpmlException.NotOpml()

        val roots = mutableListOf<OpmlOutline>()
        val stack = ArrayDeque<OutlineBuilder>()
        var outlineCount = 0
        var inHead = false
        var title: StringBuilder? = null
        var dateCreated: StringBuilder? = null
        var textTarget: StringBuilder? = null

        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "head" -> inHead = true
                    "title" -> if (inHead) textTarget = StringBuilder().also { title = it }
                    "datecreated" -> if (inHead) textTarget = StringBuilder().also { dateCreated = it }
                    "outline" -> {
                        if (stack.size >= limits.maxDepth) {
                            throw OpmlException.TooLarge(OpmlException.TooLarge.Limit.Depth)
                        }
                        if (++outlineCount > limits.maxOutlines) {
                            throw OpmlException.TooLarge(OpmlException.TooLarge.Limit.Outlines)
                        }
                        stack.addLast(OutlineBuilder.from(parser))
                    }
                }
                XmlPullParser.TEXT -> textTarget?.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                    "head" -> inHead = false
                    "title", "datecreated" -> textTarget = null
                    "outline" -> {
                        val outline = stack.removeLast().build()
                        if (stack.isEmpty()) roots += outline else stack.last().children += outline
                    }
                }
                XmlPullParser.END_DOCUMENT -> break
            }
        }

        return OpmlDocument(
            title = title?.toString()?.trim()?.let(::decodeEntities)?.ifBlank { null },
            dateCreated = dateCreated?.toString()?.let(::parseDate),
            outlines = roots,
        )
    }

    private class OutlineBuilder(private val attributes: Map<String, String>) {
        val children = mutableListOf<OpmlOutline>()

        private fun attribute(name: String): String? =
            attributes.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        fun build(): OpmlOutline {
            val title = attribute("title")?.let(::decodeEntities)?.trim()?.ifBlank { null }
            val type = attribute("type")?.trim()?.ifBlank { null }
            val feedUrl = attribute("xmlUrl")
                ?: if (type.equals("rss", ignoreCase = true) || type.equals("atom", ignoreCase = true)) attribute("url") else null
            return OpmlOutline(
                text = attribute("text")?.let(::decodeEntities)?.trim()?.ifBlank { null } ?: title ?: "",
                title = title,
                type = type,
                xmlUrl = feedUrl?.let(::decodeEntities)?.trim()?.ifBlank { null },
                htmlUrl = attribute("htmlUrl")?.let(::decodeEntities)?.trim()?.ifBlank { null },
                attributes = attributes,
                children = children.toList(),
            )
        }

        companion object {
            fun from(parser: XmlPullParser): OutlineBuilder {
                val attributes = LinkedHashMap<String, String>()
                for (index in 0 until parser.attributeCount) {
                    attributes[parser.getAttributeName(index)] = parser.getAttributeValue(index)
                }
                return OutlineBuilder(attributes)
            }
        }
    }
}
