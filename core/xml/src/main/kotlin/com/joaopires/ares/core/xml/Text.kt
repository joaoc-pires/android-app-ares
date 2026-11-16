// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.xml

private const val MAX_DECODE_PASSES = 3

private val entityPattern = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});")

public fun decodeEntities(value: String): String {
    var current = value
    repeat(MAX_DECODE_PASSES) {
        if ('&' !in current) return current
        val next = entityPattern.replace(current) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") -> codePoint(body.substring(2).toInt(16))
                body.startsWith("#") -> codePoint(body.substring(1).toInt())
                else -> htmlEntities[body]
            } ?: match.value
        }
        if (next == current) return current
        current = next
    }
    return current
}

private fun codePoint(value: Int): String? =
    if (value in 1..0x10FFFF && value !in 0xD800..0xDFFF) String(Character.toChars(value)) else null

public fun stripInvalidXmlChars(value: String): String {
    if (value.all { isValidXmlChar(it) }) return value
    val builder = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (Character.isHighSurrogate(char) && index + 1 < value.length && Character.isLowSurrogate(value[index + 1])) {
            builder.append(char).append(value[index + 1])
            index += 2
            continue
        }
        if (isValidXmlChar(char) && !Character.isSurrogate(char)) builder.append(char)
        index++
    }
    return builder.toString()
}

private fun isValidXmlChar(char: Char): Boolean =
    char == '\t' || char == '\n' || char == '\r' || char in ' '..'\uFFFD'
