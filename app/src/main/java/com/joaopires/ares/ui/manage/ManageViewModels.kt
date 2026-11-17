// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joaopires.ares.core.data.FeedRepository
import com.joaopires.ares.core.data.FeedSource
import com.joaopires.ares.core.data.FilterRepository
import com.joaopires.ares.core.data.RefreshManager
import com.joaopires.ares.core.data.SaveSectionResult
import com.joaopires.ares.core.data.UpdateFeedResult
import com.joaopires.ares.core.data.model.FeedSettings
import com.joaopires.ares.core.data.model.Filter
import com.joaopires.ares.core.data.model.Section
import com.joaopires.ares.core.feed.FetchFailure
import com.joaopires.ares.core.feed.FetchOutcome
import com.joaopires.ares.ui.addfeed.normalizeFeedInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface FeedSettingsError {
    data object InvalidUrl : FeedSettingsError
    data object UrlTaken : FeedSettingsError
    data class Fetch(val failure: FetchFailure) : FeedSettingsError
}

data class FeedSettingsForm(
    val title: String = "",
    val url: String = "",
    val sectionId: Long? = null,
    val saving: Boolean = false,
    val error: FeedSettingsError? = null,
)

enum class ManageEvent { Saved, Deleted }

class FeedSettingsViewModel(
    private val feedId: Long,
    private val feeds: FeedRepository,
    private val source: FeedSource,
    private val refresh: RefreshManager,
) : ViewModel() {

    val settings: StateFlow<FeedSettings?> = feeds.feedSettings(feedId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val sections: StateFlow<List<Section>> = feeds.sections.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val formState = MutableStateFlow<FeedSettingsForm?>(null)
    val form: StateFlow<FeedSettingsForm?> = formState.asStateFlow()

    private val eventChannel = Channel<ManageEvent>(Channel.BUFFERED)
    val events: Flow<ManageEvent> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            val initial = settings.first { it != null } ?: return@launch
            formState.value = FeedSettingsForm(initial.customTitle.orEmpty(), initial.url, initial.sectionId)
        }
    }

    fun onTitleChange(value: String) = formState.update { it?.copy(title = value) }

    fun onUrlChange(value: String) = formState.update { it?.copy(url = value, error = null) }

    fun onSectionChange(value: Long?) = formState.update { it?.copy(sectionId = value) }

    fun save() {
        val current = formState.value ?: return
        val original = settings.value ?: return
        if (current.saving) return
        val url = normalizeFeedInput(current.url)
        if (url == null) {
            formState.value = current.copy(error = FeedSettingsError.InvalidUrl)
            return
        }
        formState.value = current.copy(saving = true, error = null)
        viewModelScope.launch {
            val error = try {
                saveChecked(current, url, original)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                FeedSettingsError.Fetch(FetchFailure.Network)
            }
            formState.update { it?.copy(saving = false, error = error) }
        }
    }

    private suspend fun saveChecked(form: FeedSettingsForm, url: String, original: FeedSettings): FeedSettingsError? {
        val finalUrl = if (url == original.url) {
            url
        } else {
            when (val verified = verify(url)) {
                is Verified.Ok -> verified.url
                is Verified.Failed -> return FeedSettingsError.Fetch(verified.failure)
            }
        }
        return when (feeds.updateFeed(feedId, form.title, finalUrl, form.sectionId)) {
            UpdateFeedResult.Saved -> null.also { eventChannel.send(ManageEvent.Saved) }
            UpdateFeedResult.UrlTaken -> FeedSettingsError.UrlTaken
            UpdateFeedResult.NotFound -> null.also { eventChannel.send(ManageEvent.Deleted) }
        }
    }

    fun delete() {
        viewModelScope.launch {
            feeds.deleteFeed(feedId)
            eventChannel.send(ManageEvent.Deleted)
        }
    }

    fun tryAgain() {
        viewModelScope.launch {
            feeds.reactivate(feedId)
            refresh.refreshFeed(feedId)
        }
    }

    private sealed interface Verified {
        data class Ok(val url: String) : Verified
        data class Failed(val failure: FetchFailure) : Verified
    }

    private suspend fun verify(url: String): Verified = when (val outcome = source.fetch(url, null, url)) {
        is FetchOutcome.Updated -> Verified.Ok(outcome.newUrl ?: url)
        is FetchOutcome.NotModified -> Verified.Ok(outcome.newUrl ?: url)
        FetchOutcome.Gone -> Verified.Failed(FetchFailure.Http(410))
        is FetchOutcome.Failed -> Verified.Failed(outcome.failure)
    }
}

data class SectionFeedRow(val id: Long, val title: String, val otherSection: String?)

enum class SectionError { Empty, NameTaken }

data class SectionForm(
    val name: String = "",
    val selected: Set<Long> = emptySet(),
    val error: SectionError? = null,
    val saving: Boolean = false,
)

class SectionViewModel(
    private val sectionId: Long?,
    private val feeds: FeedRepository,
) : ViewModel() {

    val isNew: Boolean = sectionId == null

    val rows: StateFlow<List<SectionFeedRow>> = combine(feeds.feeds, feeds.sections) { feedList, sections ->
        val names = sections.associate { it.id to it.name }
        feedList.map { feed ->
            SectionFeedRow(feed.id, feed.title, feed.sectionId?.takeIf { it != sectionId }?.let { names[it] })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val formState = MutableStateFlow<SectionForm?>(if (sectionId == null) SectionForm() else null)
    val form: StateFlow<SectionForm?> = formState.asStateFlow()

    private val eventChannel = Channel<ManageEvent>(Channel.BUFFERED)
    val events: Flow<ManageEvent> = eventChannel.receiveAsFlow()

    init {
        if (sectionId != null) {
            viewModelScope.launch {
                val section = feeds.sections.first().firstOrNull { it.id == sectionId }
                if (section == null) {
                    eventChannel.send(ManageEvent.Deleted)
                } else {
                    formState.value = SectionForm(section.name, feeds.sectionFeedIds(sectionId).toSet())
                }
            }
        }
    }

    fun onNameChange(value: String) = formState.update { it?.copy(name = value, error = null) }

    fun toggle(feedId: Long) = formState.update { form ->
        form?.copy(selected = if (feedId in form.selected) form.selected - feedId else form.selected + feedId)
    }

    fun save() {
        val current = formState.value ?: return
        if (current.saving) return
        if (current.name.isBlank()) {
            formState.value = current.copy(error = SectionError.Empty)
            return
        }
        formState.value = current.copy(saving = true)
        viewModelScope.launch {
            when (feeds.saveSection(sectionId, current.name, current.selected.toList())) {
                is SaveSectionResult.Saved -> eventChannel.send(ManageEvent.Saved)
                SaveSectionResult.NotFound -> eventChannel.send(ManageEvent.Deleted)
                SaveSectionResult.EmptyName -> formState.update { it?.copy(saving = false, error = SectionError.Empty) }
                SaveSectionResult.NameTaken -> formState.update { it?.copy(saving = false, error = SectionError.NameTaken) }
            }
        }
    }
}

sealed interface FiltersMessage {
    data class ConfirmApply(val count: Int) : FiltersMessage
    data object NothingMatches : FiltersMessage
    data class Applied(val count: Int) : FiltersMessage
}

class FiltersViewModel(private val filters: FilterRepository) : ViewModel() {

    val items: StateFlow<List<Filter>?> = filters.filters.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val messageChannel = Channel<FiltersMessage>(Channel.BUFFERED)
    val messages: Flow<FiltersMessage> = messageChannel.receiveAsFlow()

    fun add() {
        viewModelScope.launch { filters.add() }
    }

    fun update(filterId: Long, expression: String) {
        viewModelScope.launch { filters.update(filterId, expression) }
    }

    fun delete(filterId: Long) {
        viewModelScope.launch { filters.delete(filterId) }
    }

    fun requestApply() {
        viewModelScope.launch {
            val count = filters.countMatching()
            messageChannel.send(if (count == 0) FiltersMessage.NothingMatches else FiltersMessage.ConfirmApply(count))
        }
    }

    fun confirmApply() {
        viewModelScope.launch { messageChannel.send(FiltersMessage.Applied(filters.applyToExisting())) }
    }
}
