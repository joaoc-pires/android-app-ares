// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.feed

import java.time.Duration
import java.time.Instant

public data class Feed(
    val url: String,
    val title: String?,
    val siteUrl: String?,
    val description: String?,
    val iconUrl: String?,
    val entries: List<Entry>,
)

public data class Entry(
    val id: String,
    val url: String?,
    val title: String?,
    val author: String?,
    val contentHtml: String?,
    val excerpt: String?,
    val publishedAt: Instant?,
    val updatedAt: Instant?,
    val thumbnail: Thumbnail?,
    val videoId: String?,
    val enclosures: List<Enclosure>,
)

public data class Thumbnail(val url: String, val width: Int?, val height: Int?)

public data class Enclosure(val url: String, val type: String?, val length: Long?)

public data class Validators(val etag: String?, val lastModified: String?)

public sealed interface FetchOutcome {
    public data class Updated(val feed: Feed, val validators: Validators, val newUrl: String?) : FetchOutcome
    public data class NotModified(val newUrl: String?) : FetchOutcome
    public data object Gone : FetchOutcome
    public data class Failed(val failure: FetchFailure) : FetchOutcome
}

public sealed interface FetchFailure {
    public data object Network : FetchFailure
    public data object Timeout : FetchFailure
    public data class Http(val code: Int) : FetchFailure
    public data object TooLarge : FetchFailure
    public data object NotAFeed : FetchFailure
    public data object Malformed : FetchFailure
    public data object InsecureOnly : FetchFailure
}

public data class DiscoveredFeed(val url: String, val title: String?)

public sealed interface DiscoveryOutcome {
    public data class Found(val feed: Feed, val validators: Validators) : DiscoveryOutcome
    public data class Candidates(val feeds: List<DiscoveredFeed>) : DiscoveryOutcome
    public data object NothingFound : DiscoveryOutcome
    public data class Failed(val failure: FetchFailure) : DiscoveryOutcome
}

public data class FeedClientConfig(
    val appVersion: String,
    val connectTimeout: Duration = Duration.ofSeconds(15),
    val readTimeout: Duration = Duration.ofSeconds(30),
    val callTimeout: Duration = Duration.ofSeconds(60),
    val maxBytes: Long = 20L * 1024 * 1024,
    val maxRedirects: Int = 5,
) {
    val userAgent: String get() = "Ares/$appVersion (+https://joaopires.com/ares)"
}
