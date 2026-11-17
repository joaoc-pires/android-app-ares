// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui

import androidx.paging.testing.asSnapshot
import com.joaopires.ares.AppContainer
import com.joaopires.ares.FakeFeedSource
import com.joaopires.ares.core.data.model.EntryScope
import com.joaopires.ares.core.feed.FetchFailure
import com.joaopires.ares.core.feed.FetchOutcome
import com.joaopires.ares.entry
import com.joaopires.ares.feed
import com.joaopires.ares.subscribe
import com.joaopires.ares.ui.entries.EntriesMessage
import com.joaopires.ares.ui.entries.EntriesViewModel
import com.joaopires.ares.ui.home.HomeMessage
import com.joaopires.ares.ui.home.HomeUiState
import com.joaopires.ares.ui.home.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val WAIT_MILLIS = 5_000L

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ViewModelTest {

    private val source = FakeFeedSource()
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        container = testContainer()
    }

    @After
    fun tearDown() {
        container.database.close()
        Dispatchers.resetMain()
    }

    private fun testContainer() = com.joaopires.ares.testContainer(source)

    private fun home() = HomeViewModel(container.feeds, container.entries, container.settings, container.refresh, container.imports)

    private suspend fun HomeViewModel.awaitState(predicate: (HomeUiState) -> Boolean): HomeUiState =
        withTimeout(WAIT_MILLIS) { state.first(predicate) }

    @Test
    fun homeGroupsFeedsBySectionWithCounts() = runBlocking<Unit> {
        val section = container.feeds.createSection("Tech")!!
        container.subscribe(feed("https://a.example/feed", entry("a1"), entry("a2"), title = "Alpha"))
        container.subscribe(feed("https://b.example/feed", entry("b1"), title = "Beta"), sectionId = section)
        val state = home().awaitState { it.loaded && it.unsectioned.isNotEmpty() && it.sections.any { s -> s.feeds.isNotEmpty() } }
        assertEquals(listOf("Alpha"), state.unsectioned.map { it.title })
        assertEquals(2, state.unsectioned.single().unread)
        assertEquals("Tech", state.sections.single().name)
        assertEquals(listOf("Beta"), state.sections.single().feeds.map { it.title })
        assertEquals(3, state.counts.unread)
        assertFalse(state.isEmpty)
    }

    @Test
    fun homeIsEmptyWithoutFeeds() = runBlocking<Unit> {
        assertTrue(home().awaitState { it.loaded }.isEmpty)
    }

    @Test
    fun hideUnreadSettingIsReflected() = runBlocking<Unit> {
        container.settings.update { it.copy(hideUnreadMarkers = true) }
        assertTrue(home().awaitState { it.loaded }.hideUnread)
    }

    @Test
    fun markingAFeedReadCanBeUndone() = runBlocking<Unit> {
        val id = container.subscribe(feed("https://a.example/feed", entry("a1"), entry("a2")))
        val viewModel = home()
        viewModel.markFeedRead(id)
        val message = withTimeout(WAIT_MILLIS) { viewModel.messages.first() } as HomeMessage.MarkedRead
        assertEquals(setOf("a1", "a2"), message.entryIds.toSet())
        viewModel.undoMarkRead(message.entryIds)
        withTimeout(WAIT_MILLIS) { container.entries.counts().first { it.unread == 2 } }
    }

    @Test
    fun refreshFailuresAreReported() = runBlocking<Unit> {
        container.subscribe(feed("https://a.example/feed"))
        source.onFetch = { FetchOutcome.Failed(FetchFailure.Timeout) }
        val viewModel = home()
        viewModel.refresh()
        assertEquals(HomeMessage.RefreshFailed(1), withTimeout(WAIT_MILLIS) { viewModel.messages.first() })
    }

    @Test
    fun entryListCanShowUnreadOnly() = runBlocking<Unit> {
        val id = container.subscribe(feed("https://a.example/feed", entry("a1"), entry("a2")))
        container.entries.setRead("a1", true)
        val viewModel = EntriesViewModel(EntryScope.Feed(id), container.entries, container.settings, container.refresh)
        assertTrue(viewModel.canFilterUnread)
        assertEquals(setOf("a1", "a2"), viewModel.items.asSnapshot().map { it.id }.toSet())
        viewModel.toggleUnreadOnly()
        assertEquals(listOf("a2"), viewModel.items.asSnapshot().map { it.id })
    }

    @Test
    fun theUnreadListHasNoUnreadFilter() {
        assertFalse(EntriesViewModel(EntryScope.Unread, container.entries, container.settings, container.refresh).canFilterUnread)
    }

    @Test
    fun entryListActionsChangeTheEntry() = runBlocking<Unit> {
        val id = container.subscribe(feed("https://a.example/feed", entry("a1")))
        val viewModel = EntriesViewModel(EntryScope.Feed(id), container.entries, container.settings, container.refresh)
        val item = viewModel.items.asSnapshot().single()
        viewModel.toggleBookmark(item)
        viewModel.markOpened(item)
        withTimeout(WAIT_MILLIS) { container.entries.detail("a1").first { it?.isBookmarked == true && it.isRead } }
        viewModel.markAllRead()
        assertEquals(EntriesMessage.MarkedRead(emptyList()), withTimeout(WAIT_MILLIS) { viewModel.messages.first() })
    }
}
