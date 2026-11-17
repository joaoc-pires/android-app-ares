// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.xml

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException

public fun XmlPullParser.prepareForUntrustedInput(processNamespaces: Boolean) {
    try {
        setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
    } catch (e: XmlPullParserException) {
        if (getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) throw e
    }
    setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, processNamespaces)
}

public fun XmlPullParser.defineHtmlEntities() {
    htmlEntities.forEach { (name, value) ->
        if (name !in xmlBuiltInEntities) defineEntityReplacementText(name, value)
    }
}
