// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.joaopires.ares.AppContainer
import com.joaopires.ares.FakeFeedSource
import com.joaopires.ares.R
import com.joaopires.ares.core.data.db.FeedStatus
import com.joaopires.ares.core.feed.FetchFailure
import com.joaopires.ares.core.feed.FetchOutcome
import com.joaopires.ares.core.feed.Validators
import com.joaopires.ares.entry
import com.joaopires.ares.feed
import com.joaopires.ares.subscribe
import com.joaopires.ares.testContainer
import com.joaopires.ares.ui.common.LocalAppContainer
import com.joaopires.ares.ui.home.HomeScreen
import com.joaopires.ares.ui.home.HomeViewModel
import com.joaopires.ares.ui.manage.FeedSettingsError
import com.joaopires.ares.ui.manage.FeedSettingsScreen
import com.joaopires.ares.ui.manage.FeedSettingsViewModel
import com.joaopires.ares.ui.manage.FiltersMessage
import com.joaopires.ares.ui.manage.FiltersScreen
import com.joaopires.ares.ui.manage.FiltersViewModel
import com.joaopires.ares.ui.manage.ManageEvent
import com.joaopires.ares.ui.manage.SectionError
import com.joaopires.ares.ui.manage.SectionScreen
import com.joaopires.ares.ui.manage.SectionViewModel
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val WAIT = 5_000L

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ManageViewModelTest {

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

    private suspend fun feedSettings(feedId: Long) =
        FeedSettingsViewModel(feedId, container.feeds, container.feedSource, container.refresh).also { viewModel ->
            withTimeout(WAIT) { viewModel.form.first { it != null } }
        }

    @Test
    fun titleAndSectionAreSavedAndAnEmptyTitleRevertsToTheFeedTitle() = runBlocking<Unit> {
        source.onFetch = { error("an unchanged address must not be downloaded") }
        val section = container.feeds.createSection("Tech")!!
        val id = container.subscribe(feed("https://a.example/feed", title = "Alpha"))
        val viewModel = feedSettings(id)
        assertEquals("", viewModel.form.value!!.title)

        viewModel.onTitleChange("  Mine  ")
        viewModel.onSectionChange(section)
        viewModel.save()
        assertEquals(ManageEvent.Saved, withTimeout(WAIT) { viewModel.events.first() })
        val saved = container.feeds.feeds.first().single()
        assertEquals("Mine", saved.title)
        assertEquals(section, saved.sectionId)

        viewModel.onTitleChange("")
        viewModel.save()
        withTimeout(WAIT) { viewModel.events.first() }
        assertEquals("Alpha", container.feeds.feeds.first().single().title)
    }

    @Test
    fun aNewAddressIsDownloadedBeforeSavingAndFollowsRedirects() = runBlocking<Unit> {
        val id = container.subscribe(feed("https://a.example/feed"))
        val requested = mutableListOf<String>()
        source.onFetch = { url ->
            requested += url
            when (url) {
                "https://broken.example/feed" -> FetchOutcome.Failed(FetchFailure.NotAFeed)
                else -> FetchOutcome.Updated(feed(url), Validators(null, null), "https://moved.example/feed")
            }
        }
        val viewModel = feedSettings(id)

        viewModel.onUrlChange("broken.example/feed")
        viewModel.save()
        withTimeout(WAIT) { viewModel.form.first { it?.saving == false && it.error != null } }
        assertEquals(FeedSettingsError.Fetch(FetchFailure.NotAFeed), viewModel.form.value!!.error)
        assertEquals("https://a.example/feed", container.feeds.feeds.first().single().url)

        viewModel.onUrlChange("https://new.example/feed")
        viewModel.save()
        assertEquals(ManageEvent.Saved, withTimeout(WAIT) { viewModel.events.first() })
        assertEquals("https://moved.example/feed", container.feeds.feeds.first().single().url)
        assertEquals(listOf("https://broken.example/feed", "https://new.example/feed"), requested)
    }

    @Test
    fun duplicateAndInvalidAddressesAreRefused() = runBlocking<Unit> {
        val a = container.subscribe(feed("https://a.example/feed"))
        container.subscribe(feed("https://b.example/feed"))
        source.onFetch = { FetchOutcome.Updated(feed(it), Validators(null, null), null) }
        val viewModel = feedSettings(a)

        viewModel.onUrlChange("https://b.example/feed")
        viewModel.save()
        withTimeout(WAIT) { viewModel.form.first { it?.saving == false && it.error != null } }
        assertEquals(FeedSettingsError.UrlTaken, viewModel.form.value!!.error)

        viewModel.onUrlChange("not a url")
        viewModel.save()
        assertEquals(FeedSettingsError.InvalidUrl, viewModel.form.value!!.error)
    }

    @Test
    fun goneFeedsCanBeTriedAgainAndFeedsDeleted() = runBlocking<Unit> {
        val id = container.subscribe(feed("https://a.example/feed", entry("e1")))
        source.onFetch = { FetchOutcome.Gone }
        container.refresh.refreshFeed(id)
        val viewModel = feedSettings(id)
        withTimeout(WAIT) { viewModel.settings.first { it?.status == FeedStatus.Gone } }

        source.onFetch = { FetchOutcome.NotModified(null) }
        viewModel.tryAgain()
        val active = withTimeout(WAIT) { viewModel.settings.first { it?.status == FeedStatus.Active } }!!
        assertNull(active.lastError)

        viewModel.delete()
        assertEquals(ManageEvent.Deleted, withTimeout(WAIT) { viewModel.events.first() })
        assertTrue(container.feeds.feeds.first().isEmpty())
    }

    @Test
    fun sectionsAreCreatedWithFeedsAndEditedByTickingAndUnticking() = runBlocking<Unit> {
        val a = container.subscribe(feed("https://a.example/feed"))
        val b = container.subscribe(feed("https://b.example/feed"))
        val other = container.feeds.createSection("Other")!!
        val c = container.subscribe(feed("https://c.example/feed"), sectionId = other)

        val create = SectionViewModel(null, container.feeds)
        create.save()
        assertEquals(SectionError.Empty, create.form.value!!.error)
        create.onNameChange("other")
        create.save()
        withTimeout(WAIT) { create.form.first { it?.saving == false && it.error != null } }
        assertEquals(SectionError.NameTaken, create.form.value!!.error)

        create.onNameChange("Tech")
        create.toggle(a)
        create.toggle(c)
        create.save()
        assertEquals(ManageEvent.Saved, withTimeout(WAIT) { create.events.first() })
        val tech = container.feeds.sections.first().single { it.name == "Tech" }.id
        assertEquals(setOf(a, c), container.feeds.sectionFeedIds(tech).toSet())

        val edit = SectionViewModel(tech, container.feeds)
        withTimeout(WAIT) { edit.form.first { it != null } }
        assertEquals(setOf(a, c), edit.form.value!!.selected)
        val rows = withTimeout(WAIT) { edit.rows.first { it.size == 3 } }
        assertNull(rows.single { it.id == a }.otherSection)
        assertNull(rows.single { it.id == b }.otherSection)

        edit.onNameChange("Technology")
        edit.toggle(a)
        edit.toggle(b)
        edit.save()
        withTimeout(WAIT) { edit.events.first() }
        assertEquals("Technology", container.feeds.sections.first().single { it.id == tech }.name)
        assertEquals(setOf(b, c), container.feeds.sectionFeedIds(tech).toSet())
        assertNull(container.feeds.feeds.first().single { it.id == a }.sectionId)
    }

    @Test
    fun filtersAreEditedAndAppliedWithConfirmationKeepingBookmarks() = runBlocking<Unit> {
        container.subscribe(
            feed("https://a.example/feed", entry("e1", title = "Sponsored post"), entry("e2", title = "Sponsored but kept"), entry("e3")),
        )
        container.entries.setBookmarked("e2", true)
        val viewModel = FiltersViewModel(container.filters)

        viewModel.requestApply()
        assertEquals(FiltersMessage.NothingMatches, withTimeout(WAIT) { viewModel.messages.first() })

        viewModel.add()
        val filter = withTimeout(WAIT) { container.filters.filters.first { it.isNotEmpty() } }.single()
        viewModel.update(filter.id, "sponsored")
        withTimeout(WAIT) { container.filters.filters.first { it.single().expression == "sponsored" } }
        viewModel.requestApply()
        assertEquals(FiltersMessage.ConfirmApply(1), withTimeout(WAIT) { viewModel.messages.first() })
        viewModel.confirmApply()
        assertEquals(FiltersMessage.Applied(1), withTimeout(WAIT) { viewModel.messages.first() })

        assertNull(container.entries.detail("e1").first())
        assertTrue(container.entries.detail("e2").first() != null)
        assertTrue(container.entries.detail("e3").first() != null)

        viewModel.delete(filter.id)
        withTimeout(WAIT) { container.filters.filters.first { it.isEmpty() } }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ManageScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val source = FakeFeedSource()
    private lateinit var container: AppContainer
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val viewModels = ViewModelStore()

    private inline fun <reified T : ViewModel> owned(crossinline create: () -> T): T =
        ViewModelProvider(viewModels, viewModelFactory { initializer { create() } })[T::class.java]

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        container = testContainer(source)
    }

    @After
    fun tearDown() {
        viewModels.clear()
        container.database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun feedSettingsShowsTheFeedAndConfirmsDeletion() {
        val id = runBlocking { container.subscribe(feed("https://a.example/feed", title = "Alpha")) }
        val viewModel = owned { FeedSettingsViewModel(id, container.feeds, container.feedSource, container.refresh) }
        var deleted = false
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                AresTheme { FeedSettingsScreen(viewModel, container.clock, onBack = {}, onDeleted = { deleted = true }) }
            }
        }
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("https://a.example/feed").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.feed_settings_title_default, "Alpha")).assertExists()
        compose.onNodeWithTag("feed-settings-status").assertTextContains(context.getString(R.string.feed_settings_last_updated, ""), substring = true)
        compose.onNodeWithTag("feed-settings-delete").performClick()
        compose.onNodeWithText(context.getString(R.string.action_delete)).performClick()
        compose.waitUntil(WAIT) { deleted }
    }

    @Test
    fun sectionScreenCreatesASectionFromTickedFeeds() {
        val id = runBlocking { container.subscribe(feed("https://a.example/feed", title = "Alpha")) }
        val viewModel = owned { SectionViewModel(null, container.feeds) }
        var done = false
        val focus = FocusHolder()
        compose.setContent {
            CaptureFocus(focus)
            CompositionLocalProvider(LocalAppContainer provides container) {
                AresTheme { SectionScreen(viewModel, onDone = { done = true }) }
            }
        }
        compose.onNodeWithTag("section-name").performTextInput("Tech")
        compose.releaseFocus(focus)
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Alpha").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Alpha").performClick()
        compose.onNodeWithTag("manage-save").performClick()
        compose.waitUntil(WAIT) { done }
        runBlocking {
            val section = container.feeds.sections.first().single()
            assertEquals("Tech", section.name)
            assertEquals(listOf(id), container.feeds.sectionFeedIds(section.id))
        }
    }

    @Test
    fun filtersScreenAddsEditsAndAsksBeforeApplying() {
        runBlocking { container.subscribe(feed("https://a.example/feed", entry("e1", title = "Sponsored"), entry("e2"))) }
        val viewModel = owned { FiltersViewModel(container.filters) }
        val focus = FocusHolder()
        compose.setContent {
            CaptureFocus(focus)
            CompositionLocalProvider(LocalAppContainer provides container) {
                AresTheme { FiltersScreen(viewModel, onBack = {}) }
            }
        }
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText(context.getString(R.string.filters_empty), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("filters-add").performClick()
        val filter = runBlocking { withTimeout(WAIT) { container.filters.filters.first { it.isNotEmpty() } }.single() }
        compose.onNodeWithTag("filter-${filter.id}").performTextClearance()
        compose.onNodeWithTag("filter-${filter.id}").performTextInput("sponsored")
        compose.releaseFocus(focus)
        compose.waitUntil(WAIT) { runBlocking { container.filters.filters.first().single().expression == "sponsored" } }
        compose.onNodeWithTag("filters-apply").performClick()
        val body = context.resources.getQuantityString(R.plurals.filters_apply_body, 1, 1)
        compose.waitUntil(WAIT) { compose.onAllNodesWithText(body).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.action_delete)).performClick()
        compose.waitUntil(WAIT) { runBlocking { container.entries.detail("e1").first() == null } }
        assertTrue(runBlocking { container.entries.detail("e2").first() } != null)
    }

    @Test
    fun homeOffersSectionAndFeedManagement() {
        val sectionId = runBlocking {
            val section = container.feeds.createSection("Tech")!!
            container.subscribe(feed("https://a.example/feed", title = "Alpha"), sectionId = section)
            section
        }
        val viewModel = owned { HomeViewModel(container.feeds, container.entries, container.settings, container.refresh, container.imports) }
        var editedSection: Long? = null
        var settingsFor: Long? = null
        var settingsOpened = false
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                AresTheme {
                    HomeScreen(
                        viewModel,
                        onOpenList = {},
                        onSearch = {},
                        onAddFeed = {},
                        onImport = {},
                        onFeedSettings = { settingsFor = it },
                        onNewSection = {},
                        onEditSection = { editedSection = it },
                        onSettings = { settingsOpened = true },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.action_more)).performClick()
        compose.onNodeWithText(context.getString(R.string.action_new_section)).assertExists()
        compose.onNodeWithText(context.getString(R.string.action_settings)).performClick()
        assertTrue(settingsOpened)

        compose.waitUntil(WAIT) { compose.onAllNodesWithText("Alpha").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("section-menu-$sectionId").performClick()
        compose.onNodeWithText(context.getString(R.string.action_edit_section)).performClick()
        assertEquals(sectionId, editedSection)

        compose.onNodeWithTag("section-menu-$sectionId").performClick()
        compose.onNodeWithText(context.getString(R.string.action_delete_section)).performClick()
        compose.onNodeWithText(context.getString(R.string.action_delete)).performClick()
        compose.waitUntil(WAIT) { runBlocking { container.feeds.sections.first().isEmpty() } }
        assertFalse(runBlocking { container.feeds.feeds.first().isEmpty() })
        assertNull(settingsFor)
    }
}
