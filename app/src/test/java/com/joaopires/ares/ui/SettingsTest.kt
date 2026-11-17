// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import com.joaopires.ares.AppContainer
import com.joaopires.ares.NOW
import com.joaopires.ares.R
import com.joaopires.ares.core.data.ArticleOpener
import com.joaopires.ares.core.data.LogLevel
import com.joaopires.ares.core.data.ThemeModeSetting
import com.joaopires.ares.entry
import com.joaopires.ares.feed
import com.joaopires.ares.subscribe
import com.joaopires.ares.testContainer
import com.joaopires.ares.ui.common.LocalAppContainer
import com.joaopires.ares.ui.settings.AppearanceScreen
import com.joaopires.ares.ui.settings.LicencesScreen
import com.joaopires.ares.ui.settings.LicencesViewModel
import com.joaopires.ares.ui.settings.LogColors
import com.joaopires.ares.ui.settings.LogsScreen
import com.joaopires.ares.ui.settings.LogsViewModel
import com.joaopires.ares.ui.settings.SettingsMessage
import com.joaopires.ares.ui.settings.SettingsNavigation
import com.joaopires.ares.ui.settings.SettingsScreen
import com.joaopires.ares.ui.settings.SettingsViewModel
import com.joaopires.ares.ui.settings.highlightLevel
import com.joaopires.ares.ui.settings.parseLibraries
import com.joaopires.ares.ui.settings.secureUrl
import com.joaopires.ares.ui.theme.AccentColor
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
import java.io.File
import java.nio.file.Files
import java.time.Duration

private const val WAIT = 5_000L

private fun Context.generatedLibraries(): String =
    resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }

class SettingsLogicTest {

    @Test
    fun onlySecureLinksAreOpened() {
        assertEquals("https://www.apache.org/licenses/LICENSE-2.0.txt", secureUrl("http://www.apache.org/licenses/LICENSE-2.0.txt"))
        assertEquals("https://opensource.org/license/mit", secureUrl(" https://opensource.org/license/mit "))
        assertNull(secureUrl("ftp://example.com/licence"))
    }

    @Test
    fun logLevelsAreHighlighted() {
        val colors = LogColors(Color.Green, Color.Yellow, Color.Red)
        val text = highlightLevel("2026-11-16T12:00:00Z ERROR Refresh of a.example failed: Network", colors)
        assertEquals("2026-11-16T12:00:00Z ERROR Refresh of a.example failed: Network", text.text)
        val span = text.spanStyles.single()
        assertEquals(Color.Red, span.item.color)
        assertEquals("ERROR", text.text.substring(span.start, span.end))
        assertTrue(highlightLevel("plain line", colors).spanStyles.isEmpty())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {

    private lateinit var container: AppContainer
    private lateinit var shareDirectory: File
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        container = testContainer()
        shareDirectory = Files.createTempDirectory("ares-share").toFile()
    }

    @After
    fun tearDown() {
        container.database.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = SettingsViewModel(container.settings, container.entries, container.opml, container.eventLog, shareDirectory)

    @Test
    fun everyChoiceIsSaved() = runBlocking<Unit> {
        val viewModel = viewModel()
        viewModel.setThemeMode(ThemeModeSetting.Dark)
        viewModel.setDynamicColor(true)
        viewModel.setAccent(AccentColor.Teal)
        viewModel.setOpener(ArticleOpener.ExternalBrowser)
        viewModel.setLoadYouTubeImages(false)
        viewModel.setHideUnreadMarkers(true)
        val saved = withTimeout(WAIT) { container.settings.settings.first { it.hideUnreadMarkers } }
        assertEquals(ThemeModeSetting.Dark, saved.themeMode)
        assertTrue(saved.dynamicColor)
        assertEquals("Teal", saved.accent)
        assertEquals(ArticleOpener.ExternalBrowser, saved.articleOpener)
        assertFalse(saved.loadYouTubeImages)
    }

    @Test
    fun aShorterRetentionCleansUpImmediately() = runBlocking<Unit> {
        container.subscribe(
            feed(
                "https://a.example/feed",
                entry("old", publishedAt = NOW.minus(Duration.ofDays(60))),
                entry("kept", publishedAt = NOW.minus(Duration.ofDays(60))),
                entry("new"),
            ),
        )
        container.entries.setRead("old", true)
        container.entries.setRead("kept", true)
        container.entries.setBookmarked("kept", true)
        container.entries.setRead("new", true)
        val viewModel = viewModel()

        viewModel.setRetention(90)
        viewModel.setRetention(30)
        assertEquals(SettingsMessage.RetentionApplied(1), withTimeout(WAIT) { viewModel.messages.first() })
        assertNull(container.entries.detail("old").first())
        assertTrue(container.entries.detail("kept").first() != null)
        assertTrue(container.entries.detail("new").first() != null)
        assertEquals(30, container.settings.current().retentionDays)

        viewModel.setRetention(null)
        withTimeout(WAIT) { container.settings.settings.first { it.retentionDays == null } }
    }

    @Test
    fun deletingTheCacheKeepsBookmarks() = runBlocking<Unit> {
        container.subscribe(feed("https://a.example/feed", entry("a1"), entry("a2")))
        container.entries.setBookmarked("a2", true)
        val viewModel = viewModel()
        viewModel.deleteCache()
        assertEquals(SettingsMessage.CacheDeleted(1), withTimeout(WAIT) { viewModel.messages.first() })
        assertNull(container.entries.detail("a1").first())
        assertTrue(container.entries.detail("a2").first() != null)
        assertEquals(1, container.feeds.feeds.first().size)
    }

    @Test
    fun feedbackOnlyAttachesTheLogWhenAskedAndThereIsOne() = runBlocking<Unit> {
        val viewModel = viewModel()
        viewModel.prepareFeedback(includeLog = true)
        assertEquals(SettingsMessage.FeedbackReady(null), withTimeout(WAIT) { viewModel.messages.first() })

        container.eventLog.log(LogLevel.Warning, "Refresh of a.example failed: Network")
        viewModel.prepareFeedback(includeLog = false)
        assertEquals(SettingsMessage.FeedbackReady(null), withTimeout(WAIT) { viewModel.messages.first() })

        viewModel.prepareFeedback(includeLog = true)
        val attached = (withTimeout(WAIT) { viewModel.messages.first() } as SettingsMessage.FeedbackReady).log!!
        assertEquals(File(shareDirectory, "Ares.log"), attached)
        assertTrue(attached.readText().contains("WARNING Refresh of a.example failed: Network"))
    }

    @Test
    fun logsCanBeReadSharedAndCleared() = runBlocking<Unit> {
        container.eventLog.log(LogLevel.Info, "first")
        container.eventLog.log(LogLevel.Error, "second")
        val viewModel = LogsViewModel(container.eventLog, shareDirectory)
        val lines = withTimeout(WAIT) { viewModel.lines.first { it?.size == 2 } }!!
        assertTrue(lines[1].endsWith("ERROR second"))

        viewModel.share()
        assertTrue(withTimeout(WAIT) { viewModel.shares.first() }.readText().contains("INFO first"))

        viewModel.clear()
        withTimeout(WAIT) { viewModel.lines.first { it?.isEmpty() == true } }
    }

    @Test
    fun theGeneratedLicenceListCoversTheDependencies() {
        val libraries = parseLibraries(context.generatedLibraries())
        val ids = libraries.map { it.id }
        assertTrue(ids.any { it.startsWith("com.squareup.okhttp3:okhttp") })
        assertTrue(ids.any { it.startsWith("androidx.room:room-runtime") })
        assertTrue(ids.any { it.startsWith("net.dankito.readability4j:") })
        assertTrue(libraries.all { library -> library.licences.all { it.url?.startsWith("https://") ?: true } })
        assertEquals(libraries.sortedBy { it.name.lowercase() }, libraries)
        assertFalse(ids.any { it.startsWith("com.google.android.gms") || it.startsWith("com.google.firebase") })
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var container: AppContainer
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val viewModels = ViewModelStore()

    private inline fun <reified T : ViewModel> owned(crossinline create: () -> T): T =
        ViewModelProvider(viewModels, viewModelFactory { initializer { create() } })[T::class.java]

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        container = testContainer()
    }

    @After
    fun tearDown() {
        viewModels.clear()
        container.database.close()
        Dispatchers.resetMain()
    }

    private fun settingsViewModel() = owned {
        SettingsViewModel(container.settings, container.entries, container.opml, container.eventLog, Files.createTempDirectory("share").toFile())
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) { AresTheme { content() } }
        }
    }

    @Test
    fun settingsListOffersEveryGroupAndSavesTheOpener() {
        var filtersOpened = false
        val viewModel = settingsViewModel()
        show {
            SettingsScreen(
                viewModel,
                SettingsNavigation(onBack = {}, onAppearance = {}, onFilters = { filtersOpened = true }, onImport = {}, onLicences = {}, onLogs = {}, onNotifications = {}),
            )
        }
        compose.waitUntil(WAIT) { compose.onAllNodesWithTag("settings-opener").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.opener_reader)).assertExists()
        compose.onNodeWithTag("settings-opener").performClick()
        compose.onNodeWithText(context.getString(R.string.opener_in_app)).performClick()
        runBlocking { withTimeout(WAIT) { container.settings.settings.first { it.articleOpener == ArticleOpener.InAppBrowser } } }

        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-filters"))
        compose.onNodeWithTag("settings-filters").performClick()
        assertTrue(filtersOpened)

        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-feedback"))
        compose.onNodeWithTag("settings-feedback").performClick()
        compose.onNodeWithTag("feedback-include-log").assertExists()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()

        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-delete-cache"))
        compose.onNodeWithTag("settings-delete-cache").performClick()
        compose.onNodeWithText(context.getString(R.string.delete_cache_body)).assertExists()
    }

    @Test
    fun dynamicColourIsOnlyOfferedWhereAvailableAndLocksTheAccent() {
        val viewModel = settingsViewModel()
        runBlocking { container.settings.update { it.copy(dynamicColor = true) } }
        var available by mutableStateOf(false)
        show { AppearanceScreen(viewModel, onBack = {}, dynamicColorAvailable = available) }
        compose.waitUntil(WAIT) { compose.onAllNodesWithTag("accent-Pink").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithTag("settings-dynamic-color").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("accent-Teal").performClick()
        runBlocking { withTimeout(WAIT) { container.settings.settings.first { it.accent == "Teal" } } }
        compose.onNodeWithTag("accent-Teal").assertIsSelected()

        available = true
        compose.waitUntil(WAIT) { compose.onAllNodesWithTag("settings-dynamic-color").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("accent-Teal").assertIsNotEnabled()
        compose.onNodeWithTag("theme-Dark").performClick()
        runBlocking { withTimeout(WAIT) { container.settings.settings.first { it.themeMode == ThemeModeSetting.Dark } } }
    }

    @Test
    fun licencesAreListed() {
        val viewModel = owned { LicencesViewModel { context.generatedLibraries() } }
        show { LicencesScreen(viewModel, onBack = {}) }
        compose.waitUntil(WAIT) { compose.onAllNodesWithTag("licences-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("licences-list").performScrollToNode(hasText("okhttp"))
        compose.onNodeWithText("okhttp").assertExists()
    }

    @Test
    fun logsShowTheirLinesOrAnEmptyState() {
        val viewModel = owned { LogsViewModel(container.eventLog, Files.createTempDirectory("share").toFile()) }
        show { LogsScreen(viewModel, onBack = {}) }
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText(context.getString(R.string.logs_empty)).fetchSemanticsNodes().isNotEmpty()
        }
        runBlocking { container.eventLog.log(LogLevel.Warning, "Refresh of a.example failed: Timeout") }
        compose.waitUntil(WAIT) {
            compose.onAllNodesWithText("Refresh of a.example failed: Timeout", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
