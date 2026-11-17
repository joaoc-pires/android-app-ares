// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.home

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joaopires.ares.R
import com.joaopires.ares.core.data.ImportSummary
import com.joaopires.ares.ui.common.FeedIcon
import com.joaopires.ares.ui.common.failureMessage
import com.joaopires.ares.ui.common.SwipeActionBox
import com.joaopires.ares.ui.common.SwipeAction
import com.joaopires.ares.ui.common.storedErrorMessage
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenList: (HomeDestination) -> Unit,
    onSearch: () -> Unit,
    onAddFeed: () -> Unit,
    onImport: (Uri) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onImport) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        uri?.let(viewModel::export)
    }
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var pendingDelete by remember { mutableStateOf<FeedRowUi?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                is HomeMessage.RefreshFailed ->
                    snackbar.showSnackbar(resources.getQuantityString(R.plurals.refresh_failed, message.count, message.count))
                is HomeMessage.MarkedRead -> {
                    if (message.entryIds.isEmpty()) {
                        snackbar.showSnackbar(resources.getString(R.string.nothing_to_mark))
                    } else {
                        val result = snackbar.showSnackbar(
                            message = resources.getQuantityString(R.plurals.marked_read, message.entryIds.size, message.entryIds.size),
                            actionLabel = resources.getString(R.string.action_undo),
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.undoMarkRead(message.entryIds)
                    }
                }
                is HomeMessage.FeedDeleted -> snackbar.showSnackbar(resources.getString(R.string.feed_deleted, message.title))
                is HomeMessage.Subscribed -> snackbar.showSnackbar(resources.getString(R.string.add_feed_subscribed, message.title))
                HomeMessage.AlreadySubscribed -> snackbar.showSnackbar(resources.getString(R.string.add_feed_already))
                is HomeMessage.Exported -> snackbar.showSnackbar(resources.getQuantityString(R.plurals.export_done, message.count, message.count))
                HomeMessage.ExportFailed -> snackbar.showSnackbar(resources.getString(R.string.export_failed))
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                painter = painterResource(R.drawable.ic_ares_glyph),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onSearch) {
                            Icon(painterResource(R.drawable.ic_search), contentDescription = stringResource(R.string.action_search))
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.opml_import)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        importPicker.launch(OPML_PICKER_TYPES)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.opml_export)) },
                                    leadingIcon = { Icon(painterResource(R.drawable.ic_upload), contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        exportPicker.launch("Ares-${LocalDate.now()}.opml")
                                    },
                                )
                            }
                        }
                    },
                )
                val importing = state.importing
                if (importing != null) {
                    LinearProgressIndicator(
                        progress = { if (importing.total == 0) 0f else importing.done.toFloat() / importing.total },
                        modifier = Modifier.fillMaxWidth().testTag("import-progress"),
                    )
                    Text(
                        stringResource(R.string.importing_progress, importing.done, importing.total),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                } else if (state.refreshing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("refresh-progress"))
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddFeed,
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text(stringResource(R.string.action_add_feed)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.isEmpty) {
                EmptyHome()
            } else {
                HomeList(
                    state = state,
                    onOpenList = onOpenList,
                    onMarkFeedRead = viewModel::markFeedRead,
                    onDeleteFeed = { pendingDelete = it },
                )
            }
        }
    }

    pendingDelete?.let { feed ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_feed_title)) },
            text = { Text(stringResource(R.string.delete_feed_body, feed.title)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFeed(feed)
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    state.importSummary?.let { summary ->
        ImportSummaryDialog(summary, onDismiss = viewModel::dismissImportSummary)
    }
}

sealed interface HomeDestination {
    data object Unread : HomeDestination
    data object Today : HomeDestination
    data object Bookmarks : HomeDestination
    data class Feed(val id: Long, val title: String) : HomeDestination
    data class Section(val id: Long, val name: String) : HomeDestination
}

@Composable
private fun HomeList(
    state: HomeUiState,
    onOpenList: (HomeDestination) -> Unit,
    onMarkFeedRead: (Long) -> Unit,
    onDeleteFeed: (FeedRowUi) -> Unit,
) {
    var collapsed by rememberSaveable { mutableStateOf(setOf<Long>()) }
    LazyColumn(Modifier.fillMaxSize().testTag("home-list")) {
        item(key = "smart-unread") {
            SmartListRow(
                R.drawable.ic_inbox,
                stringResource(R.string.list_unread),
                state.counts.unread.takeUnless { state.hideUnread },
                countIsUnread = true,
            ) {
                onOpenList(HomeDestination.Unread)
            }
        }
        item(key = "smart-today") {
            SmartListRow(R.drawable.ic_today, stringResource(R.string.list_today), state.counts.today) { onOpenList(HomeDestination.Today) }
        }
        item(key = "smart-bookmarks") {
            SmartListRow(R.drawable.ic_bookmark, stringResource(R.string.list_bookmarks), state.counts.bookmarks) {
                onOpenList(HomeDestination.Bookmarks)
            }
        }
        item(key = "divider") { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        items(state.unsectioned, key = { "feed-${it.id}" }) { feed ->
            FeedRow(feed, state.hideUnread, onOpenList, onMarkFeedRead, onDeleteFeed)
        }
        state.sections.forEach { section ->
            val isCollapsed = section.id in collapsed
            item(key = "section-${section.id}") {
                SectionHeader(
                    section = section,
                    collapsed = isCollapsed,
                    hideUnread = state.hideUnread,
                    onToggle = { collapsed = if (isCollapsed) collapsed - section.id else collapsed + section.id },
                    onOpen = { onOpenList(HomeDestination.Section(section.id, section.name)) },
                )
            }
            if (!isCollapsed) {
                items(section.feeds, key = { "feed-${it.id}" }) { feed ->
                    FeedRow(feed, state.hideUnread, onOpenList, onMarkFeedRead, onDeleteFeed)
                }
            }
        }
        item(key = "fab-space") { Spacer(Modifier.height(88.dp)) }
    }
}

@Composable
private fun SmartListRow(icon: Int, title: String, count: Int?, countIsUnread: Boolean = false, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = count?.let {
            {
                if (countIsUnread) UnreadCount(it) else Text(it.toString(), style = MaterialTheme.typography.labelLarge)
            }
        },
        modifier = Modifier.combinedClickable(onClick = onClick),
    )
}

@Composable
private fun SectionHeader(section: SectionUi, collapsed: Boolean, hideUnread: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val toggleLabel = stringResource(if (collapsed) R.string.action_expand_section else R.string.action_collapse_section, section.name)
    ListItem(
        headlineContent = {
            Text(section.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!hideUnread && section.unread > 0) UnreadCount(section.unread)
                IconButton(onClick = onToggle) {
                    Icon(
                        painterResource(if (collapsed) R.drawable.ic_expand_more else R.drawable.ic_expand_less),
                        contentDescription = toggleLabel,
                    )
                }
            }
        },
        modifier = Modifier.combinedClickable(onClick = onOpen),
    )
}

@Composable
private fun FeedRow(
    feed: FeedRowUi,
    hideUnread: Boolean,
    onOpenList: (HomeDestination) -> Unit,
    onMarkFeedRead: (Long) -> Unit,
    onDeleteFeed: (FeedRowUi) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val markReadLabel = stringResource(R.string.action_mark_all_read)
    val deleteLabel = stringResource(R.string.action_delete)
    SwipeActionBox(
        endToStart = SwipeAction(R.drawable.ic_done_all, markReadLabel) { onMarkFeedRead(feed.id) },
    ) {
        Box {
            ListItem(
                headlineContent = { Text(feed.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = { FeedIcon(feed.iconUrl, feed.siteUrl, feed.url) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        feed.error?.let { code ->
                            Icon(
                                painterResource(R.drawable.ic_warning),
                                contentDescription = stringResource(R.string.feed_error_indicator, storedErrorMessage(code)),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        if (!hideUnread && feed.unread > 0) UnreadCount(feed.unread)
                    }
                },
                modifier = Modifier
                    .combinedClickable(
                        onClick = { onOpenList(HomeDestination.Feed(feed.id, feed.title)) },
                        onLongClick = { menuOpen = true },
                    )
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(markReadLabel) {
                                onMarkFeedRead(feed.id)
                                true
                            },
                            CustomAccessibilityAction(deleteLabel) {
                                onDeleteFeed(feed)
                                true
                            },
                        )
                    }
                    .testTag("feed-${feed.id}"),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(markReadLabel) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_done_all), contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onMarkFeedRead(feed.id)
                    },
                )
                DropdownMenuItem(
                    text = { Text(deleteLabel) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onDeleteFeed(feed)
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyHome() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .testTag("home-empty"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_ares_glyph),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.home_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun UnreadCount(count: Int) {
    val description = pluralStringResource(R.plurals.unread_count, count, count)
    Text(
        count.toString(),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.semantics { contentDescription = description },
    )
}

private val OPML_PICKER_TYPES = arrayOf("text/x-opml", "application/x-opml", "text/xml", "application/xml", "application/octet-stream")

@Composable
private fun ImportSummaryDialog(summary: ImportSummary, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_summary_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pluralStringResource(R.plurals.import_summary_imported, summary.imported, summary.imported))
                if (summary.alreadySubscribed > 0) {
                    Text(pluralStringResource(R.plurals.import_summary_already, summary.alreadySubscribed, summary.alreadySubscribed))
                }
                if (summary.failures.isNotEmpty()) {
                    Text(pluralStringResource(R.plurals.import_summary_failed, summary.failures.size, summary.failures.size))
                    summary.failures.forEach { failure ->
                        Text(
                            "\u2022 ${failure.title ?: failure.url}: ${failureMessage(failure.failure)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}
