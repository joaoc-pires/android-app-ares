// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.feed.parse

import com.joaopires.ares.core.feed.FetchFailure
import com.joaopires.ares.core.xml.defineHtmlEntities
import com.joaopires.ares.core.xml.isLimitExceeded
import com.joaopires.ares.core.xml.prepareForUntrustedInput
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayInputStream

internal const val NS_XML = "http://www.w3.org/XML/1998/namespace"

internal sealed interface XmlNode

internal class XmlText(val text: String) : XmlNode

internal class XmlElement(
    val namespace: String,
    val name: String,
    private val attributes: List<Triple<String, String, String>>,
) : XmlNode {
    val children = mutableListOf<XmlNode>()

    fun attr(name: String, namespace: String = ""): String? =
        attributes.firstOrNull { it.first == namespace && it.second.equals(name, ignoreCase = true) }?.third

    fun elements(): List<XmlElement> = children.filterIsInstance<XmlElement>()

    fun child(namespaces: Collection<String>, name: String): XmlElement? =
        children.firstOrNull { it is XmlElement && it.namespace in namespaces && it.name == name } as XmlElement?

    fun children(namespaces: Collection<String>, name: String): List<XmlElement> =
        children.filterIsInstance<XmlElement>().filter { it.namespace in namespaces && it.name == name }

    fun text(): String = buildString { collectText(this@XmlElement, this) }

    fun innerXml(): String = buildString { children.forEach { serialize(it, this) } }

    private fun collectText(element: XmlElement, out: StringBuilder) {
        element.children.forEach {
            when (it) {
                is XmlText -> out.append(it.text)
                is XmlElement -> collectText(it, out)
            }
        }
    }

    private fun serialize(node: XmlNode, out: StringBuilder) {
        when (node) {
            is XmlText -> out.append(escape(node.text, attribute = false))
            is XmlElement -> {
                out.append('<').append(node.name)
                node.attributes.filter { it.first.isEmpty() }.forEach { (_, name, value) ->
                    out.append(' ').append(name).append("=\"").append(escape(value, attribute = true)).append('"')
                }
                out.append('>')
                node.children.forEach { serialize(it, out) }
                out.append("</").append(node.name).append('>')
            }
        }
    }

    private fun escape(value: String, attribute: Boolean): String = buildString(value.length) {
        value.forEach {
            when (it) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> if (attribute) append("&quot;") else append(it)
                else -> append(it)
            }
        }
    }
}

internal class XmlTreeLimits(val maxElements: Int = 500_000, val maxDepth: Int = 256)

internal fun parseXmlTree(
    bytes: ByteArray,
    charset: String?,
    newParser: () -> XmlPullParser,
    limits: XmlTreeLimits = XmlTreeLimits(),
): XmlElement {
    rejectInternalSubset(bytes)
    val parser = newParser()
    try {
        parser.prepareForUntrustedInput(processNamespaces = true)
        parser.setInput(ByteArrayInputStream(bytes), charset)
        parser.defineHtmlEntities()
        val stack = ArrayDeque<XmlElement>()
        var root: XmlElement? = null
        var count = 0
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    if (++count > limits.maxElements || stack.size >= limits.maxDepth) {
                        throw FeedParseException(FetchFailure.TooLarge, "feed exceeds structural limits")
                    }
                    val attributes = (0 until parser.attributeCount).map {
                        Triple(parser.getAttributeNamespace(it).orEmpty(), parser.getAttributeName(it), parser.getAttributeValue(it))
                    }
                    val element = XmlElement(parser.namespace.orEmpty(), parser.name, attributes)
                    stack.lastOrNull()?.children?.add(element) ?: run { root = element }
                    stack.addLast(element)
                }
                XmlPullParser.TEXT -> stack.lastOrNull()?.children?.add(XmlText(parser.text))
                XmlPullParser.END_TAG -> stack.removeLast()
                XmlPullParser.END_DOCUMENT -> return root ?: throw FeedParseException(FetchFailure.NotAFeed, "empty document")
            }
        }
    } catch (e: XmlPullParserException) {
        if (e.isLimitExceeded()) throw FeedParseException(FetchFailure.TooLarge, "feed too large")
        throw FeedParseException(FetchFailure.Malformed, "malformed XML at ${e.lineNumber}:${e.columnNumber}: ${e.message}")
    }
}

private fun rejectInternalSubset(bytes: ByteArray) {
    val prolog = decodePrefix(bytes, 64 * 1024)
    val doctype = Regex("<!DOCTYPE", RegexOption.IGNORE_CASE).find(prolog) ?: return
    val firstElement = Regex("<[A-Za-z_]").find(prolog)
    if (firstElement != null && firstElement.range.first < doctype.range.first) return
    val end = prolog.indexOf('>', doctype.range.last)
    val bracket = prolog.indexOf('[', doctype.range.last)
    if (bracket >= 0 && (end < 0 || bracket < end)) {
        throw FeedParseException(FetchFailure.Malformed, "DOCTYPE with an internal subset is not allowed")
    }
}

internal fun decodePrefix(bytes: ByteArray, max: Int): String {
    val length = minOf(bytes.size, max)
    val charset = when {
        length >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
        length >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
        else -> Charsets.ISO_8859_1
    }
    return String(bytes, 0, length, charset)
}
