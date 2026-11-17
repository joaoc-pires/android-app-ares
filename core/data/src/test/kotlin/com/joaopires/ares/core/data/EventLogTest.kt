// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data

import com.joaopires.ares.core.feed.FetchFailure
import com.joaopires.ares.core.feed.FetchOutcome
import com.joaopires.ares.core.feed.Validators
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

class EventLogTest {

    private fun newLog(maxBytes: Long = 128L * 1024) =
        FileEventLog(Files.createTempDirectory("ares-log").toFile(), MutableClock(), maxBytes)

    @Test
    fun linesAreTimestampedWithTheirLevel() = runBlocking {
        val log = newLog()
        log.log(LogLevel.Warning, "first\nline")
        log.log(LogLevel.Error, "second")
        assertEquals("$NOW WARNING first line\n$NOW ERROR second\n", log.read())
        assertEquals(2, log.version.value)
    }

    @Test
    fun theLogRotatesSoItStaysSmall() = runBlocking {
        val directory = Files.createTempDirectory("ares-log").toFile()
        val log = FileEventLog(directory, MutableClock(), maxBytesPerFile = 200)
        repeat(50) { log.log(LogLevel.Info, "message number $it") }
        val text = log.read()
        assertTrue(text.endsWith("message number 49\n"))
        assertFalse(text.contains("message number 0\n"))
        assertTrue(directory.listFiles()!!.sumOf(File::length) <= 400)
    }

    @Test
    fun clearRemovesEverything() = runBlocking {
        val log = newLog()
        log.log(LogLevel.Info, "hello")
        log.clear()
        assertEquals("", log.read())
    }

    @Test
    fun onlyTheDomainIsLogged() {
        assertEquals("blog.example", domainForLog("https://Blog.Example/private/feed?token=secret"))
        assertEquals("unknown host", domainForLog("not a url"))
    }
}

@RunWith(RobolectricTestRunner::class)
class EventLogWiringTest {

    @Test
    fun refreshAndImportFailuresAreLoggedWithoutAddresses() = runBlocking<Unit> {
        val db = inMemoryDatabase()
        val log = FileEventLog(Files.createTempDirectory("ares-log").toFile(), MutableClock())
        val feeds = FeedRepository(db, MutableClock())
        feeds.subscribe(feed("https://a.example/secret/feed"), Validators(null, null))
        val source = FakeFeedSource { _, _, _ -> FetchOutcome.Failed(FetchFailure.Http(500)) }
        RefreshManager(db, source, testSettings(), MutableClock(), log = log).refreshAll()
        FeedImporter(feeds, source, log = log).import(listOf(ImportCandidate("https://b.example/private.xml", null, null)))

        val text = log.read()
        assertTrue(text, text.contains("WARNING Refresh of a.example failed: Http:500"))
        assertTrue(text, text.contains("INFO Refreshed 0 of 1 feeds, 1 failed"))
        assertTrue(text, text.contains("WARNING Import of b.example failed: Http:500"))
        assertFalse(text, text.contains("secret") || text.contains("private"))
        db.close()
    }
}
