// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui

import android.os.Process
import com.joaopires.ares.AppContainer
import com.joaopires.ares.backgroundDispatcher
import com.joaopires.ares.feed
import com.joaopires.ares.subscribe
import com.joaopires.ares.testContainer
import com.joaopires.ares.throttleLatest
import com.joaopires.ares.ui.home.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

class ThrottleTest {
    @Test
    fun aBurstShowsTheFirstValueAtOnceAndThenOnlyTheLatest() = runTest {
        val values = flow {
            repeat(10) {
                emit(it)
                delay(10)
            }
            delay(1_000)
            emit(99)
        }.throttleLatest(300).toList()
        assertEquals(listOf(0, 9, 99), values)
    }
}

@RunWith(RobolectricTestRunner::class)
class BackgroundDispatcherTest {
    @Test
    fun workRunsOnFewLowPriorityThreads() = runBlocking<Unit> {
        val dispatcher = backgroundDispatcher("ares-test", threads = 2)
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val seen = (1..6).map {
            async {
                withContext(dispatcher) {
                    maxActive.accumulateAndGet(active.incrementAndGet()) { a, b -> maxOf(a, b) }
                    Thread.sleep(30)
                    active.decrementAndGet()
                    Thread.currentThread().name to Process.getThreadPriority(Process.myTid())
                }
            }
        }.awaitAll()
        assertTrue(maxActive.get() <= 2)
        assertTrue(seen.toString(), seen.all { (name, priority) -> name.startsWith("ares-test-") && priority == Process.THREAD_PRIORITY_BACKGROUND })
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HomeUpdatesTest {

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

    @Test
    fun homeCatchesUpWithABurstOfNewFeeds() = runBlocking<Unit> {
        val home = HomeViewModel(container.feeds, container.entries, container.settings, container.refresh, container.imports)
        val states = async { home.state.first { it.unsectioned.size == 20 } }
        repeat(20) { container.subscribe(feed("https://f$it.example/feed", title = "Feed $it")) }
        val final = withTimeout(5_000) { states.await() }
        assertEquals((0 until 20).map { "Feed $it" }.sorted(), final.unsectioned.map { it.title }.sorted())
    }
}
