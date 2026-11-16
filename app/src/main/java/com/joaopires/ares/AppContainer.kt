// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares

import android.content.Context
import android.util.Xml
import com.joaopires.ares.core.data.EntryRepository
import com.joaopires.ares.core.data.FeedClientSource
import com.joaopires.ares.core.data.FeedRepository
import com.joaopires.ares.core.data.FilterRepository
import com.joaopires.ares.core.data.RefreshManager
import com.joaopires.ares.core.data.SettingsRepository
import com.joaopires.ares.core.data.db.AresDatabase
import com.joaopires.ares.core.feed.FeedClient
import com.joaopires.ares.core.feed.FeedClientConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.time.Clock

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val clock: Clock = Clock.systemUTC()

    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AresDatabase by lazy { AresDatabase.create(appContext) }

    val settings: SettingsRepository by lazy {
        SettingsRepository.create(File(appContext.filesDir, "datastore/settings.preferences_pb"), applicationScope)
    }

    val feedClient: FeedClient by lazy {
        FeedClient(FeedClientConfig(appVersion = BuildConfig.VERSION_NAME), { Xml.newPullParser() }, clock)
    }

    val feeds: FeedRepository by lazy { FeedRepository(database, clock) }

    val entries: EntryRepository by lazy { EntryRepository(database, clock) }

    val filters: FilterRepository by lazy { FilterRepository(database) }

    val refresh: RefreshManager by lazy { RefreshManager(database, FeedClientSource(feedClient), settings, clock) }
}
