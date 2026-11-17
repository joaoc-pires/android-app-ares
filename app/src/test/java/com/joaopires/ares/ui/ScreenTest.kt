// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.joaopires.ares.AppContainer
import com.joaopires.ares.FakeFeedSource
import com.joaopires.ares.R
import com.joaopires.ares.core.data.model.EntryScope
import com.joaopires.ares.entry
import com.joaopires.ares.feed
import com.joaopires.ares.subscribe
import com.joaopires.ares.testContainer
import com.joaopires.ares.ui.common.LocalAppContainer
import com.joaopires.ares.ui.entries.EntriesScreen
import com.joaopires.ares.ui.entries.EntriesViewModel
import com.joaopires.ares.ui.home.HomeScreen
import com.joaopires.ares.ui.home.HomeViewModel
import com.joaopires.ares.ui.theme.AresTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var container: AppContainer
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        container = testContainer(FakeFeedSource())
    }

    @After
    fun tearDown() {
        container.database.close()
    }

    private fun showHome() {
        val viewModel = HomeViewModel(container.feeds, container.entries, container.settings, container.refresh, container.imports, container.opml)
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                AresTheme { HomeScreen(viewModel, onOpenList = {}, onSearch = {}, onAddFeed = {}, onImport = {}, onFeedSettings = {}, onNewSection = {}, onEditSection = {}, onFilters = {}) }
            }
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun SemanticsNodeInteraction.runCustomAction(label: String) {
        val actions = fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnUiThread { actions.first { it.label == label }.action() }
    }

    @Test
    fun homeShowsSmartListsSectionsAndFeeds() {
        runBlocking {
            val section = container.feeds.createSection("Tech")!!
            container.subscribe(feed("https://a.example/feed", entry("a1"), title = "Alpha"))
            container.subscribe(feed("https://b.example/feed", entry("b1"), title = "Beta"), sectionId = section)
        }
        showHome()
        waitForText("Beta")
        compose.onNodeWithText(context.getString(R.string.list_unread)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.list_today)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.list_bookmarks)).assertIsDisplayed()
        compose.onNodeWithText("Alpha").assertIsDisplayed()
        compose.onNodeWithText("Tech").assertIsDisplayed()
    }

    @Test
    fun sectionsCanBeCollapsed() {
        runBlocking {
            val section = container.feeds.createSection("Tech")!!
            container.subscribe(feed("https://b.example/feed", entry("b1"), title = "Beta"), sectionId = section)
        }
        showHome()
        waitForText("Beta")
        compose.onNodeWithContentDescription(context.getString(R.string.action_collapse_section, "Tech")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Beta").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription(context.getString(R.string.action_expand_section, "Tech")).performClick()
        waitForText("Beta")
    }

    @Test
    fun emptyStateIsShownWithoutFeeds() {
        showHome()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(context.getString(R.string.home_empty_title)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("home-empty").assertIsDisplayed()
    }

    @Test
    fun feedRowsOfferTalkBackActions() {
        val id = runBlocking { container.subscribe(feed("https://a.example/feed", entry("a1"), entry("a2"), title = "Alpha")) }
        showHome()
        waitForText("Alpha")
        compose.onNodeWithTag("feed-$id").runCustomAction(context.getString(R.string.action_mark_all_read))
        runBlocking { withTimeout(5_000) { container.entries.counts().first { it.unread == 0 } } }
    }

    @Test
    fun entryCardsOfferTalkBackActions() {
        val id = runBlocking { container.subscribe(feed("https://a.example/feed", entry("a1", title = "First post"), title = "Alpha")) }
        val viewModel = EntriesViewModel(EntryScope.Feed(id), container.entries, container.settings, container.refresh)
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                AresTheme { EntriesScreen(viewModel, title = "Alpha", clock = container.clock, onBack = {}, onSearch = {}, onOpenEntry = {}) }
            }
        }
        waitForText("First post")
        compose.onNodeWithTag("entry-a1").runCustomAction(context.getString(R.string.action_bookmark))
        val bookmarked = runBlocking { withTimeout(5_000) { container.entries.detail("a1").first { it?.isBookmarked == true } } }
        assertEquals("a1", bookmarked?.id)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(context.getString(R.string.entry_bookmarked)).fetchSemanticsNodes().isEmpty() &&
                runCatching { compose.onNodeWithContentDescription(context.getString(R.string.entry_bookmarked)).fetchSemanticsNode() }.isSuccess
        }
        compose.onNodeWithTag("entry-a1").runCustomAction(context.getString(R.string.action_mark_read))
        runBlocking { withTimeout(5_000) { container.entries.detail("a1").first { it?.isRead == true } } }
    }
}
