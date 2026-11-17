// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.joaopires.ares.AppContainer
import com.joaopires.ares.R
import com.joaopires.ares.core.data.model.EntryScope
import com.joaopires.ares.core.data.model.NewEntry
import com.joaopires.ares.entry
import com.joaopires.ares.feed
import com.joaopires.ares.subscribe
import com.joaopires.ares.testContainer
import com.joaopires.ares.ui.common.LocalAppContainer
import com.joaopires.ares.ui.common.SwipeAction
import com.joaopires.ares.ui.common.SwipeActionBox
import com.joaopires.ares.ui.entries.EntriesScreen
import com.joaopires.ares.ui.entries.EntriesViewModel
import com.joaopires.ares.ui.theme.AresTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

private const val WAIT = 5_000L

@RunWith(RobolectricTestRunner::class)
class SwipeActionBoxTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun oneSwipeRunsItsActionOnceEvenWhenTheActionsChange() {
        var read by mutableIntStateOf(0)
        var bookmarked by mutableIntStateOf(0)
        compose.setContent {
            AresTheme {
                SwipeActionBox(
                    startToEnd = SwipeAction(R.drawable.ic_bookmark_add, "Bookmark $bookmarked") { bookmarked++ },
                    endToStart = SwipeAction(R.drawable.ic_drafts, "Read $read") { read++ },
                ) {
                    Text("Row $read $bookmarked", Modifier.fillMaxWidth().height(72.dp).testTag("row"))
                }
            }
        }

        compose.onNodeWithTag("row").performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        assertEquals(1, read)
        assertEquals(0, bookmarked)

        compose.onNodeWithTag("row").performTouchInput { swipeRight() }
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        assertEquals(1, read)
        assertEquals(1, bookmarked)
    }
}

@RunWith(RobolectricTestRunner::class)
class EntrySwipeTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var container: AppContainer
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        container = testContainer()
    }

    @After
    fun tearDown() {
        container.database.close()
    }

    @Test
    fun swipingAnEntryMarksItReadAndItStaysRead() {
        val id = runBlocking { container.subscribe(feed("https://a.example/feed", entry("a1", title = "First post"), title = "Alpha")) }
        val viewModel = EntriesViewModel(EntryScope.Feed(id), container.entries, container.settings, container.refresh)
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                AresTheme { EntriesScreen(viewModel, title = "Alpha", clock = container.clock, onBack = {}, onSearch = {}, onOpenEntry = {}) }
            }
        }
        compose.waitUntil(WAIT) { compose.onAllNodesWithText("First post").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("entry-a1").performTouchInput { swipeLeft() }
        compose.waitUntil(WAIT) { runBlocking { container.entries.detail("a1").first()!!.isRead } }
        repeat(5) {
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            Thread.sleep(100)
        }
        assertTrue(runBlocking { container.entries.detail("a1").first()!!.isRead })
    }

    @Test
    fun markingAnEntryWithoutANotificationLeavesTheOthersAlone() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { container.subscribe(feed("https://a.example/feed", entry("a1"), entry("a2"), entry("a3"), title = "Alpha")) }
        container.notifier.post(listOf(NewEntry("a1", 1, "Alpha", "One", true), NewEntry("a2", 1, "Alpha", "Two", true)))
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(3, manager.activeNotifications.size)

        container.notifier.cancel(listOf("a3"))
        assertEquals(3, manager.activeNotifications.size)

        container.notifier.cancel(listOf("a1"))
        assertEquals(setOf("a2", "summary"), manager.activeNotifications.map { it.tag }.toSet())
        container.notifier.cancel(listOf("a2"))
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
