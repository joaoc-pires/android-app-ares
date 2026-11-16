// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.opml

public sealed class OpmlException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    public class NotOpml : OpmlException("The document is not an OPML file")

    public class Malformed(
        public val line: Int,
        public val column: Int,
        reason: String,
        cause: Throwable? = null,
    ) : OpmlException("Malformed OPML at line $line, column $column: $reason", cause)

    public class TooLarge(public val limit: Limit) : OpmlException("The OPML file exceeds the ${limit.description} limit") {
        public enum class Limit(internal val description: String) {
            Bytes("file size"),
            Depth("nesting depth"),
            Outlines("number of outlines"),
        }
    }
}

public data class OpmlLimits(
    val maxBytes: Long = 10L * 1024 * 1024,
    val maxDepth: Int = 64,
    val maxOutlines: Int = 20_000,
)
