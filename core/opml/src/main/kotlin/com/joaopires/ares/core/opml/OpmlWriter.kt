// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.opml

import com.joaopires.ares.core.xml.formatRfc822
import com.joaopires.ares.core.xml.stripInvalidXmlChars
import org.xmlpull.v1.XmlSerializer
import java.io.OutputStream

public class OpmlWriter(private val newSerializer: () -> XmlSerializer) {

    public fun write(document: OpmlDocument, output: OutputStream) {
        val serializer = newSerializer()
        serializer.setOutput(output, "UTF-8")
        serializer.setFeature(INDENT_OUTPUT, true)
        serializer.startDocument("UTF-8", null)
        serializer.startTag(null, "opml")
        serializer.attribute(null, "version", "2.0")

        serializer.startTag(null, "head")
        document.title?.let { serializer.textElement("title", it) }
        document.dateCreated?.let { serializer.textElement("dateCreated", formatRfc822(it)) }
        serializer.endTag(null, "head")

        serializer.startTag(null, "body")
        document.outlines.forEach { serializer.outline(it) }
        serializer.endTag(null, "body")

        serializer.endTag(null, "opml")
        serializer.endDocument()
        serializer.flush()
    }

    private fun XmlSerializer.textElement(name: String, value: String) {
        startTag(null, name)
        text(stripInvalidXmlChars(value))
        endTag(null, name)
    }

    private fun XmlSerializer.outline(outline: OpmlOutline) {
        startTag(null, "outline")
        val known = listOf(
            "text" to outline.text,
            "type" to outline.type,
            "title" to outline.title,
            "xmlUrl" to outline.xmlUrl,
            "htmlUrl" to outline.htmlUrl,
        )
        known.forEach { (name, value) ->
            if (!value.isNullOrBlank() || name == "text") attribute(null, name, stripInvalidXmlChars(value.orEmpty()))
        }
        outline.attributes.forEach { (name, value) ->
            if (value.isNotBlank() && known.none { it.first.equals(name, ignoreCase = true) }) {
                attribute(null, name, stripInvalidXmlChars(value))
            }
        }
        outline.children.forEach { outline(it) }
        endTag(null, "outline")
    }

    private companion object {
        const val INDENT_OUTPUT = "http://xmlpull.org/v1/doc/features.html#indent-output"
    }
}
