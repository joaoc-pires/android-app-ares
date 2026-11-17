// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.addfeed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joaopires.ares.core.data.FeedRepository
import com.joaopires.ares.core.data.FeedSource
import com.joaopires.ares.core.data.SubscribeResult
import com.joaopires.ares.core.data.model.Section
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AddFeedError {
    data object InvalidUrl : AddFeedError
    data class Fetch(val failure: FetchFailure) : AddFeedError
}

sealed interface AddFeedPhase {
    data class Editing(val error: AddFeedError? = null) : AddFeedPhase
    data object Loading : AddFeedPhase
    data class Choosing(val candidates: List<DiscoveredFeed>) : AddFeedPhase
    data class Previewing(val feed: Feed, val validators: Validators) : AddFeedPhase
    data object Subscribing : AddFeedPhase
}

data class AddFeedUiState(
    val url: String = "",
    val title: String = "",
    val sectionId: Long? = null,
    val phase: AddFeedPhase = AddFeedPhase.Editing(),
)

sealed interface AddFeedEvent {
    data object ShowPreview : AddFeedEvent
    data class Finished(val title: String?, val alreadySubscribed: Boolean) : AddFeedEvent
}

class AddFeedViewModel(
    initialUrl: String,
    autoPreview: Boolean,
    private val feeds: FeedRepository,
    private val source: FeedSource,
) : ViewModel() {

    private val uiState = MutableStateFlow(AddFeedUiState(url = initialUrl))
    val state: StateFlow<AddFeedUiState> = uiState.asStateFlow()

    val sections: StateFlow<List<Section>> = feeds.sections.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val eventChannel = Channel<AddFeedEvent>(Channel.BUFFERED)
    val events: Flow<AddFeedEvent> = eventChannel.receiveAsFlow()

    init {
        if (autoPreview && initialUrl.isNotBlank()) preview()
    }

    fun onUrlChange(url: String) = uiState.update { it.copy(url = url, phase = AddFeedPhase.Editing()) }

    fun onTitleChange(title: String) = uiState.update { it.copy(title = title) }

    fun onSectionChange(sectionId: Long?) = uiState.update { it.copy(sectionId = sectionId) }

    fun backToEditing() = uiState.update { it.copy(phase = AddFeedPhase.Editing()) }

    fun preview() {
        val current = uiState.value
        if (current.phase == AddFeedPhase.Loading || current.phase == AddFeedPhase.Subscribing) return
        val url = normalizeFeedInput(current.url)
        if (url == null) {
            uiState.update { it.copy(phase = AddFeedPhase.Editing(AddFeedError.InvalidUrl)) }
            return
        }
        uiState.update { it.copy(phase = AddFeedPhase.Loading) }
        launchGuarded {
            when (val outcome = source.discover(url)) {
                is DiscoveryOutcome.Found -> showPreview(outcome.feed, outcome.validators)
                is DiscoveryOutcome.Candidates -> uiState.update { it.copy(phase = AddFeedPhase.Choosing(outcome.feeds)) }
                DiscoveryOutcome.NothingFound -> fail(FetchFailure.NotAFeed)
                is DiscoveryOutcome.Failed -> fail(outcome.failure)
            }
        }
    }

    fun chooseCandidate(candidate: DiscoveredFeed) {
        uiState.update { it.copy(phase = AddFeedPhase.Loading) }
        launchGuarded {
            when (val outcome = source.fetch(candidate.url, null, candidate.url)) {
                is FetchOutcome.Updated -> showPreview(outcome.feed, outcome.validators)
                is FetchOutcome.Failed -> fail(outcome.failure)
                FetchOutcome.Gone -> fail(FetchFailure.Http(410))
                is FetchOutcome.NotModified -> fail(FetchFailure.NotAFeed)
            }
        }
    }

    fun subscribe() {
        val current = uiState.value
        val preview = current.phase as? AddFeedPhase.Previewing ?: return
        uiState.update { it.copy(phase = AddFeedPhase.Subscribing) }
        launchGuarded {
            val title = current.title.trim().ifEmpty { null }
            val result = feeds.subscribe(preview.feed, preview.validators, customTitle = title, sectionId = current.sectionId)
            eventChannel.send(
                AddFeedEvent.Finished(
                    title = title ?: preview.feed.title ?: preview.feed.url,
                    alreadySubscribed = result is SubscribeResult.AlreadySubscribed,
                ),
            )
        }
    }

    private suspend fun showPreview(feed: Feed, validators: Validators) {
        uiState.update { it.copy(phase = AddFeedPhase.Previewing(feed, validators)) }
        eventChannel.send(AddFeedEvent.ShowPreview)
    }

    private fun fail(failure: FetchFailure) = uiState.update { it.copy(phase = AddFeedPhase.Editing(AddFeedError.Fetch(failure))) }

    private fun launchGuarded(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                fail(FetchFailure.Malformed)
            }
        }
    }
}
