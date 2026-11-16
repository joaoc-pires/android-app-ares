// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.xml

import org.xmlpull.v1.XmlPullParser

public fun XmlPullParser.defineHtmlEntities() {
    htmlEntities.forEach { (name, value) ->
        if (name !in xmlBuiltInEntities) defineEntityReplacementText(name, value)
    }
}
