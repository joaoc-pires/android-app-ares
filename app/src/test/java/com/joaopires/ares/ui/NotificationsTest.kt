// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.joaopires.ares.AppContainer
import com.joaopires.ares.FakeFeedSource
import com.joaopires.ares.NOW
import com.joaopires.ares.TestClock
import com.joaopires.ares.core.data.model.EntryScope
import com.joaopires.ares.core.data.model.NewEntry
import com.joaopires.ares.core.feed.FetchOutcome
import com.joaopires.ares.core.feed.Validators
import com.joaopires.ares.entry
import com.joaopires.ares.feed
import com.joaopires.ares.notifications.ACTION_BOOKMARK
import com.joaopires.ares.notifications.ACTION_MARK_READ
import com.joaopires.ares.notifications.ACTION_OPEN_ENTRY
import com.joaopires.ares.notifications.AresWorkerFactory
import com.joaopires.ares.notifications.BACKGROUND_REFRESH_WORK
import com.joaopires.ares.notifications.BackgroundRefreshScheduler
import com.joaopires.ares.notifications.EXTRA_ENTRY_ID
import com.joaopires.ares.notifications.MAX_ENTRY_NOTIFICATIONS
import com.joaopires.ares.notifications.RefreshWorker
import com.joaopires.ares.notifications.handleNotificationAction
import com.joaopires.ares.subscribe
import com.joaopires.ares.testContainer
import com.joaopires.ares.ui.addfeed.IncomingLink
import com.joaopires.ares.ui.addfeed.incomingLink
import com.joaopires.ares.ui.common.LocalAppContainer
import com.joaopires.ares.ui.home.HomeViewModel
import com.joaopires.ares.ui.manage.FeedSettingsScreen
import com.joaopires.ares.ui.manage.FeedSettingsViewModel
import com.joaopires.ares.ui.settings.NotificationsScreen
import com.joaopires.ares.ui.settings.NotificationsViewModel
import com.joaopires.ares.ui.settings.SettingsViewModel
import com.joaopires.ares.ui.theme.AresTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import org.robolectric.Shadows.shadowOf
import java.nio.file.Files
import java.time.Duration

private const val WAIT = 5_000L

private fun grantNotifications(granted: Boolean) {
    val app = ApplicationProvider.getApplicationContext<Application>()
    if (granted) shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    else shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
}

private fun posted(): List<Pair<String?, Notification>> {
    val manager = ApplicationProvider.getApplicationContext<Context>().getSystemService(NotificationManager::class.java)
    return manager.activeNotifications.map { it.tag to it.notification }
}

private fun entryNotifications() = posted().filter { (tag, notification) -> tag != "summary" && notification.group != null }

private suspend fun waitUntil(condition: () -> Boolean) {
    withTimeout(WAIT) { while (!condition()) delay(20) }
}

class NotificationLinkTest {
    @Test
    fun notificationTapsOpenTheEntry() {
        assertEquals(IncomingLink.OpenEntry("a1"), incomingLink(ACTION_OPEN_ENTRY, "ares://entry/a1", null, null, null, entryId = "a1"))
        assertNull(incomingLink(ACTION_OPEN_ENTRY, null, null, null, null, entryId = null))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NotifierTest {

    private lateinit var container: AppContainer
    private lateinit var scope: CoroutineScope
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        grantNotifications(true)
        container = testContainer()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        container.notifier.listen(scope, container.entryEvents)
    }

    @After
    fun tearDown() {
        scope.cancel()
        container.database.close()
        Dispatchers.resetMain()
    }

    private fun newEntry(id: String, feedId: Long = 1, feedTitle: String = "Alpha") = NewEntry(id, feedId, feedTitle, "Title $id", notify = true)

    @Test
    fun eachEntryGetsANotificationUpToTheCapPlusASummary() {
        val fresh = (1..12).map { newEntry("e$it", feedId = if (it % 2 == 0) 1 else 2) }
        container.notifier.post(fresh)

        val entries = entryNotifications()
        assertEquals(MAX_ENTRY_NOTIFICATIONS, entries.size)
        val first = entries.single { it.first == "e1" }.second
        assertEquals("Alpha", first.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Title e1", first.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("Mark as read", "Bookmark"), first.actions.map { it.title.toString() })

        val tap = shadowOf(first.contentIntent).savedIntent
        assertEquals(ACTION_OPEN_ENTRY, tap.action)
        assertEquals("e1", tap.getStringExtra(EXTRA_ENTRY_ID))
        assertEquals(ACTION_MARK_READ, shadowOf(first.actions[0].actionIntent).savedIntent.action)
        assertEquals(ACTION_BOOKMARK, shadowOf(first.actions[1].actionIntent).savedIntent.action)

        val summary = posted().single { it.first == "summary" }.second
        assertEquals("12 new entries from 2 feeds", summary.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test
    fun nothingIsPostedWithoutPermission() {
        grantNotifications(false)
        container.notifier.post(listOf(newEntry("e1")))
        assertTrue(posted().isEmpty())
    }

    @Test
    fun readingOrRemovingEntriesClearsTheirNotifications() = runBlocking<Unit> {
        container.subscribe(feed("https://a.example/feed", entry("a1"), entry("a2"), title = "Alpha"))
        val b = container.subscribe(feed("https://b.example/feed", entry("b1"), title = "Beta"))
        container.notifier.post(listOf(newEntry("a1"), newEntry("a2"), newEntry("b1", feedId = b, feedTitle = "Beta")))
        assertEquals(3, entryNotifications().size)

        container.entries.setRead("a1", true)
        waitUntil { entryNotifications().map { it.first }.toSet() == setOf("a2", "b1") }

        container.feeds.deleteFeed(b)
        waitUntil { entryNotifications().map { it.first } == listOf("a2") }

        container.entries.markAllRead(EntryScope.All)
        waitUntil { posted().isEmpty() }
    }

    @Test
    fun notificationActionsMarkReadOrBookmark() = runBlocking<Unit> {
        container.subscribe(feed("https://a.example/feed", entry("a1"), entry("a2"), title = "Alpha"))
        container.notifier.post(listOf(newEntry("a1"), newEntry("a2")))

        handleNotificationAction(ACTION_MARK_READ, "a1", container.entries, container.notifier)
        assertTrue(container.entries.detail("a1").first()!!.isRead)
        handleNotificationAction(ACTION_BOOKMARK, "a2", container.entries, container.notifier)
        val bookmarked = container.entries.detail("a2").first()!!
        assertTrue(bookmarked.isBookmarked)
        assertFalse(bookmarked.isRead)
        waitUntil { posted().isEmpty() }
    }

    @Test
    fun turningNotificationsOffClearsThem() = runBlocking<Unit> {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        container.settings.update { it.copy(notificationsEnabled = true) }
        container.startBackgroundWork()
        container.notifier.post(listOf(newEntry("x1")))
        assertEquals(1, entryNotifications().size)
        container.settings.update { it.copy(notificationsEnabled = false) }
        waitUntil { posted().isEmpty() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BackgroundRefreshTest {

    private val source = FakeFeedSource()
    private val clock = TestClock()
    private lateinit var container: AppContainer
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        grantNotifications(true)
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        container = testContainer(source, clock)
    }

    @After
    fun tearDown() {
        container.database.close()
        Dispatchers.resetMain()
    }

    private suspend fun runWorker(): ListenableWorker.Result =
        TestListenableWorkerBuilder<RefreshWorker>(context).setWorkerFactory(AresWorkerFactory { container }).build().doWork()

    private suspend fun twoFeedsWithNewEntries(): Pair<Long, Long> {
        val a = container.subscribe(feed("https://a.example/feed", entry("a1"), title = "Alpha"))
        val b = container.subscribe(feed("https://b.example/feed", entry("b1"), title = "Beta"))
        container.feeds.setNotify(b, false)
        source.onFetch = { url ->
            val key = url.substringAfter("//").first()
            FetchOutcome.Updated(feed(url, entry("${key}1"), entry("${key}2"), title = if (key == 'a') "Alpha" else "Beta"), Validators(null, null), null)
        }
        clock.now = NOW.plus(Duration.ofHours(1))
        return a to b
    }

    @Test
    fun theWorkerRefreshesAndOnlyNotifiesForFeedsThatAllowIt() = runBlocking<Unit> {
        twoFeedsWithNewEntries()
        container.settings.update { it.copy(notificationsEnabled = true) }
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertTrue(container.entries.detail("a2").first() != null)
        assertTrue(container.entries.detail("b2").first() != null)
        assertEquals(listOf("a2"), entryNotifications().map { it.first })
    }

    @Test
    fun theMainSwitchOverridesEveryFeed() = runBlocking<Unit> {
        twoFeedsWithNewEntries()
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertTrue(container.entries.detail("a2").first() != null)
        assertTrue(posted().isEmpty())
    }

    @Test
    fun pullToRefreshNeverNotifies() = runBlocking<Unit> {
        twoFeedsWithNewEntries()
        container.settings.update { it.copy(notificationsEnabled = true) }
        val home = HomeViewModel(container.feeds, container.entries, container.settings, container.refresh, container.imports)
        home.refresh()
        waitUntil { runBlocking { container.entries.detail("a2").first() } != null }
        assertTrue(posted().isEmpty())
    }

    @Test
    fun openingTheAppRefreshesFeedsThatAreStale() = runBlocking<Unit> {
        container.subscribe(feed("https://a.example/feed", entry("a1")))
        source.onFetch = { FetchOutcome.Updated(feed(it, entry("a1"), entry("a2")), Validators(null, null), null) }
        clock.now = NOW.plus(Duration.ofMinutes(10))
        HomeViewModel(container.feeds, container.entries, container.settings, container.refresh, container.imports)
        delay(200)
        assertNull(container.entries.detail("a2").first())

        clock.now = NOW.plus(Duration.ofMinutes(20))
        HomeViewModel(container.feeds, container.entries, container.settings, container.refresh, container.imports)
        waitUntil { runBlocking { container.entries.detail("a2").first() } != null }
    }

    @Test
    fun theScheduleFollowsTheSetting() = runBlocking<Unit> {
        val workManager = WorkManager.getInstance(context)
        val scheduler = BackgroundRefreshScheduler { workManager }
        scheduler.apply(180)
        val info = workManager.getWorkInfosForUniqueWork(BACKGROUND_REFRESH_WORK).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(Duration.ofMinutes(180).toMillis(), info.periodicityInfo!!.repeatIntervalMillis)
        assertTrue(info.constraints.requiresBatteryNotLow())

        scheduler.apply(null)
        assertEquals(WorkInfo.State.CANCELLED, workManager.getWorkInfosForUniqueWork(BACKGROUND_REFRESH_WORK).get().single().state)

        container.startBackgroundWork()
        waitUntil { workManager.getWorkInfosForUniqueWork(BACKGROUND_REFRESH_WORK).get().any { it.state == WorkInfo.State.ENQUEUED } }
        val viewModel = SettingsViewModel(container.settings, container.entries, container.opml, container.eventLog, Files.createTempDirectory("s").toFile())
        viewModel.setBackgroundRefresh(null)
        waitUntil { workManager.getWorkInfosForUniqueWork(BACKGROUND_REFRESH_WORK).get().none { it.state == WorkInfo.State.ENQUEUED } }
        assertEquals(null, container.settings.current().backgroundRefreshMinutes)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NotificationScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var container: AppContainer
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

    @Test
    fun theNotificationsScreenTurnsOnAndChoosesFeeds() {
        grantNotifications(true)
        val a = runBlocking { container.subscribe(feed("https://a.example/feed", title = "Alpha")) }
        val viewModel = owned { NotificationsViewModel(container.settings, container.feeds) }
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) { AresTheme { NotificationsScreen(viewModel, onBack = {}) } }
        }
        compose.waitUntil(WAIT) { compose.onAllNodesWithTag("notifications-switch").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithTag("notify-feed-$a").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("notifications-blocked").fetchSemanticsNodes().isEmpty())

        compose.onNodeWithTag("notifications-switch").performClick()
        runBlocking { withTimeout(WAIT) { container.settings.settings.first { it.notificationsEnabled } } }
        compose.waitUntil(WAIT) { compose.onAllNodesWithTag("notify-feed-$a").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("notify-feed-$a").performClick()
        runBlocking { withTimeout(WAIT) { container.feeds.feeds.first { list -> list.none { it.notify } } } }
        compose.onNodeWithTag("notifications-all-on").performClick()
        runBlocking { withTimeout(WAIT) { container.feeds.feeds.first { list -> list.all { it.notify } } } }
    }

    @Test
    fun blockedNotificationsAreExplained() {
        grantNotifications(false)
        val viewModel = owned { NotificationsViewModel(container.settings, container.feeds) }
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) { AresTheme { NotificationsScreen(viewModel, onBack = {}) } }
        }
        compose.waitUntil(WAIT) { compose.onAllNodesWithTag("notifications-blocked").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theFeedSwitchRespectsTheMainSwitch() {
        val id = runBlocking { container.subscribe(feed("https://a.example/feed", title = "Alpha")) }
        val viewModel = owned { FeedSettingsViewModel(id, container.feeds, container.feedSource, container.refresh, container.settings) }
        var closed = false
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                AresTheme { FeedSettingsScreen(viewModel, container.clock, onBack = { closed = true }, onDeleted = {}) }
            }
        }
        compose.waitUntil(WAIT) { compose.onAllNodesWithTag("feed-settings-notify").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("feed-settings-notify").assertIsNotEnabled()

        runBlocking { container.settings.update { it.copy(notificationsEnabled = true) } }
        compose.waitUntil(WAIT) { runCatching { compose.onNodeWithTag("feed-settings-notify").assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("feed-settings-notify").performClick()
        compose.onNodeWithTag("feed-settings-notify").assertIsOff()
        compose.onNodeWithTag("manage-save").performClick()
        compose.waitUntil(WAIT) { closed }
        assertFalse(runBlocking { container.feeds.feedSettings(id).first()!!.notify })
    }
}
