// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.joaopires.ares.R
import com.joaopires.ares.ui.addfeed.AddFeedEvent
import com.joaopires.ares.ui.addfeed.AddFeedScreen
import com.joaopires.ares.ui.addfeed.AddFeedViewModel
import com.joaopires.ares.ui.addfeed.FeedPreviewScreen
import com.joaopires.ares.ui.addfeed.IncomingLink
import com.joaopires.ares.ui.article.EntryScreen
import com.joaopires.ares.ui.article.EntryViewModel
import com.joaopires.ares.ui.common.LocalAppContainer
import com.joaopires.ares.ui.entries.EntriesScreen
import com.joaopires.ares.ui.entries.EntriesViewModel
import com.joaopires.ares.ui.home.HomeDestination
import com.joaopires.ares.ui.home.HomeScreen
import com.joaopires.ares.ui.home.HomeViewModel
import com.joaopires.ares.ui.navigation.AddFeedRoute
import com.joaopires.ares.ui.navigation.EntriesRoute
import com.joaopires.ares.ui.navigation.EntryRoute
import com.joaopires.ares.ui.navigation.FeedPreviewRoute
import com.joaopires.ares.ui.navigation.HomeRoute
import com.joaopires.ares.ui.navigation.ImportRoute
import com.joaopires.ares.ui.navigation.ScopeKind
import com.joaopires.ares.ui.navigation.SearchRoute
import com.joaopires.ares.ui.navigation.scopeOf
import com.joaopires.ares.ui.opml.ImportScreen
import com.joaopires.ares.ui.opml.ImportViewModel
import com.joaopires.ares.ui.search.SearchScreen
import com.joaopires.ares.ui.search.SearchViewModel

private const val ADD_FEED_RESULT_TITLE = "addFeedResultTitle"
private const val ADD_FEED_RESULT_ALREADY = "addFeedResultAlready"

@Composable
fun AresApp(incoming: IncomingLink? = null, onIncomingHandled: () -> Unit = {}) {
    val container = LocalAppContainer.current
    val navController = rememberNavController()

    LaunchedEffect(incoming) {
        when (incoming) {
            is IncomingLink.AddFeed -> navController.navigate(AddFeedRoute(incoming.url, autoPreview = true)) { launchSingleTop = true }
            is IncomingLink.ImportOpml -> navController.navigate(ImportRoute(incoming.uri)) { launchSingleTop = true }
            null -> return@LaunchedEffect
        }
        onIncomingHandled()
    }

    fun finishAddFeed(result: AddFeedEvent.Finished) {
        navController.getBackStackEntry<HomeRoute>().savedStateHandle.apply {
            set(ADD_FEED_RESULT_ALREADY, result.alreadySubscribed)
            set(ADD_FEED_RESULT_TITLE, result.title.orEmpty())
        }
        navController.popBackStack<AddFeedRoute>(inclusive = true)
    }

    val unreadTitle = stringResource(R.string.list_unread)
    val todayTitle = stringResource(R.string.list_today)
    val bookmarksTitle = stringResource(R.string.list_bookmarks)

    NavHost(navController = navController, startDestination = HomeRoute) {
        composable<HomeRoute> { entry ->
            val viewModel = viewModel {
                HomeViewModel(container.feeds, container.entries, container.settings, container.refresh, container.imports, container.opml)
            }
            val addedTitle by entry.savedStateHandle.getStateFlow<String?>(ADD_FEED_RESULT_TITLE, null).collectAsStateWithLifecycle()
            LaunchedEffect(addedTitle) {
                val title = addedTitle ?: return@LaunchedEffect
                viewModel.onFeedAdded(title, entry.savedStateHandle.get<Boolean>(ADD_FEED_RESULT_ALREADY) == true)
                entry.savedStateHandle.remove<String>(ADD_FEED_RESULT_TITLE)
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
                onAddFeed = { navController.navigate(AddFeedRoute()) },
                onImport = { uri -> navController.navigate(ImportRoute(uri.toString())) },
            )
        }
        composable<AddFeedRoute> { entry ->
            val route = entry.toRoute<AddFeedRoute>()
            val viewModel = viewModel { AddFeedViewModel(route.url, route.autoPreview, container.feeds, container.feedSource) }
            AddFeedScreen(
                viewModel = viewModel,
                onClose = { navController.popBackStack() },
                onShowPreview = { navController.navigate(FeedPreviewRoute) { launchSingleTop = true } },
                onFinished = ::finishAddFeed,
            )
        }
        composable<FeedPreviewRoute> { entry ->
            val parent = remember(entry) { navController.getBackStackEntry<AddFeedRoute>() }
            val route = parent.toRoute<AddFeedRoute>()
            val viewModel = viewModel(viewModelStoreOwner = parent) {
                AddFeedViewModel(route.url, route.autoPreview, container.feeds, container.feedSource)
            }
            LaunchedEffect(viewModel) { viewModel.events.collect { if (it is AddFeedEvent.Finished) finishAddFeed(it) } }
            FeedPreviewScreen(
                viewModel = viewModel,
                clock = container.clock,
                onBack = {
                    viewModel.backToEditing()
                    navController.popBackStack()
                },
            )
        }
        composable<ImportRoute> { entry ->
            val route = entry.toRoute<ImportRoute>()
            val viewModel = viewModel { ImportViewModel(route.uri.toUri(), container.opml, container.feeds, container.imports) }
            ImportScreen(
                viewModel = viewModel,
                onClose = { navController.popBackStack() },
                onPreview = { url -> navController.navigate(AddFeedRoute(url, autoPreview = true)) },
                onStarted = { navController.popBackStack<HomeRoute>(inclusive = false) },
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
                onOpenEntry = { navController.navigate(EntryRoute(it)) },
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
                onOpenEntry = { navController.navigate(EntryRoute(it)) },
            )
        }
        composable<EntryRoute> { entry ->
            val route = entry.toRoute<EntryRoute>()
            val viewModel = viewModel { EntryViewModel(route.entryId, container.entries, container.articles, container.settings) }
            EntryScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onOpenEntry = { navController.navigate(EntryRoute(it)) },
            )
        }
    }
}
