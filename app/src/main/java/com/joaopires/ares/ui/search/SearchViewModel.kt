// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.joaopires.ares.core.data.ArticleOpener
import com.joaopires.ares.core.data.EntryRepository
import com.joaopires.ares.core.data.SettingsRepository
import com.joaopires.ares.core.data.model.EntryListItem
import com.joaopires.ares.core.data.model.EntryQuery
import com.joaopires.ares.core.data.model.EntryScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val SEARCH_DEBOUNCE_MILLIS = 250L

class SearchViewModel(
    private val scope: EntryScope,
    private val entries: EntryRepository,
    settings: SettingsRepository,
) : ViewModel() {

    private val queryState = MutableStateFlow("")
    val query: StateFlow<String> = queryState.asStateFlow()

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: Flow<PagingData<EntryListItem>> = queryState
        .map { it.trim() }
        .debounce(SEARCH_DEBOUNCE_MILLIS)
        .distinctUntilChanged()
        .flatMapLatest { text ->
            if (text.isEmpty()) flowOf(PagingData.empty()) else entries.entries(EntryQuery(scope, search = text))
        }
        .cachedIn(viewModelScope)

    val hideUnread: StateFlow<Boolean> = settings.settings.map { it.hideUnreadMarkers }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val articleOpener: StateFlow<ArticleOpener> = settings.settings.map { it.articleOpener }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArticleOpener.AresReader)

    fun onQueryChange(text: String) {
        queryState.value = text
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
}
