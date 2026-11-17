// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data

import com.joaopires.ares.core.feed.FetchFailure
import com.joaopires.ares.core.feed.FetchOutcome
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
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

public data class ImportCandidate(val url: String, val title: String?, val section: String?)

public data class ImportFailure(val url: String, val title: String?, val failure: FetchFailure)

public data class ImportSummary(val imported: Int, val alreadySubscribed: Int, val failures: List<ImportFailure>)

public sealed interface ImportProgress {
    public data object Idle : ImportProgress
    public data class Running(val done: Int, val total: Int) : ImportProgress
}

public class FeedImporter(
    private val feeds: FeedRepository,
    private val source: FeedSource,
    private val maxConcurrent: Int = 6,
    private val maxPerHost: Int = 2,
) {
    private val running = Mutex()
    private val progressState = MutableStateFlow<ImportProgress>(ImportProgress.Idle)

    public val progress: StateFlow<ImportProgress> = progressState.asStateFlow()

    public suspend fun import(candidates: List<ImportCandidate>): ImportSummary = running.withLock {
        val unique = candidates.distinctBy { it.url.trim() }
        try {
            progressState.value = ImportProgress.Running(0, unique.size)
            val sectionIds = unique.mapNotNull { it.section?.trim()?.ifEmpty { null } }
                .distinctBy { it.lowercase() }
                .associate { name -> name.lowercase() to feeds.ensureSection(name) }
            val global = Semaphore(maxConcurrent)
            val perHost = ConcurrentHashMap<String, Semaphore>()
            val results = coroutineScope {
                unique.map { candidate ->
                    async {
                        val hostLimit = perHost.computeIfAbsent(hostOf(candidate.url)) { Semaphore(maxPerHost) }
                        global.withPermit {
                            hostLimit.withPermit {
                                importOne(candidate, candidate.section?.trim()?.lowercase()?.let { sectionIds[it] })
                            }
                        }.also {
                            progressState.update { state ->
                                if (state is ImportProgress.Running) state.copy(done = state.done + 1) else state
                            }
                        }
                    }
                }.awaitAll()
            }
            ImportSummary(
                imported = results.count { it is Result.Imported },
                alreadySubscribed = results.count { it is Result.Already },
                failures = results.filterIsInstance<Result.Failed>().map { it.failure },
            )
        } finally {
            progressState.value = ImportProgress.Idle
        }
    }

    private suspend fun importOne(candidate: ImportCandidate, sectionId: Long?): Result {
        val url = candidate.url.trim()
        val outcome = try {
            source.fetch(url, null, url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            FetchOutcome.Failed(FetchFailure.Network)
        }
        return when (outcome) {
            is FetchOutcome.Updated -> {
                val title = candidate.title?.trim()?.ifEmpty { null }?.takeIf { it != outcome.feed.title }
                when (feeds.subscribe(outcome.feed, outcome.validators, customTitle = title, sectionId = sectionId)) {
                    is SubscribeResult.Subscribed -> Result.Imported
                    is SubscribeResult.AlreadySubscribed -> Result.Already
                }
            }
            is FetchOutcome.Failed -> Result.Failed(ImportFailure(url, candidate.title, outcome.failure))
            FetchOutcome.Gone -> Result.Failed(ImportFailure(url, candidate.title, FetchFailure.Http(410)))
            is FetchOutcome.NotModified -> Result.Failed(ImportFailure(url, candidate.title, FetchFailure.NotAFeed))
        }
    }

    private sealed interface Result {
        data object Imported : Result
        data object Already : Result
        data class Failed(val failure: ImportFailure) : Result
    }
}

private fun hostOf(url: String): String = try {
    URI(url).host?.lowercase() ?: url
} catch (_: Exception) {
    url
}
