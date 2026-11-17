// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

public enum class ThemeModeSetting { System, Light, Dark }

public enum class ArticleOpener { AresReader, InAppBrowser, ExternalBrowser }

public data class AppSettings(
    val themeMode: ThemeModeSetting = ThemeModeSetting.System,
    val accent: String = DEFAULT_ACCENT,
    val dynamicColor: Boolean = false,
    val articleOpener: ArticleOpener = ArticleOpener.AresReader,
    val hideUnreadMarkers: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val loadYouTubeImages: Boolean = true,
    val retentionDays: Int? = DEFAULT_RETENTION_DAYS,
    val backgroundRefreshMinutes: Int? = DEFAULT_BACKGROUND_REFRESH_MINUTES,
) {
    public companion object {
        public const val DEFAULT_ACCENT: String = "Pink"
        public const val DEFAULT_RETENTION_DAYS: Int = 90
        public const val DEFAULT_BACKGROUND_REFRESH_MINUTES: Int = 60
    }
}

public class SettingsRepository internal constructor(private val store: DataStore<Preferences>) {

    public val settings: Flow<AppSettings> = store.data.map { it.toSettings() }

    public suspend fun current(): AppSettings = settings.first()

    public suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { preferences ->
            val next = transform(preferences.toSettings())
            preferences[THEME_MODE] = next.themeMode.name
            preferences[ACCENT] = next.accent
            preferences[DYNAMIC_COLOR] = next.dynamicColor
            preferences[ARTICLE_OPENER] = next.articleOpener.name
            preferences[HIDE_UNREAD] = next.hideUnreadMarkers
            preferences[NOTIFICATIONS] = next.notificationsEnabled
            preferences[YOUTUBE_IMAGES] = next.loadYouTubeImages
            preferences[RETENTION_DAYS] = next.retentionDays ?: NEVER
            preferences[BACKGROUND_REFRESH] = next.backgroundRefreshMinutes ?: NEVER
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            themeMode = enumOrDefault(this[THEME_MODE], defaults.themeMode),
            accent = this[ACCENT] ?: defaults.accent,
            dynamicColor = this[DYNAMIC_COLOR] ?: defaults.dynamicColor,
            articleOpener = enumOrDefault(this[ARTICLE_OPENER], defaults.articleOpener),
            hideUnreadMarkers = this[HIDE_UNREAD] ?: defaults.hideUnreadMarkers,
            notificationsEnabled = this[NOTIFICATIONS] ?: defaults.notificationsEnabled,
            loadYouTubeImages = this[YOUTUBE_IMAGES] ?: defaults.loadYouTubeImages,
            retentionDays = when (val days = this[RETENTION_DAYS]) {
                null -> defaults.retentionDays
                NEVER -> null
                else -> days
            },
            backgroundRefreshMinutes = when (val minutes = this[BACKGROUND_REFRESH]) {
                null -> defaults.backgroundRefreshMinutes
                NEVER -> null
                else -> minutes
            },
        )
    }

    public companion object {
        private const val NEVER = 0
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val ACCENT = stringPreferencesKey("accent")
        private val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val ARTICLE_OPENER = stringPreferencesKey("article_opener")
        private val HIDE_UNREAD = booleanPreferencesKey("hide_unread_markers")
        private val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        private val YOUTUBE_IMAGES = booleanPreferencesKey("load_youtube_images")
        private val RETENTION_DAYS = intPreferencesKey("retention_days")
        private val BACKGROUND_REFRESH = intPreferencesKey("background_refresh_minutes")

        public fun create(file: File, scope: CoroutineScope): SettingsRepository =
            SettingsRepository(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))

        private inline fun <reified T : Enum<T>> enumOrDefault(value: String?, default: T): T =
            value?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default
    }
}
