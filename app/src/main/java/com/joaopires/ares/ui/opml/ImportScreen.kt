// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.opml

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.joaopires.ares.R
import com.joaopires.ares.core.data.FeedRepository
import com.joaopires.ares.core.data.ImportCandidate
import com.joaopires.ares.core.opml.OpmlException
import com.joaopires.ares.core.opml.feeds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ImportRow(val url: String, val title: String, val section: String?, val alreadyFollowed: Boolean)

enum class ImportError { NotOpml, Malformed, TooLarge, Unreadable }

sealed interface ImportUiState {
    data object Loading : ImportUiState
    data class Failed(val error: ImportError) : ImportUiState
    data class Ready(val rows: List<ImportRow>, val selected: Set<String>) : ImportUiState {
        val groups: List<Pair<String?, List<ImportRow>>> get() = rows.groupBy { it.section }.toList()
        val selectable: List<ImportRow> get() = rows.filter { !it.alreadyFollowed && it.url in selected }
    }
}

class ImportViewModel(
    private val uri: Uri,
    private val opml: OpmlService,
    feeds: FeedRepository,
    private val imports: ImportController,
) : ViewModel() {

    private val parsed = MutableStateFlow<Result<List<ImportRow>>?>(null)
    private val selection = MutableStateFlow<Set<String>?>(null)

    val state: StateFlow<ImportUiState> = combine(parsed, feeds.feeds, selection) { result, followed, selected ->
        val rows = result?.getOrElse { return@combine ImportUiState.Failed(it.toImportError()) } ?: return@combine ImportUiState.Loading
        val followedUrls = followed.map { it.url }.toSet()
        val marked = rows.map { it.copy(alreadyFollowed = it.url in followedUrls) }
        ImportUiState.Ready(marked, selected ?: marked.filterNot { it.alreadyFollowed }.map { it.url }.toSet())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImportUiState.Loading)

    private val eventChannel = Channel<Unit>(Channel.BUFFERED)
    val started: Flow<Unit> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            parsed.value = try {
                Result.success(opml.read(uri).feeds().map { ImportRow(it.url, it.title, it.section, alreadyFollowed = false) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    fun toggle(url: String) {
        val ready = state.value as? ImportUiState.Ready ?: return
        selection.value = if (url in ready.selected) ready.selected - url else ready.selected + url
    }

    fun startImport() {
        val ready = state.value as? ImportUiState.Ready ?: return
        val candidates = ready.selectable.map { ImportCandidate(it.url, it.title, it.section) }
        if (candidates.isEmpty()) return
        imports.start(candidates)
        viewModelScope.launch { eventChannel.send(Unit) }
    }
}

private fun Throwable.toImportError(): ImportError = when (this) {
    is OpmlException.NotOpml -> ImportError.NotOpml
    is OpmlException.Malformed -> ImportError.Malformed
    is OpmlException.TooLarge -> ImportError.TooLarge
    else -> ImportError.Unreadable
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(viewModel: ImportViewModel, onClose: () -> Unit, onPreview: (String) -> Unit, onStarted: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.started.collect { onStarted() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_close))
                    }
                },
            )
        },
        floatingActionButton = {
            val ready = state as? ImportUiState.Ready
            val count = ready?.selectable?.size ?: 0
            if (count > 0) {
                ExtendedFloatingActionButton(
                    onClick = viewModel::startImport,
                    icon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
                    text = { Text(pluralStringResource(R.plurals.import_button, count, count)) },
                    modifier = Modifier.testTag("import-start"),
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val current = state) {
                ImportUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is ImportUiState.Failed -> Text(
                    stringResource(
                        when (current.error) {
                            ImportError.NotOpml -> R.string.import_error_not_opml
                            ImportError.Malformed -> R.string.import_error_malformed
                            ImportError.TooLarge -> R.string.import_error_too_large
                            ImportError.Unreadable -> R.string.import_error_unreadable
                        },
                    ),
                    modifier = Modifier.align(Alignment.Center).padding(32.dp).testTag("import-error"),
                )
                is ImportUiState.Ready -> if (current.rows.isEmpty()) {
                    Text(stringResource(R.string.import_nothing), modifier = Modifier.align(Alignment.Center).padding(32.dp))
                } else {
                    LazyColumn(Modifier.fillMaxSize().testTag("import-list")) {
                        current.groups.forEach { (section, rows) ->
                            item(key = "section-$section") {
                                Text(
                                    section ?: stringResource(R.string.import_no_section),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                                )
                            }
                            items(rows, key = { "feed-${it.url}" }) { row ->
                                ListItem(
                                    headlineContent = { Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = {
                                        Text(
                                            if (row.alreadyFollowed) stringResource(R.string.import_already_following) else row.url,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    leadingContent = {
                                        Checkbox(
                                            checked = row.alreadyFollowed || row.url in current.selected,
                                            onCheckedChange = { viewModel.toggle(row.url) },
                                            enabled = !row.alreadyFollowed,
                                            modifier = Modifier.testTag("import-check-${row.url}"),
                                        )
                                    },
                                    modifier = Modifier.clickable(enabled = !row.alreadyFollowed) { onPreview(row.url) },
                                )
                            }
                        }
                        item { Spacer(Modifier.height(88.dp)) }
                    }
                }
            }
        }
    }
}
