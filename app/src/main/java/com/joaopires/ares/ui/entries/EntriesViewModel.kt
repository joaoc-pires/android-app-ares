// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.entries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.joaopires.ares.core.data.ArticleOpener
import com.joaopires.ares.core.data.EntryRepository
import com.joaopires.ares.core.data.RefreshManager
import com.joaopires.ares.core.data.RefreshProgress
import com.joaopires.ares.core.data.SettingsRepository
import com.joaopires.ares.core.data.model.EntryListItem
import com.joaopires.ares.core.data.model.EntryQuery
import com.joaopires.ares.core.data.model.EntryScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface EntriesMessage {
    data class MarkedRead(val entryIds: List<String>) : EntriesMessage
    data class RefreshFailed(val count: Int) : EntriesMessage
}

class EntriesViewModel(
    val scope: EntryScope,
    private val entries: EntryRepository,
    settings: SettingsRepository,
    private val refresh: RefreshManager,
) : ViewModel() {

    private val unreadOnlyState = MutableStateFlow(false)
    val unreadOnly: StateFlow<Boolean> = unreadOnlyState.asStateFlow()

    val canFilterUnread: Boolean = scope != EntryScope.Unread

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: Flow<PagingData<EntryListItem>> = unreadOnlyState
        .flatMapLatest { entries.entries(EntryQuery(scope, unreadOnly = it)) }
        .cachedIn(viewModelScope)

    val hideUnread: StateFlow<Boolean> = settings.settings.map { it.hideUnreadMarkers }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val articleOpener: StateFlow<ArticleOpener> = settings.settings.map { it.articleOpener }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArticleOpener.AresReader)

    private val refreshingFeed = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = combine(refresh.progress, refreshingFeed) { progress, feed ->
        feed || progress is RefreshProgress.Running
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val messageChannel = Channel<EntriesMessage>(Channel.BUFFERED)
    val messages: Flow<EntriesMessage> = messageChannel.receiveAsFlow()

    fun toggleUnreadOnly() {
        unreadOnlyState.value = !unreadOnlyState.value
    }

    fun toggleRead(item: EntryListItem) {
        viewModelScope.launch { entries.setRead(item.id, !item.isRead) }
    }

    fun toggleBookmark(item: EntryListItem) {
        viewModelScope.launch { entries.setBookmarked(item.id, !item.isBookmarked) }
    }

    fun markOpened(item: EntryListItem) {
        if (!item.isRead) viewModelScope.launch { entries.setRead(item.id, true) }
    }

    fun markAllRead() {
        viewModelScope.launch { messageChannel.send(EntriesMessage.MarkedRead(entries.markAllRead(scope))) }
    }

    fun undoMarkRead(entryIds: List<String>) {
        viewModelScope.launch { entries.markUnread(entryIds) }
    }

    fun refresh() {
        viewModelScope.launch {
            val summary = if (scope is EntryScope.Feed) {
                refreshingFeed.value = true
                try {
                    refresh.refreshFeed(scope.feedId)
                } finally {
                    refreshingFeed.value = false
                }
            } else {
                refresh.refreshAll(force = true)
            }
            if (summary.failed > 0) messageChannel.send(EntriesMessage.RefreshFailed(summary.failed))
        }
    }
}
