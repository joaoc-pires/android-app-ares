// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.joaopires.ares.core.data.FeedSource
import com.joaopires.ares.core.data.SubscribeResult
import com.joaopires.ares.core.data.db.AresDatabase
import com.joaopires.ares.core.feed.DiscoveryOutcome
import com.joaopires.ares.core.feed.Entry
import com.joaopires.ares.core.feed.Feed
import com.joaopires.ares.core.feed.FetchOutcome
import com.joaopires.ares.core.feed.Validators
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

val NOW: Instant = Instant.parse("2026-11-16T12:00:00Z")

class FakeFeedSource(
    var onFetch: suspend (String) -> FetchOutcome = { FetchOutcome.NotModified(null) },
    var onDiscover: suspend (String) -> DiscoveryOutcome = { DiscoveryOutcome.NothingFound },
) : FeedSource {
    override suspend fun fetch(url: String, validators: Validators?, feedKey: String): FetchOutcome = onFetch(url)
    override suspend fun discover(url: String): DiscoveryOutcome = onDiscover(url)
}

@OptIn(DelicateCoilApi::class)
fun testContainer(source: FakeFeedSource = FakeFeedSource()): AppContainer {
    val context = ApplicationProvider.getApplicationContext<Context>()
    SingletonImageLoader.setUnsafe(
        ImageLoader.Builder(context).components { add(NoImages) }.build(),
    )
    val settingsFile = File(Files.createTempDirectory("ares-test").toFile(), "settings.preferences_pb")
    return AppContainer(
        context = context,
        createDatabase = {
            Room.inMemoryDatabaseBuilder(it, AresDatabase::class.java).allowMainThreadQueries().build()
        },
        createFeedSource = { source },
        settingsFile = settingsFile,
        clock = Clock.fixed(NOW, ZoneOffset.UTC),
    )
}

private object NoImages : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
        ErrorResult(image = null, request = chain.request, throwable = IllegalStateException("images disabled in tests"))
}

fun entry(id: String, title: String = "Title $id", publishedAt: Instant? = NOW, text: String = "Body of $id") = Entry(
    id = id,
    url = "https://site.example/$id",
    title = title,
    author = "Author $id",
    contentHtml = "<p>$text</p>",
    contentText = text,
    excerpt = text,
    publishedAt = publishedAt,
    updatedAt = null,
    thumbnail = null,
    videoId = null,
    enclosures = emptyList(),
)

fun feed(url: String, vararg entries: Entry, title: String = "Feed $url") =
    Feed(url = url, title = title, siteUrl = url, description = null, iconUrl = null, entries = entries.toList())

suspend fun AppContainer.subscribe(feed: Feed, sectionId: Long? = null): Long =
    (feeds.subscribe(feed, Validators(null, null), sectionId = sectionId) as SubscribeResult.Subscribed).feedId
