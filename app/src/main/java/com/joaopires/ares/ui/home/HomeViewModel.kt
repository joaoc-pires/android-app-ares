// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joaopires.ares.core.data.EntryRepository
import com.joaopires.ares.core.data.FeedRepository
import com.joaopires.ares.core.data.ImportProgress
import com.joaopires.ares.core.data.ImportSummary
import com.joaopires.ares.core.data.RefreshManager
import com.joaopires.ares.core.data.RefreshProgress
import com.joaopires.ares.core.data.SettingsRepository
import com.joaopires.ares.core.data.model.EntryScope
import com.joaopires.ares.core.data.model.FeedSummary
import com.joaopires.ares.core.data.model.HomeCounts
import com.joaopires.ares.ui.opml.ImportController
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    val importing: ImportProgress.Running? = null,
    val importSummary: ImportSummary? = null,
) {
    val isEmpty: Boolean get() = loaded && unsectioned.isEmpty() && sections.isEmpty()
}

sealed interface HomeMessage {
    data class RefreshFailed(val count: Int) : HomeMessage
    data class MarkedRead(val entryIds: List<String>) : HomeMessage
    data class FeedDeleted(val title: String) : HomeMessage
    data class SectionDeleted(val name: String) : HomeMessage
    data class Subscribed(val title: String) : HomeMessage
    data object AlreadySubscribed : HomeMessage
}

class HomeViewModel(
    private val feeds: FeedRepository,
    private val entries: EntryRepository,
    settings: SettingsRepository,
    private val refresh: RefreshManager,
    private val imports: ImportController,
) : ViewModel() {

    private val library = combine(
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
    }

    val state: StateFlow<HomeUiState> = combine(library, imports.progress, imports.summary) { home, progress, summary ->
        home.copy(importing = progress as? ImportProgress.Running, importSummary = summary)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

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

    fun deleteSection(section: SectionUi) {
        viewModelScope.launch {
            feeds.deleteSection(section.id)
            messageChannel.send(HomeMessage.SectionDeleted(section.name))
        }
    }

    fun dismissImportSummary() = imports.clearSummary()

    fun onFeedAdded(title: String?, alreadySubscribed: Boolean) {
        viewModelScope.launch {
            messageChannel.send(if (alreadySubscribed) HomeMessage.AlreadySubscribed else HomeMessage.Subscribed(title.orEmpty()))
        }
    }

    private fun FeedSummary.toRow() = FeedRowUi(id, title, url, siteUrl, iconUrl, unreadCount, lastError)
}
