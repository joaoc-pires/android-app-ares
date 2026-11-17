// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.entries

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.joaopires.ares.R
import com.joaopires.ares.core.data.model.EntryListItem
import com.joaopires.ares.ui.common.openInBrowser
import kotlinx.coroutines.launch
import java.time.Clock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntriesScreen(
    viewModel: EntriesViewModel,
    title: String,
    clock: Clock,
    onBack: () -> Unit,
    onSearch: () -> Unit,
) {
    val items = viewModel.items.collectAsLazyPagingItems()
    val unreadOnly by viewModel.unreadOnly.collectAsStateWithLifecycle()
    val hideUnread by viewModel.hideUnread.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val noBrowser = stringResource(R.string.no_browser)

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                is EntriesMessage.MarkedRead -> if (message.entryIds.isEmpty()) {
                    snackbar.showSnackbar(resources.getString(R.string.nothing_to_mark))
                } else {
                    val result = snackbar.showSnackbar(
                        message = resources.getQuantityString(R.plurals.marked_read, message.entryIds.size, message.entryIds.size),
                        actionLabel = resources.getString(R.string.action_undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.undoMarkRead(message.entryIds)
                }
                is EntriesMessage.RefreshFailed ->
                    snackbar.showSnackbar(resources.getQuantityString(R.plurals.refresh_failed, message.count, message.count))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (viewModel.canFilterUnread) {
                        IconToggleButton(checked = unreadOnly, onCheckedChange = { viewModel.toggleUnreadOnly() }) {
                            Icon(
                                painterResource(R.drawable.ic_filter_list),
                                contentDescription = stringResource(if (unreadOnly) R.string.action_show_all else R.string.action_unread_only),
                            )
                        }
                    }
                    IconButton(onClick = viewModel::markAllRead) {
                        Icon(painterResource(R.drawable.ic_done_all), contentDescription = stringResource(R.string.action_mark_all_read))
                    }
                    IconButton(onClick = onSearch) {
                        Icon(painterResource(R.drawable.ic_search), contentDescription = stringResource(R.string.action_search))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            EntryList(
                items = items,
                hideUnread = hideUnread,
                clock = clock,
                onOpen = { item ->
                    viewModel.markOpened(item)
                    val opened = item.url?.let { openInBrowser(context, it) } ?: false
                    if (!opened) scope.launch { snackbar.showSnackbar(noBrowser) }
                },
                onToggleRead = viewModel::toggleRead,
                onToggleBookmark = viewModel::toggleBookmark,
                emptyText = stringResource(R.string.entries_empty),
            )
        }
    }
}

@Composable
fun EntryList(
    items: LazyPagingItems<EntryListItem>,
    hideUnread: Boolean,
    clock: Clock,
    onOpen: (EntryListItem) -> Unit,
    onToggleRead: (EntryListItem) -> Unit,
    onToggleBookmark: (EntryListItem) -> Unit,
    emptyText: String,
) {
    val now = clock.instant()
    if (items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(32.dp)
                .testTag("entries-empty"),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painterResource(R.drawable.ic_ares_glyph),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(emptyText, style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("entry-list")) {
        items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
            val item = items[index] ?: return@items
            EntryCard(
                item = item,
                hideUnread = hideUnread,
                now = now,
                onOpen = { onOpen(item) },
                onToggleRead = { onToggleRead(item) },
                onToggleBookmark = { onToggleBookmark(item) },
            )
            HorizontalDivider()
        }
        item { Box(Modifier.fillMaxWidth().height(24.dp)) }
    }
}
