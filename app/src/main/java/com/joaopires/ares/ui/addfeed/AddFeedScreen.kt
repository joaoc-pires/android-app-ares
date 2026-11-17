// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.addfeed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joaopires.ares.R
import com.joaopires.ares.ui.common.FeedIcon
import com.joaopires.ares.ui.common.SectionDropdown
import com.joaopires.ares.ui.common.failureMessage
import com.joaopires.ares.ui.common.openInCustomTab
import com.joaopires.ares.ui.common.relativeTime
import java.net.URI
import java.time.Clock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFeedScreen(
    viewModel: AddFeedViewModel,
    onClose: () -> Unit,
    onShowPreview: () -> Unit,
    onFinished: (AddFeedEvent.Finished) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sections by viewModel.sections.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                AddFeedEvent.ShowPreview -> onShowPreview()
                is AddFeedEvent.Finished -> onFinished(event)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_feed_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_close))
                    }
                },
            )
        },
    ) { padding ->
        val phase = state.phase
        val busy = phase == AddFeedPhase.Loading || phase == AddFeedPhase.Subscribing
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val error = (phase as? AddFeedPhase.Editing)?.error
            OutlinedTextField(
                value = state.url,
                onValueChange = viewModel::onUrlChange,
                label = { Text(stringResource(R.string.add_feed_hint)) },
                singleLine = true,
                enabled = !busy,
                isError = error != null,
                supportingText = error?.let { { Text(addFeedErrorText(it)) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { viewModel.preview() }),
                modifier = Modifier.fillMaxWidth().testTag("add-feed-url"),
            )
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::onTitleChange,
                label = { Text(stringResource(R.string.add_feed_title_hint)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("add-feed-title"),
            )
            if (sections.isNotEmpty()) {
                SectionDropdown(sections, state.sectionId, viewModel::onSectionChange, enabled = !busy)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(Modifier.padding(end = 16.dp))
                }
                Button(onClick = viewModel::preview, enabled = !busy && state.url.isNotBlank(), modifier = Modifier.testTag("add-feed-preview")) {
                    Text(stringResource(R.string.action_preview))
                }
            }
            if (phase is AddFeedPhase.Choosing) {
                Text(stringResource(R.string.add_feed_choose), style = MaterialTheme.typography.titleSmall)
                phase.candidates.forEach { candidate ->
                    ListItem(
                        headlineContent = { Text(candidate.title ?: candidate.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(candidate.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.clickable { viewModel.chooseCandidate(candidate) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedPreviewScreen(viewModel: AddFeedViewModel, clock: Clock, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toolbarColor = MaterialTheme.colorScheme.surface.toArgb()
    val phase = state.phase
    val preview = (phase as? AddFeedPhase.Previewing)
    val feed = preview?.feed

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.preview_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            if (feed != null) {
                ExtendedFloatingActionButton(
                    onClick = viewModel::subscribe,
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    text = { Text(stringResource(R.string.action_subscribe)) },
                    modifier = Modifier.testTag("preview-subscribe"),
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (feed == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
                return@Box
            }
            val now = clock.instant()
            LazyColumn(Modifier.fillMaxSize().testTag("preview-list")) {
                item {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        FeedIcon(feed.iconUrl, feed.siteUrl, feed.url, size = 40.dp)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(state.title.ifBlank { feed.title ?: feed.url }, style = MaterialTheme.typography.titleLarge)
                            val host = feed.siteUrl?.let { runCatching { URI(it).host }.getOrNull() }
                            if (host != null) Text(host, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    feed.description?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
                        Spacer(Modifier.height(8.dp))
                    }
                    HorizontalDivider()
                }
                if (feed.entries.isEmpty()) {
                    item { Text(stringResource(R.string.preview_empty), modifier = Modifier.padding(16.dp)) }
                }
                items(feed.entries, key = { it.id }) { entry ->
                    ListItem(
                        headlineContent = {
                            Text(entry.title ?: stringResource(R.string.entry_untitled), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = entry.excerpt?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
                        trailingContent = entry.publishedAt?.let { { Text(relativeTime(it, now), style = MaterialTheme.typography.labelSmall) } },
                        modifier = Modifier.clickable(enabled = entry.url != null) {
                            entry.url?.let { openInCustomTab(context, it, toolbarColor) }
                        },
                    )
                    HorizontalDivider()
                }
                item { Spacer(Modifier.height(88.dp)) }
            }
        }
    }
}

@Composable
private fun addFeedErrorText(error: AddFeedError): String = when (error) {
    AddFeedError.InvalidUrl -> stringResource(R.string.error_invalid_url)
    is AddFeedError.Fetch -> failureMessage(error.failure)
}
