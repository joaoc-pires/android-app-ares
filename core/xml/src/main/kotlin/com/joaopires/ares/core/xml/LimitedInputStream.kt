// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.xml

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

public class LimitExceededException : IOException("input limit exceeded")

public class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) add(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = super.read(buffer, offset, length)
        if (read > 0) add(read.toLong())
        return read
    }

    private fun add(bytes: Long) {
        count += bytes
        if (count > limit) throw LimitExceededException()
    }
}

public fun Throwable.isLimitExceeded(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is LimitExceededException) return true
        current = if (current is org.xmlpull.v1.XmlPullParserException) current.detail ?: current.cause else current.cause
    }
    return false
}
