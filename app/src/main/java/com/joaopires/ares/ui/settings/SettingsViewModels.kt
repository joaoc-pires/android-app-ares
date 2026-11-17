// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joaopires.ares.core.data.AppSettings
import com.joaopires.ares.core.data.ArticleOpener
import com.joaopires.ares.core.data.EntryRepository
import com.joaopires.ares.core.data.FileEventLog
import com.joaopires.ares.core.data.SettingsRepository
import com.joaopires.ares.core.data.ThemeModeSetting
import com.joaopires.ares.ui.opml.OpmlService
import com.joaopires.ares.ui.theme.AccentColor
import com.mikepenz.aboutlibraries.Libs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

val RETENTION_CHOICES: List<Int?> = listOf(30, 90, 180, 365, null)

val BACKGROUND_REFRESH_CHOICES: List<Int?> = listOf(null, 30, 60, 180, 360)

sealed interface SettingsMessage {
    data class RetentionApplied(val count: Int) : SettingsMessage
    data class CacheDeleted(val count: Int) : SettingsMessage
    data class Exported(val count: Int) : SettingsMessage
    data object ExportFailed : SettingsMessage
    data class FeedbackReady(val log: File?) : SettingsMessage
}

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val entries: EntryRepository,
    private val opml: OpmlService,
    private val log: FileEventLog,
    private val shareDirectory: File,
) : ViewModel() {

    val state: StateFlow<AppSettings?> = settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val messageChannel = Channel<SettingsMessage>(Channel.BUFFERED)
    val messages: Flow<SettingsMessage> = messageChannel.receiveAsFlow()

    private fun change(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settings.update(transform) }
    }

    fun setThemeMode(mode: ThemeModeSetting) = change { it.copy(themeMode = mode) }

    fun setDynamicColor(enabled: Boolean) = change { it.copy(dynamicColor = enabled) }

    fun setAccent(accent: AccentColor) = change { it.copy(accent = accent.name) }

    fun setOpener(opener: ArticleOpener) = change { it.copy(articleOpener = opener) }

    fun setLoadYouTubeImages(enabled: Boolean) = change { it.copy(loadYouTubeImages = enabled) }

    fun setHideUnreadMarkers(enabled: Boolean) = change { it.copy(hideUnreadMarkers = enabled) }

    fun setBackgroundRefresh(minutes: Int?) = change { it.copy(backgroundRefreshMinutes = minutes) }

    fun setRetention(days: Int?) {
        viewModelScope.launch {
            settings.update { it.copy(retentionDays = days) }
            val removed = entries.applyRetention(days)
            if (removed > 0) messageChannel.send(SettingsMessage.RetentionApplied(removed))
        }
    }

    fun deleteCache() {
        viewModelScope.launch { messageChannel.send(SettingsMessage.CacheDeleted(entries.deleteCache())) }
    }

    fun export(uri: Uri) {
        viewModelScope.launch {
            messageChannel.send(
                try {
                    SettingsMessage.Exported(opml.export(uri))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    SettingsMessage.ExportFailed
                },
            )
        }
    }

    fun prepareFeedback(includeLog: Boolean) {
        viewModelScope.launch {
            messageChannel.send(SettingsMessage.FeedbackReady(if (includeLog) log.copyForSharing(shareDirectory) else null))
        }
    }
}

class LogsViewModel(private val log: FileEventLog, private val shareDirectory: File) : ViewModel() {

    val lines: StateFlow<List<String>?> = log.version
        .map { log.read().lines().filter(String::isNotBlank) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val shareChannel = Channel<File>(Channel.BUFFERED)
    val shares: Flow<File> = shareChannel.receiveAsFlow()

    fun share() {
        viewModelScope.launch { log.copyForSharing(shareDirectory)?.let { shareChannel.send(it) } }
    }

    fun clear() {
        viewModelScope.launch { log.clear() }
    }
}

suspend fun FileEventLog.copyForSharing(directory: File): File? {
    val text = read()
    if (text.isBlank()) return null
    return withContext(Dispatchers.IO) {
        directory.mkdirs()
        File(directory, "Ares.log").apply { writeText(text) }
    }
}

data class LicenceLink(val name: String, val url: String?)

data class LibraryItem(
    val id: String,
    val name: String,
    val version: String?,
    val website: String?,
    val licences: List<LicenceLink>,
)

class LicencesViewModel(private val loadJson: suspend () -> String) : ViewModel() {

    private val itemsState = MutableStateFlow<List<LibraryItem>?>(null)
    val items: StateFlow<List<LibraryItem>?> = itemsState.asStateFlow()

    init {
        viewModelScope.launch {
            itemsState.value = try {
                parseLibraries(loadJson())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}

fun parseLibraries(json: String): List<LibraryItem> =
    Libs.Builder().withJson(json).build().libraries
        .map { library ->
            LibraryItem(
                id = library.uniqueId,
                name = library.name,
                version = library.artifactVersion,
                website = library.website?.let(::secureUrl),
                licences = library.licenses.map { LicenceLink(it.name, it.url?.let(::secureUrl)) }.sortedBy { it.name },
            )
        }
        .distinctBy { it.id }
        .sortedBy { it.name.lowercase() }

fun secureUrl(url: String): String? {
    val trimmed = url.trim()
    return when {
        trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        trimmed.startsWith("http://", ignoreCase = true) -> "https://" + trimmed.substring("http://".length)
        else -> null
    }
}
