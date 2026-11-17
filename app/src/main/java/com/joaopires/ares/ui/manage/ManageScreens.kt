// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.manage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joaopires.ares.R
import com.joaopires.ares.core.data.db.FeedStatus
import com.joaopires.ares.ui.common.SectionDropdown
import com.joaopires.ares.ui.common.failureMessage
import com.joaopires.ares.ui.common.relativeTime
import com.joaopires.ares.ui.common.storedErrorMessage
import java.time.Clock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManageTopBar(title: String, onBack: () -> Unit, onSave: (() -> Unit)?, saveEnabled: Boolean = true) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
            }
        },
        actions = {
            if (onSave != null) {
                TextButton(onClick = onSave, enabled = saveEnabled, modifier = Modifier.testTag("manage-save")) {
                    Text(stringResource(R.string.action_save))
                }
            }
        },
    )
}

@Composable
fun FeedSettingsScreen(viewModel: FeedSettingsViewModel, clock: Clock, onBack: () -> Unit, onDeleted: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.events.collect { if (it == ManageEvent.Deleted) onDeleted() else onBack() } }

    Scaffold(
        topBar = {
            ManageTopBar(stringResource(R.string.feed_settings_title), onBack, viewModel::save, saveEnabled = form?.saving == false)
        },
    ) { padding ->
        val current = form
        val feed = settings
        if (current == null || feed == null) {
            Box(Modifier.fillMaxSize().padding(padding)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = current.title,
                onValueChange = viewModel::onTitleChange,
                label = { Text(stringResource(R.string.feed_settings_title_field)) },
                placeholder = { Text(feed.title ?: feed.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingText = {
                    Text(stringResource(R.string.feed_settings_title_default, feed.title ?: feed.url), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("feed-settings-title"),
            )
            val error = current.error
            OutlinedTextField(
                value = current.url,
                onValueChange = viewModel::onUrlChange,
                label = { Text(stringResource(R.string.feed_settings_address)) },
                singleLine = true,
                isError = error != null,
                supportingText = when {
                    current.saving -> {
                        { Text(stringResource(R.string.feed_settings_checking)) }
                    }
                    error != null -> {
                        { Text(feedSettingsErrorText(error)) }
                    }
                    else -> null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().testTag("feed-settings-url"),
            )
            SectionDropdown(sections, current.sectionId, viewModel::onSectionChange)

            HorizontalDivider()
            val status = when {
                feed.status == FeedStatus.Gone -> stringResource(R.string.feed_settings_gone)
                feed.lastError != null -> storedErrorMessage(feed.lastError!!)
                feed.lastSuccessAt != null -> stringResource(R.string.feed_settings_last_updated, relativeTime(feed.lastSuccessAt!!, clock.instant()))
                else -> stringResource(R.string.feed_settings_never_updated)
            }
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (feed.status == FeedStatus.Gone || feed.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("feed-settings-status"),
            )
            if (feed.status == FeedStatus.Gone || feed.lastError != null) {
                OutlinedButton(onClick = viewModel::tryAgain) { Text(stringResource(R.string.action_try_again)) }
            }
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("feed-settings-delete")) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = null)
                Text(stringResource(R.string.action_delete_feed), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

    if (confirmDelete) {
        val name = settings?.let { it.customTitle ?: it.title ?: it.url }.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_feed_title)) },
            text = { Text(stringResource(R.string.delete_feed_body, name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun feedSettingsErrorText(error: FeedSettingsError): String = when (error) {
    FeedSettingsError.InvalidUrl -> stringResource(R.string.error_invalid_url)
    FeedSettingsError.UrlTaken -> stringResource(R.string.feed_settings_url_taken)
    is FeedSettingsError.Fetch -> failureMessage(error.failure)
}

@Composable
fun SectionScreen(viewModel: SectionViewModel, onDone: () -> Unit) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) { viewModel.events.collect { onDone() } }

    Scaffold(
        topBar = {
            ManageTopBar(
                stringResource(if (viewModel.isNew) R.string.section_new_title else R.string.section_edit_title),
                onDone,
                viewModel::save,
                saveEnabled = form?.saving == false,
            )
        },
    ) { padding ->
        val current = form
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("section-list")) {
            item {
                OutlinedTextField(
                    value = current.name,
                    onValueChange = viewModel::onNameChange,
                    label = { Text(stringResource(R.string.section_name)) },
                    singleLine = true,
                    isError = current.error != null,
                    supportingText = current.error?.let { error ->
                        {
                            Text(
                                stringResource(if (error == SectionError.Empty) R.string.section_name_empty else R.string.section_name_taken),
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .testTag("section-name"),
                )
                Text(
                    stringResource(if (rows.isEmpty()) R.string.section_no_feeds else R.string.section_feeds),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(rows, key = { it.id }) { row ->
                ListItem(
                    headlineContent = { Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = row.otherSection?.let { { Text(stringResource(R.string.section_currently_in, it)) } },
                    leadingContent = {
                        Checkbox(
                            checked = row.id in current.selected,
                            onCheckedChange = { viewModel.toggle(row.id) },
                            modifier = Modifier.testTag("section-check-${row.id}"),
                        )
                    },
                    modifier = Modifier.clickable { viewModel.toggle(row.id) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun FiltersScreen(viewModel: FiltersViewModel, onBack: () -> Unit) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var confirmCount by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                is FiltersMessage.ConfirmApply -> confirmCount = message.count
                FiltersMessage.NothingMatches -> snackbar.showSnackbar(resources.getString(R.string.filters_nothing_matches))
                is FiltersMessage.Applied ->
                    snackbar.showSnackbar(resources.getQuantityString(R.plurals.filters_applied, message.count, message.count))
            }
        }
    }

    Scaffold(
        topBar = { ManageTopBar(stringResource(R.string.filters_title), onBack, onSave = null) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::add,
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text(stringResource(R.string.filters_add)) },
                modifier = Modifier.testTag("filters-add"),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val filters = items
        if (filters == null) {
            Box(Modifier.fillMaxSize().padding(padding)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("filters-list")) {
            item {
                Text(
                    stringResource(R.string.filters_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                if (filters.isEmpty()) {
                    Text(stringResource(R.string.filters_empty), modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            items(filters, key = { it.id }) { filter ->
                var text by remember(filter.id) { mutableStateOf(filter.expression) }
                val deleteLabel = stringResource(R.string.filters_delete, text)
                ListItem(
                    headlineContent = {
                        OutlinedTextField(
                            value = text,
                            onValueChange = {
                                text = it
                                viewModel.update(filter.id, it)
                            },
                            label = { Text(stringResource(R.string.filters_expression)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("filter-${filter.id}"),
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { viewModel.delete(filter.id) }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = deleteLabel)
                        }
                    },
                )
            }
            if (filters.isNotEmpty()) {
                item {
                    OutlinedButton(
                        onClick = viewModel::requestApply,
                        modifier = Modifier.padding(16.dp).testTag("filters-apply"),
                    ) { Text(stringResource(R.string.filters_apply)) }
                }
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
    }

    confirmCount?.let { count ->
        AlertDialog(
            onDismissRequest = { confirmCount = null },
            title = { Text(stringResource(R.string.filters_apply_title)) },
            text = { Text(pluralStringResource(R.plurals.filters_apply_body, count, count)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCount = null
                    viewModel.confirmApply()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmCount = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
