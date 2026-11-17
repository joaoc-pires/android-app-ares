// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.navigation

import androidx.annotation.Keep
import com.joaopires.ares.core.data.model.EntryScope
import kotlinx.serialization.Serializable

@Keep
enum class ScopeKind { All, Unread, Today, Bookmarks, Feed, Section }

@Serializable
data object HomeRoute

@Serializable
data class EntriesRoute(val kind: ScopeKind, val id: Long = 0, val title: String = "")

@Serializable
data class EntryRoute(val entryId: String)

@Serializable
data class AddFeedRoute(val url: String = "", val autoPreview: Boolean = false)

@Serializable
data object FeedPreviewRoute

@Serializable
data class ImportRoute(val uri: String)

@Serializable
data class FeedSettingsRoute(val feedId: Long)

@Serializable
data class SectionRoute(val sectionId: Long = NEW_SECTION)

const val NEW_SECTION: Long = -1L

@Serializable
data object FiltersRoute

@Serializable
data object SettingsRoute

@Serializable
data object AppearanceRoute

@Serializable
data object LicencesRoute

@Serializable
data object LogsRoute

@Serializable
data object NotificationsRoute

@Serializable
data class SearchRoute(val kind: ScopeKind, val id: Long = 0, val title: String = "")

fun scopeOf(kind: ScopeKind, id: Long): EntryScope = when (kind) {
    ScopeKind.All -> EntryScope.All
    ScopeKind.Unread -> EntryScope.Unread
    ScopeKind.Today -> EntryScope.Today
    ScopeKind.Bookmarks -> EntryScope.Bookmarks
    ScopeKind.Feed -> EntryScope.Feed(id)
    ScopeKind.Section -> EntryScope.Section(id)
}
