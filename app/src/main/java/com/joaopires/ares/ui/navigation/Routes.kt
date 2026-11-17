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
data class SearchRoute(val kind: ScopeKind, val id: Long = 0, val title: String = "")

fun scopeOf(kind: ScopeKind, id: Long): EntryScope = when (kind) {
    ScopeKind.All -> EntryScope.All
    ScopeKind.Unread -> EntryScope.Unread
    ScopeKind.Today -> EntryScope.Today
    ScopeKind.Bookmarks -> EntryScope.Bookmarks
    ScopeKind.Feed -> EntryScope.Feed(id)
    ScopeKind.Section -> EntryScope.Section(id)
}
