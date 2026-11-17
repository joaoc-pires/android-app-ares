// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.feed

import com.joaopires.ares.core.feed.normalize.normalize
import com.joaopires.ares.core.feed.parse.FeedParseException
import com.joaopires.ares.core.feed.parse.parseRawFeed
import org.xmlpull.v1.XmlPullParser
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

public class FeedParseError(public val failure: FetchFailure, cause: Throwable? = null) :
    Exception("feed could not be parsed: $failure", cause)

public fun parseFeedDocument(
    bytes: ByteArray,
    url: String,
    newParser: () -> XmlPullParser,
    now: Instant,
    charset: String? = null,
    documentUrl: String = url,
    feedKey: String = url,
): Feed = try {
    normalize(parseRawFeed(bytes, charset, newParser), url, documentUrl, feedKey, now)
} catch (e: FeedParseException) {
    throw FeedParseError(e.failure, e)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw FeedParseError(FetchFailure.Malformed, e)
}
