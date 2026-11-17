// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data

import androidx.room.withTransaction
import com.joaopires.ares.core.data.db.AresDatabase
import com.joaopires.ares.core.data.db.FeedEntity
import com.joaopires.ares.core.data.db.FeedStatus
import com.joaopires.ares.core.data.model.NewEntry
import com.joaopires.ares.core.feed.FetchFailure
import com.joaopires.ares.core.feed.FetchOutcome
import com.joaopires.ares.core.feed.Validators
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

public sealed interface RefreshProgress {
    public data object Idle : RefreshProgress
    public data class Running(val done: Int, val total: Int) : RefreshProgress
}

public data class RefreshSummary(val refreshed: Int, val failed: Int, val newEntries: List<NewEntry>)

public class RefreshManager(
    private val db: AresDatabase,
    private val source: FeedSource,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val maxConcurrent: Int = 6,
    private val maxPerHost: Int = 2,
    private val minInterval: Duration = Duration.ofMinutes(5),
    private val log: EventLog = EventLog.None,
) {
    private val running = Mutex()
    private val progressState = MutableStateFlow<RefreshProgress>(RefreshProgress.Idle)

    public val progress: StateFlow<RefreshProgress> = progressState.asStateFlow()

    public suspend fun refreshAll(force: Boolean = true): RefreshSummary = running.withLock {
        val now = clock.instant()
        val feeds = db.feedDao().all().filter { feed ->
            feed.status == FeedStatus.Active && (force || feed.lastFetchedAt?.let { Duration.between(it, now) >= minInterval } != false)
        }
        try {
            progressState.value = RefreshProgress.Running(0, feeds.size)
            val results = refreshConcurrently(feeds)
            val removed = applyRetention(db, settings.current().retentionDays, clock.instant())
            summarize(results).also { summary ->
                log.log(
                    LogLevel.Info,
                    "Refreshed ${summary.refreshed} of ${feeds.size} feeds, ${summary.failed} failed, " +
                        "${summary.newEntries.size} new entries, $removed old entries removed",
                )
            }
        } finally {
            progressState.value = RefreshProgress.Idle
        }
    }

    public suspend fun refreshFeed(feedId: Long): RefreshSummary {
        val feed = db.feedDao().byId(feedId) ?: return RefreshSummary(0, 0, emptyList())
        return summarize(listOf(refreshOne(feed)))
    }

    private suspend fun refreshConcurrently(feeds: List<FeedEntity>): List<FeedResult> = coroutineScope {
        val global = Semaphore(maxConcurrent)
        val perHost = ConcurrentHashMap<String, Semaphore>()
        feeds.map { feed ->
            async {
                val hostLimit = perHost.computeIfAbsent(hostKey(feed.url)) { Semaphore(maxPerHost) }
                global.withPermit {
                    hostLimit.withPermit { refreshOne(feed) }
                }.also {
                    progressState.update { state ->
                        if (state is RefreshProgress.Running) state.copy(done = state.done + 1) else state
                    }
                }
            }
        }.awaitAll()
    }

    private suspend fun refreshOne(feed: FeedEntity): FeedResult {
        val outcome = try {
            source.fetch(feed.url, Validators(feed.etag, feed.lastModified), feed.entryKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log(LogLevel.Error, "Refresh of ${domainForLog(feed.url)} crashed: ${e.javaClass.simpleName}")
            FetchOutcome.Failed(FetchFailure.Network)
        }
        when (outcome) {
            FetchOutcome.Gone -> log.log(LogLevel.Warning, "Feed at ${domainForLog(feed.url)} is gone")
            is FetchOutcome.Failed -> log.log(LogLevel.Warning, "Refresh of ${domainForLog(feed.url)} failed: ${outcome.failure.code()}")
            else -> Unit
        }
        val now = clock.instant()
        return db.withTransaction {
            val current = db.feedDao().byId(feed.id) ?: return@withTransaction FeedResult(success = false, newEntries = emptyList())
            when (outcome) {
                is FetchOutcome.Updated -> {
                    val updated = succeeded(current, outcome.newUrl, now).copy(
                        title = outcome.feed.title ?: current.title,
                        siteUrl = outcome.feed.siteUrl ?: current.siteUrl,
                        iconUrl = outcome.feed.iconUrl ?: current.iconUrl,
                        description = outcome.feed.description ?: current.description,
                        etag = outcome.validators.etag,
                        lastModified = outcome.validators.lastModified,
                    )
                    db.feedDao().update(updated)
                    FeedResult(success = true, newEntries = writeEntries(db, updated, outcome.feed.entries, now))
                }
                is FetchOutcome.NotModified -> {
                    db.feedDao().update(succeeded(current, outcome.newUrl, now))
                    FeedResult(success = true, newEntries = emptyList())
                }
                FetchOutcome.Gone -> {
                    db.feedDao().update(failed(current, "Gone", now).copy(status = FeedStatus.Gone))
                    FeedResult(success = false, newEntries = emptyList())
                }
                is FetchOutcome.Failed -> {
                    db.feedDao().update(failed(current, outcome.failure.code(), now))
                    FeedResult(success = false, newEntries = emptyList())
                }
            }
        }
    }

    private suspend fun succeeded(feed: FeedEntity, newUrl: String?, now: Instant): FeedEntity {
        val url = newUrl?.takeIf { it != feed.url && db.feedDao().byUrl(it) == null } ?: feed.url
        return feed.copy(
            url = url,
            lastFetchedAt = now,
            lastSuccessAt = now,
            lastError = null,
            consecutiveFailures = 0,
            status = FeedStatus.Active,
        )
    }

    private fun failed(feed: FeedEntity, error: String, now: Instant): FeedEntity =
        feed.copy(lastFetchedAt = now, lastError = error, consecutiveFailures = feed.consecutiveFailures + 1)

    private fun summarize(results: List<FeedResult>) = RefreshSummary(
        refreshed = results.count { it.success },
        failed = results.count { !it.success },
        newEntries = results.flatMap { it.newEntries },
    )

    private data class FeedResult(val success: Boolean, val newEntries: List<NewEntry>)
}

internal fun FetchFailure.code(): String = when (this) {
    FetchFailure.Network -> "Network"
    FetchFailure.Timeout -> "Timeout"
    is FetchFailure.Http -> "Http:$code"
    FetchFailure.TooLarge -> "TooLarge"
    FetchFailure.NotAFeed -> "NotAFeed"
    FetchFailure.Malformed -> "Malformed"
    FetchFailure.InsecureOnly -> "InsecureOnly"
}
