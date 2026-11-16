// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsRepositoryTest {

    @Test
    fun defaultsMatchTheAgreedBehaviour() = runBlocking {
        val settings = testSettings().current()
        assertEquals(ThemeModeSetting.System, settings.themeMode)
        assertEquals("Pink", settings.accent)
        assertEquals(false, settings.dynamicColor)
        assertEquals(ArticleOpener.AresReader, settings.articleOpener)
        assertEquals(false, settings.notificationsEnabled)
        assertEquals(true, settings.loadYouTubeImages)
        assertEquals(90, settings.retentionDays)
    }

    @Test
    fun changesAreSaved() = runBlocking {
        val repository = testSettings()
        repository.update { it.copy(themeMode = ThemeModeSetting.Dark, accent = "Blue", articleOpener = ArticleOpener.ExternalBrowser, retentionDays = 30) }
        val settings = repository.current()
        assertEquals(ThemeModeSetting.Dark, settings.themeMode)
        assertEquals("Blue", settings.accent)
        assertEquals(ArticleOpener.ExternalBrowser, settings.articleOpener)
        assertEquals(30, settings.retentionDays)
    }

    @Test
    fun retentionCanBeTurnedOff() = runBlocking {
        val repository = testSettings()
        repository.update { it.copy(retentionDays = null) }
        assertNull(repository.current().retentionDays)
    }
}
