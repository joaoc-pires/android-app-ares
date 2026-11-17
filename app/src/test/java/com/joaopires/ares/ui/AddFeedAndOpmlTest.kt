// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Xml
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.joaopires.ares.AppContainer
import com.joaopires.ares.FakeFeedSource
import com.joaopires.ares.R
import com.joaopires.ares.core.data.ImportFailure
import com.joaopires.ares.core.feed.DiscoveredFeed
import com.joaopires.ares.core.feed.DiscoveryOutcome
import com.joaopires.ares.core.feed.FetchFailure
import com.joaopires.ares.core.feed.FetchOutcome
import com.joaopires.ares.core.feed.Validators
import com.joaopires.ares.core.opml.OpmlFeed
import com.joaopires.ares.core.opml.OpmlReader
import com.joaopires.ares.core.opml.feeds
import com.joaopires.ares.entry
import com.joaopires.ares.feed
import com.joaopires.ares.subscribe
import com.joaopires.ares.testContainer
import com.joaopires.ares.ui.addfeed.AddFeedError
import com.joaopires.ares.ui.addfeed.AddFeedEvent
import com.joaopires.ares.ui.addfeed.AddFeedPhase
import com.joaopires.ares.ui.addfeed.AddFeedScreen
import com.joaopires.ares.ui.addfeed.AddFeedViewModel
import com.joaopires.ares.ui.addfeed.FeedPreviewScreen
import com.joaopires.ares.ui.addfeed.IncomingLink
import com.joaopires.ares.ui.addfeed.firstUrlIn
import com.joaopires.ares.ui.addfeed.incomingLink
import com.joaopires.ares.ui.common.LocalAppContainer
import com.joaopires.ares.ui.home.HomeScreen
import com.joaopires.ares.ui.home.HomeViewModel
import com.joaopires.ares.ui.opml.ImportError
import com.joaopires.ares.ui.opml.ImportScreen
import com.joaopires.ares.ui.opml.ImportUiState
import com.joaopires.ares.ui.opml.ImportViewModel
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

private const val WAIT = 5_000L

class IncomingLinkTest {

    @Test
    fun sharedTextWithAnAddressAddsAFeed() {
        assertEquals(
            IncomingLink.AddFeed("https://blog.example/feed"),
            incomingLink(Intent.ACTION_SEND, null, "text/plain", "Look at this (https://blog.example/feed).", null),
        )
        assertEquals(
            IncomingLink.AddFeed("https://blog.example/rss"),
            incomingLink(Intent.ACTION_SEND, null, "text/plain", "feed://blog.example/rss", null),
        )
        assertNull(incomingLink(Intent.ACTION_SEND, null, "text/plain", "no links here", null))
        assertEquals("https://a.example/x?y=1", firstUrlIn("see https://a.example/x?y=1, thanks"))
    }

    @Test
    fun feedSchemesAreConverted() {
        assertEquals(IncomingLink.AddFeed("https://blog.example/feed"), incomingLink(Intent.ACTION_VIEW, "feed://blog.example/feed", null, null, null))
        assertEquals(IncomingLink.AddFeed("https://blog.example/feed"), incomingLink(Intent.ACTION_VIEW, "feeds://blog.example/feed", null, null, null))
        assertEquals(IncomingLink.AddFeed("https://blog.example/feed"), incomingLink(Intent.ACTION_VIEW, "feed:https://blog.example/feed", null, null, null))
        assertNull(incomingLink(Intent.ACTION_VIEW, "feed:", null, null, null))
    }

    @Test
    fun feedFilesAreRecognisedByType() {
        assertEquals(
            IncomingLink.AddFeed("https://blog.example/index.xml"),
            incomingLink(Intent.ACTION_VIEW, "https://blog.example/index.xml", "application/rss+xml; charset=utf-8", null, null),
        )
        assertNull(incomingLink(Intent.ACTION_VIEW, "https://blog.example/page.html", "text/html", null, null))
    }

    @Test
    fun opmlFilesOpenTheImport() {
        assertEquals(
            IncomingLink.ImportOpml("content://downloads/1"),
            incomingLink(Intent.ACTION_VIEW, "content://downloads/1", "text/x-opml", null, null),
        )
        assertEquals(
            IncomingLink.ImportOpml("content://files/subs.opml"),
            incomingLink(Intent.ACTION_VIEW, "content://files/subs.opml", "application/octet-stream", null, null),
        )
        assertEquals(
            IncomingLink.ImportOpml("content://share/2"),
            incomingLink(Intent.ACTION_SEND, null, "application/x-opml", null, "content://share/2"),
        )
        assertNull(incomingLink(Intent.ACTION_VIEW, "content://files/photo.jpg", "image/jpeg", null, null))
        assertNull(incomingLink("android.intent.action.MAIN", null, null, null, null))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AddFeedViewModelTest {

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

    private fun viewModel(url: String = "", auto: Boolean = false) = AddFeedViewModel(url, auto, container.feeds, container.feedSource)

    @Test
    fun previewThenSubscribeWithTitleAndSectionWithoutFetchingAgain() = runBlocking<Unit> {
        var discoveries = 0
        source.onDiscover = {
            discoveries++
            DiscoveryOutcome.Found(feed("https://new.example/feed", entry("n1"), title = "Original"), Validators("\"e\"", null))
        }
        source.onFetch = { error("must not fetch again") }
        val section = container.feeds.createSection("Tech")!!
        val viewModel = viewModel()
        viewModel.onUrlChange("new.example")
        viewModel.onTitleChange("  My title ")
        viewModel.onSectionChange(section)
        viewModel.preview()
        assertEquals(AddFeedEvent.ShowPreview, withTimeout(WAIT) { viewModel.events.first() })
        assertTrue(viewModel.state.value.phase is AddFeedPhase.Previewing)

        viewModel.subscribe()
        assertEquals(AddFeedEvent.Finished("My title", alreadySubscribed = false), withTimeout(WAIT) { viewModel.events.first() })
        val stored = container.feeds.feeds.first().single()
        assertEquals("My title", stored.title)
        assertEquals(section, stored.sectionId)
        assertEquals(1, discoveries)
    }

    @Test
    fun incomingLinksPreviewAutomatically() = runBlocking<Unit> {
        source.onDiscover = { DiscoveryOutcome.Found(feed(it), Validators(null, null)) }
        val viewModel = viewModel("https://auto.example/feed", auto = true)
        assertEquals(AddFeedEvent.ShowPreview, withTimeout(WAIT) { viewModel.events.first() })
    }

    @Test
    fun candidatesAndErrors() = runBlocking<Unit> {
        val candidates = listOf(DiscoveredFeed("https://c.example/a.xml", "A"), DiscoveredFeed("https://c.example/b.xml", "B"))
        source.onDiscover = { DiscoveryOutcome.Candidates(candidates) }
        source.onFetch = { FetchOutcome.Failed(FetchFailure.InsecureOnly) }
        val viewModel = viewModel("c.example")
        viewModel.preview()
        assertEquals(AddFeedPhase.Choosing(candidates), viewModel.state.value.phase)
        viewModel.chooseCandidate(candidates[1])
        assertEquals(AddFeedPhase.Editing(AddFeedError.Fetch(FetchFailure.InsecureOnly)), viewModel.state.value.phase)

        viewModel.onUrlChange("not a url")
        viewModel.preview()
        assertEquals(AddFeedPhase.Editing(AddFeedError.InvalidUrl), viewModel.state.value.phase)

        source.onDiscover = { throw IllegalStateException("bug") }
        viewModel.onUrlChange("broken.example")
        viewModel.preview()
        assertEquals(AddFeedPhase.Editing(AddFeedError.Fetch(FetchFailure.Malformed)), viewModel.state.value.phase)
    }

    @Test
    fun subscribingTwiceIsReported() = runBlocking<Unit> {
        container.subscribe(feed("https://dup.example/feed"))
        source.onDiscover = { DiscoveryOutcome.Found(feed("https://dup.example/feed", title = "Dup"), Validators(null, null)) }
        val viewModel = viewModel("dup.example")
        viewModel.preview()
        withTimeout(WAIT) { viewModel.events.first() }
        viewModel.subscribe()
        assertEquals(AddFeedEvent.Finished("Dup", alreadySubscribed = true), withTimeout(WAIT) { viewModel.events.first() })
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OpmlTest {

    private val source = FakeFeedSource(onFetch = { url ->
        if (url.contains("world")) {
            FetchOutcome.Failed(FetchFailure.Timeout)
        } else {
            FetchOutcome.Updated(feed(url, entry("$url#1")), Validators(null, null), null)
        }
    })
    private lateinit var container: AppContainer
    private val context: Context = ApplicationProvider.getApplicationContext()

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

    private fun fixtureUri(name: String): Uri {
        val file = File(Files.createTempDirectory("opml").toFile(), name)
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(file)
    }

    @Test
    fun importListsFeedsBySectionAndImportsTheSelection() = runBlocking<Unit> {
        container.subscribe(feed("https://loose.example/feed"))
        val viewModel = ImportViewModel(fixtureUri("categories.opml"), container.opml, container.feeds, container.imports)
        val ready = withTimeout(WAIT) { viewModel.state.first { it is ImportUiState.Ready } } as ImportUiState.Ready
        assertEquals(listOf("News", "Tech", null), ready.groups.map { it.first })
        assertTrue(ready.rows.single { it.url == "https://loose.example/feed" }.alreadyFollowed)
        assertEquals(3, ready.selectable.size)

        viewModel.toggle("https://gadgets.example/feed/")
        viewModel.startImport()
        val summary = withTimeout(WAIT) { container.imports.summary.first { it != null } }!!
        assertEquals(1, summary.imported)
        assertEquals(listOf(ImportFailure("https://world.example/rss.xml", "World Report", FetchFailure.Timeout)), summary.failures)
        val stored = container.feeds.feeds.first().associateBy { it.url }
        assertTrue("https://news.example/feed" in stored)
        assertTrue("https://gadgets.example/feed/" !in stored)
        assertEquals(listOf("News"), container.feeds.sections.first().map { it.name })
    }

    @Test
    fun filesThatAreNotOpmlAreReported() = runBlocking<Unit> {
        val viewModel = ImportViewModel(fixtureUri("not-opml.xml"), container.opml, container.feeds, container.imports)
        assertEquals(ImportUiState.Failed(ImportError.NotOpml), withTimeout(WAIT) { viewModel.state.first { it is ImportUiState.Failed } })
    }

    @Test
    fun exportRoundTripsSectionsAndTitles() = runBlocking<Unit> {
        val section = container.feeds.createSection("\u00C9coles & Co")!!
        container.feeds.subscribe(feed("https://a.example/feed?x=1&y=2", title = "A"), Validators(null, null), customTitle = "A & B \u2014 \u00E9t\u00E9", sectionId = section)
        container.subscribe(feed("https://b.example/feed", title = "Beta"))
        val target = Uri.fromFile(File(Files.createTempDirectory("export").toFile(), "out.opml"))
        assertEquals(2, container.opml.export(target))

        val parsed = context.contentResolver.openInputStream(target)!!.use { OpmlReader({ Xml.newPullParser() }).read(it) }.feeds()
        assertEquals(
            listOf(
                OpmlFeed("https://b.example/feed", "Beta", "https://b.example/feed", null),
                OpmlFeed("https://a.example/feed?x=1&y=2", "A & B \u2014 \u00E9t\u00E9", "https://a.example/feed?x=1&y=2", "\u00C9coles & Co"),
            ),
            parsed,
        )
    }
}

@RunWith(RobolectricTestRunner::class)
class AddFeedScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val source = FakeFeedSource(onDiscover = {
        DiscoveryOutcome.Found(feed("https://new.example/feed", entry("n1", title = "Preview entry"), title = "New Feed"), Validators(null, null))
    })
    private lateinit var container: AppContainer
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        container = testContainer(source)
    }

    @After
    fun tearDown() {
        container.database.close()
    }

    @Test
    fun addressThenPreviewThenSubscribe() {
        val viewModel = AddFeedViewModel("", false, container.feeds, container.feedSource)
        val showPreview = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                AresTheme {
                    if (showPreview.value) {
                        FeedPreviewScreen(viewModel, container.clock, onBack = {})
                    } else {
                        AddFeedScreen(viewModel, onClose = {}, onShowPreview = { showPreview.value = true }, onFinished = {})
                    }
                }
            }
        }
        compose.onNodeWithTag("add-feed-url").performTextInput("new.example")
        compose.onNodeWithTag("add-feed-preview").performClick()
        compose.waitUntil(WAIT) { showPreview.value }
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Preview entry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("New Feed").assertExists()
        compose.onNodeWithTag("preview-subscribe").performClick()
        runBlocking { withTimeout(WAIT) { container.feeds.feeds.first { it.isNotEmpty() } } }
    }

    @Test
    fun homeOverflowMenuOffersImportAndExport() {
        val viewModel = HomeViewModel(container.feeds, container.entries, container.settings, container.refresh, container.imports, container.opml)
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                AresTheme { HomeScreen(viewModel, onOpenList = {}, onSearch = {}, onAddFeed = {}, onImport = {}) }
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.action_more)).performClick()
        compose.onNodeWithText(context.getString(R.string.opml_import)).assertExists()
        compose.onNodeWithText(context.getString(R.string.opml_export)).assertExists()
    }

    @Test
    fun importCheckboxesCanBeToggled() {
        val file = File(Files.createTempDirectory("opml").toFile(), "flat.opml")
        javaClass.classLoader!!.getResourceAsStream("flat.opml")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        val viewModel = ImportViewModel(Uri.fromFile(file), container.opml, container.feeds, container.imports)
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                AresTheme { ImportScreen(viewModel, onClose = {}, onPreview = {}, onStarted = {}) }
            }
        }
        val tag = "import-check-https://beta.example/atom.xml"
        compose.waitUntil(WAIT) { runCatching { compose.onNodeWithTag(tag).fetchSemanticsNode() }.isSuccess }
        compose.onNodeWithTag(tag).assertIsOn()
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithTag(tag).assertIsOff()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.import_button, 2, 2), useUnmergedTree = true).assertExists()
    }
}
