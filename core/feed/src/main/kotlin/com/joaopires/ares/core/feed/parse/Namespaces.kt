// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.feed.parse

internal object Ns {
    val ATOM = setOf("http://www.w3.org/2005/Atom", "http://purl.org/atom/ns#")
    val RSS = setOf("", "http://purl.org/rss/1.0/", "http://my.netscape.com/rdf/simple/0.9/", "http://backend.userland.com/rss2")
    val RDF = setOf("http://www.w3.org/1999/02/22-rdf-syntax-ns#")
    val CONTENT = setOf("http://purl.org/rss/1.0/modules/content/")
    val DC = setOf("http://purl.org/dc/elements/1.1/", "http://purl.org/dc/terms/")
    val MEDIA = setOf("http://search.yahoo.com/mrss/", "http://search.yahoo.com/mrss")
    val ITUNES = setOf("http://www.itunes.com/dtds/podcast-1.0.dtd")
    val YOUTUBE = setOf("http://www.youtube.com/xml/schemas/2015")
    val XHTML = setOf("http://www.w3.org/1999/xhtml")
}
