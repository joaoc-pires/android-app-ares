// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.joaopires.ares.AppContainer
import com.joaopires.ares.FakeFeedSource
import com.joaopires.ares.R
import com.joaopires.ares.core.feed.ArticleOutcome
import com.joaopires.ares.core.feed.ExtractedArticle
import com.joaopires.ares.core.feed.FetchFailure
import com.joaopires.ares.entry
import com.joaopires.ares.feed
import com.joaopires.ares.subscribe
import com.joaopires.ares.testContainer
import com.joaopires.ares.ui.article.EntryMessage
import com.joaopires.ares.ui.article.EntryScreen
import com.joaopires.ares.ui.article.EntryViewModel
import com.joaopires.ares.ui.article.LinkAction
import com.joaopires.ares.ui.common.LocalAppContainer
import com.joaopires.ares.ui.theme.AresTheme
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val WAIT = 5_000L

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EntryViewModelTest {

    private val source = FakeFeedSource()
    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        container = testContainer(source)
    }

    @After
    fun tearDown() {
        container.database.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(id: String) = EntryViewModel(id, container.entries, container.articles, container.settings)

    @Test
    fun openingAnEntryMarksItRead() = runBlocking<Unit> {
        container.subscribe(feed("https://a.example/feed", entry("a1")))
        val viewModel = viewModel("a1")
        withTimeout(WAIT) { container.entries.detail("a1").first { it?.isRead == true } }
        viewModel.markUnread()
        assertEquals(EntryMessage.MarkedUnread, withTimeout(WAIT) { viewModel.messages.first() })
        withTimeout(WAIT) { container.entries.detail("a1").first { it?.isRead == false } }
    }

    @Test
    fun fullArticleIsOnDemandAndCachedForOfflineUse() = runBlocking<Unit> {
        source.onFetchArticle = { ArticleOutcome.Extracted(ExtractedArticle("<p>Full</p>", null, null)) }
        container.subscribe(feed("https://a.example/feed", entry("a1")))
        val viewModel = viewModel("a1")
        withTimeout(WAIT) { viewModel.detail.first { it != null } }
        assertTrue(source.articleRequests.isEmpty())
        assertFalse(viewModel.showFull.value)

        viewModel.toggleFullArticle()
        withTimeout(WAIT) { viewModel.showFull.first { it } }
        assertEquals(listOf("https://site.example/a1"), source.articleRequests)
        withTimeout(WAIT) { viewModel.detail.first { it?.fullContentHtml == "<p>Full</p>" } }

        viewModel.toggleFullArticle()
        assertFalse(viewModel.showFull.value)
        source.onFetchArticle = { ArticleOutcome.Failed(FetchFailure.Network) }
        viewModel.toggleFullArticle()
        assertTrue(viewModel.showFull.value)
        assertEquals(1, source.articleRequests.size)
    }

    @Test
    fun fullArticleProblemsAreReported() = runBlocking<Unit> {
        container.subscribe(feed("https://a.example/feed", entry("a1")))
        val viewModel = viewModel("a1")
        withTimeout(WAIT) { viewModel.detail.first { it != null } }
        viewModel.toggleFullArticle()
        assertEquals(EntryMessage.NotExtractable, withTimeout(WAIT) { viewModel.messages.first() })
        source.onFetchArticle = { ArticleOutcome.Failed(FetchFailure.Timeout) }
        viewModel.toggleFullArticle()
        assertEquals(EntryMessage.FullArticleFailed(FetchFailure.Timeout), withTimeout(WAIT) { viewModel.messages.first() })
        assertFalse(viewModel.showFull.value)
    }

    @Test
    fun linksToStoredEntriesOpenInTheReader() = runBlocking<Unit> {
        container.subscribe(feed("https://a.example/feed", entry("a1"), entry("a2")))
        val viewModel = viewModel("a1")
        withTimeout(WAIT) { viewModel.detail.first { it != null } }
        assertEquals(LinkAction.OpenEntry("a2"), viewModel.resolveLink("https://site.example/a2"))
        assertEquals(LinkAction.OpenLink("https://site.example/a1"), viewModel.resolveLink("https://site.example/a1"))
        assertEquals(LinkAction.OpenLink("https://elsewhere.example/"), viewModel.resolveLink("https://elsewhere.example/"))
    }
}

@RunWith(RobolectricTestRunner::class)
class EntryScreenTest {

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

    @Test
    fun bottomBarActionsWork() {
        runBlocking { container.subscribe(feed("https://a.example/feed", entry("a1"), title = "Alpha")) }
        val viewModel = EntryViewModel("a1", container.entries, container.articles, container.settings)
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                AresTheme { EntryScreen(viewModel, onBack = {}, onOpenEntry = {}) }
            }
        }
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithContentDescription(context.getString(R.string.action_bookmark)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("article-webview", useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.action_full_article)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.action_share)).assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.action_open_original)).assertExists()

        compose.onNodeWithContentDescription(context.getString(R.string.action_bookmark)).performClick()
        runBlocking { withTimeout(WAIT) { container.entries.detail("a1").first { it?.isBookmarked == true } } }
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithContentDescription(context.getString(R.string.action_remove_bookmark)).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
