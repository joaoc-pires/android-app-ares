// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares

import android.content.Context
import android.util.Xml
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.joaopires.ares.core.data.ArticleRepository
import com.joaopires.ares.core.data.EntryEvents
import com.joaopires.ares.core.data.EntryRepository
import com.joaopires.ares.core.data.FeedClientSource
import com.joaopires.ares.core.data.FeedImporter
import com.joaopires.ares.core.data.FeedRepository
import com.joaopires.ares.core.data.FeedSource
import com.joaopires.ares.core.data.FileEventLog
import com.joaopires.ares.core.data.FilterRepository
import com.joaopires.ares.core.data.RefreshManager
import com.joaopires.ares.core.data.SettingsRepository
import com.joaopires.ares.core.data.db.AresDatabase
import com.joaopires.ares.core.feed.FeedClient
import com.joaopires.ares.core.feed.FeedClientConfig
import com.joaopires.ares.notifications.BackgroundRefreshScheduler
import com.joaopires.ares.notifications.Notifier
import com.joaopires.ares.ui.images.AresImageInterceptor
import com.joaopires.ares.ui.opml.ImportController
import com.joaopires.ares.ui.opml.OpmlService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.File
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean

private const val IMAGE_CACHE_BYTES = 100L * 1024 * 1024
private const val PARSING_THREADS = 2

class AppContainer(
    context: Context,
    createDatabase: (Context) -> AresDatabase = AresDatabase::create,
    createFeedSource: (FeedClient) -> FeedSource = ::FeedClientSource,
    settingsFile: File? = null,
    logDirectory: File? = null,
    val clock: Clock = Clock.systemUTC(),
) {
    private val appContext = context.applicationContext

    val cacheDirectory: File get() = appContext.cacheDir

    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val userAgent = FeedClientConfig(appVersion = BuildConfig.VERSION_NAME).userAgent

    val database: AresDatabase by lazy { createDatabase(appContext) }

    val settings: SettingsRepository by lazy {
        SettingsRepository.create(
            settingsFile ?: File(appContext.filesDir, "datastore/settings.preferences_pb"),
            applicationScope,
        )
    }

    val eventLog: FileEventLog by lazy { FileEventLog(logDirectory ?: File(appContext.filesDir, "logs"), clock) }

    private val parsingDispatcher: CoroutineDispatcher by lazy { backgroundDispatcher("ares-parse", PARSING_THREADS) }

    val feedClient: FeedClient by lazy {
        FeedClient(FeedClientConfig(appVersion = BuildConfig.VERSION_NAME), { Xml.newPullParser() }, clock, parsingDispatcher)
    }

    val feedSource: FeedSource by lazy { createFeedSource(feedClient) }

    val entryEvents: EntryEvents = EntryEvents()

    val feeds: FeedRepository by lazy { FeedRepository(database, clock, entryEvents) }

    val entries: EntryRepository by lazy { EntryRepository(database, clock, entryEvents) }

    val notifier: Notifier by lazy { Notifier(appContext, entries) }

    val workManager: WorkManager by lazy { WorkManager.getInstance(appContext) }

    val backgroundRefresh: BackgroundRefreshScheduler by lazy { BackgroundRefreshScheduler { workManager } }

    fun startBackgroundWork() {
        notifier.ensureChannel()
        notifier.listen(applicationScope, entryEvents)
        applicationScope.launch {
            settings.settings.map { it.backgroundRefreshMinutes }.distinctUntilChanged().collect(backgroundRefresh::apply)
        }
        applicationScope.launch {
            settings.settings.map { it.notificationsEnabled }.distinctUntilChanged().collect { enabled ->
                if (!enabled) notifier.cancelAll()
            }
        }
    }

    val filters: FilterRepository by lazy { FilterRepository(database) }

    val articles: ArticleRepository by lazy { ArticleRepository(database, feedSource, eventLog) }

    val imports: ImportController by lazy { ImportController(FeedImporter(feeds, feedSource, log = eventLog), applicationScope) }

    val opml: OpmlService by lazy { OpmlService(appContext.contentResolver, feeds, clock) }

    val refresh: RefreshManager by lazy { RefreshManager(database, feedSource, settings, clock, log = eventLog) }

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
