// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.joaopires.ares.R
import com.joaopires.ares.ui.common.LocalAppContainer
import com.joaopires.ares.ui.entries.EntriesScreen
import com.joaopires.ares.ui.entries.EntriesViewModel
import com.joaopires.ares.ui.home.HomeDestination
import com.joaopires.ares.ui.home.HomeScreen
import com.joaopires.ares.ui.home.HomeViewModel
import com.joaopires.ares.ui.navigation.EntriesRoute
import com.joaopires.ares.ui.navigation.HomeRoute
import com.joaopires.ares.ui.navigation.ScopeKind
import com.joaopires.ares.ui.navigation.SearchRoute
import com.joaopires.ares.ui.navigation.scopeOf
import com.joaopires.ares.ui.search.SearchScreen
import com.joaopires.ares.ui.search.SearchViewModel

@Composable
fun AresApp() {
    val container = LocalAppContainer.current
    val navController = rememberNavController()
    val unreadTitle = stringResource(R.string.list_unread)
    val todayTitle = stringResource(R.string.list_today)
    val bookmarksTitle = stringResource(R.string.list_bookmarks)

    NavHost(navController = navController, startDestination = HomeRoute) {
        composable<HomeRoute> {
            val viewModel = viewModel {
                HomeViewModel(container.feeds, container.entries, container.settings, container.refresh, container.feedSource)
            }
            HomeScreen(
                viewModel = viewModel,
                onOpenList = { destination ->
                    navController.navigate(
                        when (destination) {
                            HomeDestination.Unread -> EntriesRoute(ScopeKind.Unread, title = unreadTitle)
                            HomeDestination.Today -> EntriesRoute(ScopeKind.Today, title = todayTitle)
                            HomeDestination.Bookmarks -> EntriesRoute(ScopeKind.Bookmarks, title = bookmarksTitle)
                            is HomeDestination.Feed -> EntriesRoute(ScopeKind.Feed, destination.id, destination.title)
                            is HomeDestination.Section -> EntriesRoute(ScopeKind.Section, destination.id, destination.name)
                        },
                    )
                },
                onSearch = { navController.navigate(SearchRoute(ScopeKind.All)) },
            )
        }
        composable<EntriesRoute> { entry ->
            val route = entry.toRoute<EntriesRoute>()
            val viewModel = viewModel {
                EntriesViewModel(scopeOf(route.kind, route.id), container.entries, container.settings, container.refresh)
            }
            EntriesScreen(
                viewModel = viewModel,
                title = route.title,
                clock = container.clock,
                onBack = { navController.popBackStack() },
                onSearch = { navController.navigate(SearchRoute(route.kind, route.id, route.title)) },
            )
        }
        composable<SearchRoute> { entry ->
            val route = entry.toRoute<SearchRoute>()
            val viewModel = viewModel { SearchViewModel(scopeOf(route.kind, route.id), container.entries, container.settings) }
            SearchScreen(
                viewModel = viewModel,
                scopeTitle = route.title.ifEmpty { null },
                clock = container.clock,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
