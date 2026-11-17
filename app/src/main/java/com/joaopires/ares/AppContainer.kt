// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares

import android.content.Context
import android.util.Xml
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.joaopires.ares.core.data.ArticleRepository
import com.joaopires.ares.core.data.EntryRepository
import com.joaopires.ares.core.data.FeedClientSource
import com.joaopires.ares.core.data.FeedRepository
import com.joaopires.ares.core.data.FeedSource
import com.joaopires.ares.core.data.FilterRepository
import com.joaopires.ares.core.data.RefreshManager
import com.joaopires.ares.core.data.SettingsRepository
import com.joaopires.ares.core.data.db.AresDatabase
import com.joaopires.ares.core.feed.FeedClient
import com.joaopires.ares.core.feed.FeedClientConfig
import com.joaopires.ares.ui.images.AresImageInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.File
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean

private const val IMAGE_CACHE_BYTES = 100L * 1024 * 1024

class AppContainer(
    context: Context,
    createDatabase: (Context) -> AresDatabase = AresDatabase::create,
    createFeedSource: (FeedClient) -> FeedSource = ::FeedClientSource,
    settingsFile: File? = null,
    val clock: Clock = Clock.systemUTC(),
) {
    private val appContext = context.applicationContext

    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val userAgent = FeedClientConfig(appVersion = BuildConfig.VERSION_NAME).userAgent

    val database: AresDatabase by lazy { createDatabase(appContext) }

    val settings: SettingsRepository by lazy {
        SettingsRepository.create(
            settingsFile ?: File(appContext.filesDir, "datastore/settings.preferences_pb"),
            applicationScope,
        )
    }

    val feedClient: FeedClient by lazy {
        FeedClient(FeedClientConfig(appVersion = BuildConfig.VERSION_NAME), { Xml.newPullParser() }, clock)
    }

    val feedSource: FeedSource by lazy { createFeedSource(feedClient) }

    val feeds: FeedRepository by lazy { FeedRepository(database, clock) }

    val entries: EntryRepository by lazy { EntryRepository(database, clock) }

    val filters: FilterRepository by lazy { FilterRepository(database) }

    val articles: ArticleRepository by lazy { ArticleRepository(database, feedSource) }

    val refresh: RefreshManager by lazy { RefreshManager(database, feedSource, settings, clock) }

    private val allowYouTubeImages = AtomicBoolean(true)

    val imageLoader: ImageLoader by lazy {
        applicationScope.launch {
            settings.settings.collect { allowYouTubeImages.set(it.loadYouTubeImages) }
        }
        val client = OkHttpClient.Builder()
            .followSslRedirects(false)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build()) }
            .build()
        ImageLoader.Builder(appContext)
            .components {
                add(AresImageInterceptor { allowYouTubeImages.get() })
                add(OkHttpNetworkFetcherFactory(callFactory = { client }))
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(appContext.cacheDir, "images").toOkioPath())
                    .maxSizeBytes(IMAGE_CACHE_BYTES)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
