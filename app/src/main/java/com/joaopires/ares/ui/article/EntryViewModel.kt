// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.article

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joaopires.ares.core.data.AppSettings
import com.joaopires.ares.core.data.ArticleRepository
import com.joaopires.ares.core.data.EntryRepository
import com.joaopires.ares.core.data.FullArticleResult
import com.joaopires.ares.core.data.SettingsRepository
import com.joaopires.ares.core.data.model.EntryDetail
import com.joaopires.ares.core.data.model.EntryEnclosure
import com.joaopires.ares.core.feed.FetchFailure
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface EntryMessage {
    data object MarkedUnread : EntryMessage
    data object NotExtractable : EntryMessage
    data object NoLink : EntryMessage
    data class FullArticleFailed(val failure: FetchFailure) : EntryMessage
}

class EntryViewModel(
    val entryId: String,
    private val entries: EntryRepository,
    private val articles: ArticleRepository,
    settings: SettingsRepository,
) : ViewModel() {

    val detail: StateFlow<EntryDetail?> = entries.detail(entryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val enclosures: StateFlow<List<EntryEnclosure>> = entries.enclosures(entryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val showFullState = MutableStateFlow(false)
    val showFull: StateFlow<Boolean> = showFullState.asStateFlow()

    private val loadingState = MutableStateFlow(false)
    val loadingFull: StateFlow<Boolean> = loadingState.asStateFlow()

    private val messageChannel = Channel<EntryMessage>(Channel.BUFFERED)
    val messages: Flow<EntryMessage> = messageChannel.receiveAsFlow()

    init {
        viewModelScope.launch { entries.setRead(entryId, true) }
    }

    fun toggleBookmark() {
        val current = detail.value ?: return
        viewModelScope.launch { entries.setBookmarked(entryId, !current.isBookmarked) }
    }

    fun markUnread() {
        viewModelScope.launch {
            entries.setRead(entryId, false)
            messageChannel.send(EntryMessage.MarkedUnread)
        }
    }

    fun toggleFullArticle() {
        if (showFullState.value) {
            showFullState.value = false
            return
        }
        if (detail.value?.fullContentHtml != null) {
            showFullState.value = true
            return
        }
        if (loadingState.value) return
        loadingState.value = true
        viewModelScope.launch {
            try {
                when (val result = articles.loadFullArticle(entryId)) {
                    FullArticleResult.Loaded -> showFullState.value = true
                    FullArticleResult.NotExtractable -> messageChannel.send(EntryMessage.NotExtractable)
                    FullArticleResult.NoLink -> messageChannel.send(EntryMessage.NoLink)
                    is FullArticleResult.Failed -> messageChannel.send(EntryMessage.FullArticleFailed(result.failure))
                }
            } finally {
                loadingState.value = false
            }
        }
    }

    suspend fun resolveLink(url: String): LinkAction {
        val watchUrl = detail.value?.let { watchUrlFor(it) }
        val attachments = enclosures.value.associate { it.url to it.type }
        classifyLink(url, watchUrl, attachments)?.let { return it }
        return articles.entryIdForUrl(url)?.takeIf { it != entryId }?.let { LinkAction.OpenEntry(it) } ?: LinkAction.OpenLink(url)
    }
}

fun watchUrlFor(detail: EntryDetail): String? = detail.videoId?.let { id -> detail.url ?: "https://www.youtube.com/watch?v=$id" }
