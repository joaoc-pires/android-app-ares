// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joaopires.ares.core.data.EntryRepository
import com.joaopires.ares.core.data.FeedRepository
import com.joaopires.ares.core.data.FeedSource
import com.joaopires.ares.core.data.RefreshManager
import com.joaopires.ares.core.data.RefreshProgress
import com.joaopires.ares.core.data.SettingsRepository
import com.joaopires.ares.core.data.SubscribeResult
import com.joaopires.ares.core.data.model.EntryScope
import com.joaopires.ares.core.data.model.FeedSummary
import com.joaopires.ares.core.data.model.HomeCounts
import com.joaopires.ares.core.feed.DiscoveredFeed
import com.joaopires.ares.core.feed.DiscoveryOutcome
import com.joaopires.ares.core.feed.Feed
import com.joaopires.ares.core.feed.FetchFailure
import com.joaopires.ares.core.feed.FetchOutcome
import com.joaopires.ares.core.feed.Validators
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FeedRowUi(
    val id: Long,
    val title: String,
    val url: String,
    val siteUrl: String?,
    val iconUrl: String?,
    val unread: Int,
    val error: String?,
)

data class SectionUi(val id: Long, val name: String, val feeds: List<FeedRowUi>) {
    val unread: Int get() = feeds.sumOf { it.unread }
}

data class HomeUiState(
    val loaded: Boolean = false,
    val counts: HomeCounts = HomeCounts(0, 0, 0),
    val unsectioned: List<FeedRowUi> = emptyList(),
    val sections: List<SectionUi> = emptyList(),
    val hideUnread: Boolean = false,
    val refreshing: Boolean = false,
) {
    val isEmpty: Boolean get() = loaded && unsectioned.isEmpty() && sections.isEmpty()
}

sealed interface AddFeedState {
    data object Hidden : AddFeedState
    data class Editing(val url: String = "", val error: AddFeedError? = null) : AddFeedState
    data class Loading(val url: String) : AddFeedState
    data class Choosing(val url: String, val candidates: List<DiscoveredFeed>) : AddFeedState
}

sealed interface AddFeedError {
    data object InvalidUrl : AddFeedError
    data class Fetch(val failure: FetchFailure) : AddFeedError
}

sealed interface HomeMessage {
    data class RefreshFailed(val count: Int) : HomeMessage
    data class MarkedRead(val entryIds: List<String>) : HomeMessage
    data class FeedDeleted(val title: String) : HomeMessage
    data class Subscribed(val title: String) : HomeMessage
    data object AlreadySubscribed : HomeMessage
}

class HomeViewModel(
    private val feeds: FeedRepository,
    private val entries: EntryRepository,
    settings: SettingsRepository,
    private val refresh: RefreshManager,
    private val source: FeedSource,
) : ViewModel() {

    val state: StateFlow<HomeUiState> = combine(
        feeds.feeds,
        feeds.sections,
        entries.counts(),
        settings.settings,
        refresh.progress,
    ) { feedList, sections, counts, appSettings, progress ->
        val rows = feedList.map { it.toRow() }
        val bySection = feedList.zip(rows).groupBy({ it.first.sectionId }, { it.second })
        HomeUiState(
            loaded = true,
            counts = counts,
            unsectioned = bySection[null].orEmpty(),
            sections = sections.map { SectionUi(it.id, it.name, bySection[it.id].orEmpty()) },
            hideUnread = appSettings.hideUnreadMarkers,
            refreshing = progress is RefreshProgress.Running,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private val addFeedState = MutableStateFlow<AddFeedState>(AddFeedState.Hidden)
    val addFeed: StateFlow<AddFeedState> = addFeedState.asStateFlow()

    private val messageChannel = Channel<HomeMessage>(Channel.BUFFERED)
    val messages: Flow<HomeMessage> = messageChannel.receiveAsFlow()

    fun refresh() {
        viewModelScope.launch {
            val summary = refresh.refreshAll(force = true)
            if (summary.failed > 0) messageChannel.send(HomeMessage.RefreshFailed(summary.failed))
        }
    }

    fun markFeedRead(feedId: Long) {
        viewModelScope.launch {
            messageChannel.send(HomeMessage.MarkedRead(entries.markAllRead(EntryScope.Feed(feedId))))
        }
    }

    fun undoMarkRead(entryIds: List<String>) {
        viewModelScope.launch { entries.markUnread(entryIds) }
    }

    fun deleteFeed(feed: FeedRowUi) {
        viewModelScope.launch {
            feeds.deleteFeed(feed.id)
            messageChannel.send(HomeMessage.FeedDeleted(feed.title))
        }
    }

    fun showAddFeed() {
        addFeedState.value = AddFeedState.Editing()
    }

    fun dismissAddFeed() {
        addFeedState.value = AddFeedState.Hidden
    }

    fun onAddFeedUrlChanged(url: String) {
        addFeedState.value = AddFeedState.Editing(url)
    }

    fun submitAddFeed() {
        val current = addFeedState.value as? AddFeedState.Editing ?: return
        val url = normalizeFeedInput(current.url)
        if (url == null) {
            addFeedState.value = current.copy(error = AddFeedError.InvalidUrl)
            return
        }
        addFeedState.value = AddFeedState.Loading(current.url)
        viewModelScope.launch {
            guarded(onError = { addFeedState.value = current.copy(error = AddFeedError.Fetch(FetchFailure.Malformed)) }) {
                when (val outcome = source.discover(url)) {
                    is DiscoveryOutcome.Found -> subscribe(outcome.feed, outcome.validators)
                    is DiscoveryOutcome.Candidates -> addFeedState.value = AddFeedState.Choosing(current.url, outcome.feeds)
                    DiscoveryOutcome.NothingFound -> addFeedState.value = current.copy(error = AddFeedError.Fetch(FetchFailure.NotAFeed))
                    is DiscoveryOutcome.Failed -> addFeedState.value = current.copy(error = AddFeedError.Fetch(outcome.failure))
                }
            }
        }
    }

    fun chooseCandidate(candidate: DiscoveredFeed) {
        val current = addFeedState.value as? AddFeedState.Choosing ?: return
        addFeedState.value = AddFeedState.Loading(current.url)
        viewModelScope.launch {
            guarded(onError = { addFeedState.value = AddFeedState.Editing(current.url, AddFeedError.Fetch(FetchFailure.Malformed)) }) {
                when (val outcome = source.fetch(candidate.url, null, candidate.url)) {
                    is FetchOutcome.Updated -> subscribe(outcome.feed, outcome.validators)
                    is FetchOutcome.Failed -> addFeedState.value = AddFeedState.Editing(current.url, AddFeedError.Fetch(outcome.failure))
                    FetchOutcome.Gone -> addFeedState.value = AddFeedState.Editing(current.url, AddFeedError.Fetch(FetchFailure.Http(410)))
                    is FetchOutcome.NotModified -> addFeedState.value = AddFeedState.Editing(current.url, AddFeedError.Fetch(FetchFailure.NotAFeed))
                }
            }
        }
    }

    private suspend fun guarded(onError: () -> Unit, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            onError()
        }
    }

    private suspend fun subscribe(feed: Feed, validators: Validators) {
        val result = feeds.subscribe(feed, validators)
        addFeedState.value = AddFeedState.Hidden
        messageChannel.send(
            when (result) {
                is SubscribeResult.Subscribed -> HomeMessage.Subscribed(feed.title ?: feed.url)
                is SubscribeResult.AlreadySubscribed -> HomeMessage.AlreadySubscribed
            },
        )
    }

    private fun FeedSummary.toRow() = FeedRowUi(id, title, url, siteUrl, iconUrl, unreadCount, lastError)
}

fun normalizeFeedInput(input: String): String? {
    var url = input.trim()
    if (url.isEmpty() || url.any { it.isWhitespace() }) return null
    url = when {
        url.startsWith("feed://", ignoreCase = true) -> "https://" + url.substring(7)
        url.startsWith("feeds://", ignoreCase = true) -> "https://" + url.substring(8)
        url.startsWith("feed:", ignoreCase = true) -> url.substring(5)
        else -> url
    }
    if (!url.contains("://")) url = "https://$url"
    if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) return null
    val host = try {
        java.net.URI(url).host
    } catch (_: Exception) {
        null
    }
    return if (host.isNullOrBlank() || !host.contains('.')) null else url
}
