// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.joaopires.ares.AppContainer
import com.joaopires.ares.AresApplication
import com.joaopires.ares.core.data.EntryRepository
import com.joaopires.ares.core.data.RefreshManager
import com.joaopires.ares.core.data.SettingsRepository
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

const val BACKGROUND_REFRESH_WORK = "background-refresh"

class RefreshWorker(
    context: Context,
    params: WorkerParameters,
    private val refresh: RefreshManager,
    private val settings: SettingsRepository,
    private val notifier: Notifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val summary = refresh.refreshAll(force = false)
        if (settings.current().notificationsEnabled) notifier.post(summary.newEntries.filter { it.notify })
        return Result.success()
    }
}

class AresWorkerFactory(private val container: () -> AppContainer) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        when (workerClassName) {
            RefreshWorker::class.java.name -> container().let {
                RefreshWorker(appContext, workerParameters, it.refresh, it.settings, it.notifier)
            }
            else -> null
        }
}

class BackgroundRefreshScheduler(private val workManager: () -> WorkManager) {
    fun apply(intervalMinutes: Int?) {
        if (intervalMinutes == null) {
            workManager().cancelUniqueWork(BACKGROUND_REFRESH_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(intervalMinutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        workManager().enqueueUniquePeriodicWork(BACKGROUND_REFRESH_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

suspend fun handleNotificationAction(action: String?, entryId: String, entries: EntryRepository, notifier: Notifier) {
    when (action) {
        ACTION_MARK_READ -> entries.setRead(entryId, true)
        ACTION_BOOKMARK -> entries.setBookmarked(entryId, true)
        else -> return
    }
    notifier.cancel(listOf(entryId))
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID) ?: return
        val container = (context.applicationContext as? AresApplication)?.container ?: return
        val pending = goAsync()
        container.applicationScope.launch {
            try {
                handleNotificationAction(intent.action, entryId, container.entries, container.notifier)
            } finally {
                pending.finish()
            }
        }
    }
}
