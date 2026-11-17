// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joaopires.ares.BuildConfig
import com.joaopires.ares.R
import com.joaopires.ares.core.data.AppSettings
import com.joaopires.ares.core.data.ArticleOpener
import com.joaopires.ares.core.data.LogLevel
import com.joaopires.ares.core.data.ThemeModeSetting
import com.joaopires.ares.ui.common.openInCustomTab
import com.joaopires.ares.ui.theme.AccentColor
import java.io.File
import java.time.LocalDate

const val PRIVACY_URL = "https://joaopires.com/ares/privacy"
const val TERMS_URL = "https://joaopires.com/ares/terms"
const val SOURCE_URL = "https://github.com/joaoc-pires/android-app-ares"
const val FEEDBACK_ADDRESS = "developer@joaopires.com"

private val OPML_PICKER_TYPES = arrayOf("text/x-opml", "application/x-opml", "text/xml", "application/xml", "application/octet-stream")

class SettingsNavigation(
    val onBack: () -> Unit,
    val onAppearance: () -> Unit,
    val onFilters: () -> Unit,
    val onImport: (Uri) -> Unit,
    val onLicences: () -> Unit,
    val onLogs: () -> Unit,
    val onNotifications: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
            }
        },
        actions = { actions() },
    )
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingRow(title: String, summary: String? = null, tag: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        modifier = Modifier
            .clickable(onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, summary: String? = null, tag: String? = null, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    choices: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    note: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                note?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                choices.forEach { (value, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = value == selected, role = Role.RadioButton) {
                                onSelect(value)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun openerLabel(opener: ArticleOpener): String = stringResource(
    when (opener) {
        ArticleOpener.AresReader -> R.string.opener_reader
        ArticleOpener.InAppBrowser -> R.string.opener_in_app
        ArticleOpener.ExternalBrowser -> R.string.opener_external
    },
)

@Composable
fun retentionLabel(days: Int?): String = when (days) {
    null -> stringResource(R.string.retention_forever)
    30 -> stringResource(R.string.retention_30)
    90 -> stringResource(R.string.retention_90)
    180 -> stringResource(R.string.retention_180)
    365 -> stringResource(R.string.retention_365)
    else -> days.toString()
}

@Composable
fun backgroundRefreshLabel(minutes: Int?): String = when (minutes) {
    null -> stringResource(R.string.background_refresh_off)
    30 -> stringResource(R.string.background_refresh_30)
    60 -> stringResource(R.string.background_refresh_60)
    180 -> stringResource(R.string.background_refresh_180)
    360 -> stringResource(R.string.background_refresh_360)
    else -> minutes.toString()
}

@Composable
fun themeLabel(mode: ThemeModeSetting): String = stringResource(
    when (mode) {
        ThemeModeSetting.System -> R.string.theme_system
        ThemeModeSetting.Light -> R.string.theme_light
        ThemeModeSetting.Dark -> R.string.theme_dark
    },
)

fun AccentColor.labelRes(): Int = when (this) {
    AccentColor.Red -> R.string.accent_red
    AccentColor.Orange -> R.string.accent_orange
    AccentColor.Yellow -> R.string.accent_yellow
    AccentColor.Green -> R.string.accent_green
    AccentColor.Mint -> R.string.accent_mint
    AccentColor.Teal -> R.string.accent_teal
    AccentColor.Cyan -> R.string.accent_cyan
    AccentColor.Blue -> R.string.accent_blue
    AccentColor.Indigo -> R.string.accent_indigo
    AccentColor.Purple -> R.string.accent_purple
    AccentColor.Pink -> R.string.accent_pink
    AccentColor.Brown -> R.string.accent_brown
}

fun AppSettings.accentColor(): AccentColor = AccentColor.entries.firstOrNull { it.name == accent } ?: AccentColor.Default

val supportsDynamicColor: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

private enum class SettingsDialog { Opener, BackgroundRefresh, Retention, DeleteCache, Feedback }

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, navigation: SettingsNavigation) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val toolbarColor = MaterialTheme.colorScheme.surface.toArgb()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(navigation.onImport) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        uri?.let(viewModel::export)
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                is SettingsMessage.RetentionApplied ->
                    snackbar.showSnackbar(resources.getQuantityString(R.plurals.retention_applied, message.count, message.count))
                is SettingsMessage.CacheDeleted ->
                    snackbar.showSnackbar(resources.getQuantityString(R.plurals.cache_deleted, message.count, message.count))
                is SettingsMessage.Exported ->
                    snackbar.showSnackbar(resources.getQuantityString(R.plurals.export_done, message.count, message.count))
                SettingsMessage.ExportFailed -> snackbar.showSnackbar(resources.getString(R.string.export_failed))
                is SettingsMessage.FeedbackReady ->
                    if (!composeFeedbackEmail(context, message.log)) snackbar.showSnackbar(resources.getString(R.string.no_email_app))
            }
        }
    }

    Scaffold(
        topBar = { SettingsTopBar(stringResource(R.string.settings_title), navigation.onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val current = settings
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            return@Scaffold
        }
        val accentName = stringResource(current.accentColor().labelRes())
        val appearanceSummary = themeLabel(current.themeMode) + ", " +
            if (current.dynamicColor && supportsDynamicColor) stringResource(R.string.settings_dynamic_color) else accentName
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("settings-list")) {
            item { GroupHeader(stringResource(R.string.settings_group_appearance)) }
            item {
                SettingRow(stringResource(R.string.settings_appearance), appearanceSummary, tag = "settings-appearance", onClick = navigation.onAppearance)
            }

            item { GroupHeader(stringResource(R.string.settings_group_reading)) }
            item {
                SettingRow(stringResource(R.string.settings_opener), openerLabel(current.articleOpener), tag = "settings-opener") {
                    dialog = SettingsDialog.Opener
                }
            }
            item {
                SettingRow(
                    stringResource(R.string.settings_background_refresh),
                    backgroundRefreshLabel(current.backgroundRefreshMinutes),
                    tag = "settings-background-refresh",
                ) { dialog = SettingsDialog.BackgroundRefresh }
            }
            item {
                SwitchRow(
                    stringResource(R.string.settings_youtube_images),
                    current.loadYouTubeImages,
                    stringResource(R.string.settings_youtube_images_summary),
                    tag = "settings-youtube",
                    onChange = viewModel::setLoadYouTubeImages,
                )
            }
            item {
                SwitchRow(
                    stringResource(R.string.settings_hide_unread),
                    current.hideUnreadMarkers,
                    tag = "settings-hide-unread",
                    onChange = viewModel::setHideUnreadMarkers,
                )
            }

            item { GroupHeader(stringResource(R.string.settings_group_feeds)) }
            item {
                SettingRow(
                    stringResource(R.string.settings_notifications),
                    stringResource(if (current.notificationsEnabled) R.string.settings_notifications_on else R.string.settings_notifications_off),
                    tag = "settings-notifications",
                    onClick = navigation.onNotifications,
                )
            }
            item { SettingRow(stringResource(R.string.action_filters), tag = "settings-filters", onClick = navigation.onFilters) }
            item { SettingRow(stringResource(R.string.opml_import)) { importPicker.launch(OPML_PICKER_TYPES) } }
            item { SettingRow(stringResource(R.string.opml_export)) { exportPicker.launch("Ares-${LocalDate.now()}.opml") } }

            item { GroupHeader(stringResource(R.string.settings_group_storage)) }
            item {
                SettingRow(
                    stringResource(R.string.settings_retention),
                    stringResource(R.string.settings_retention_summary, retentionLabel(current.retentionDays)),
                    tag = "settings-retention",
                ) { dialog = SettingsDialog.Retention }
            }
            item {
                SettingRow(
                    stringResource(R.string.settings_delete_cache),
                    stringResource(R.string.settings_delete_cache_summary),
                    tag = "settings-delete-cache",
                ) { dialog = SettingsDialog.DeleteCache }
            }

            item { GroupHeader(stringResource(R.string.settings_group_about)) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_version)) },
                    supportingContent = { Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") },
                )
            }
            item { SettingRow(stringResource(R.string.settings_privacy)) { openInCustomTab(context, PRIVACY_URL, toolbarColor) } }
            item { SettingRow(stringResource(R.string.settings_terms)) { openInCustomTab(context, TERMS_URL, toolbarColor) } }
            item {
                SettingRow(stringResource(R.string.settings_source), stringResource(R.string.settings_source_summary)) {
                    openInCustomTab(context, SOURCE_URL, toolbarColor)
                }
            }
            item { SettingRow(stringResource(R.string.settings_licences), tag = "settings-licences", onClick = navigation.onLicences) }
            item { SettingRow(stringResource(R.string.settings_feedback), tag = "settings-feedback") { dialog = SettingsDialog.Feedback } }
            item { SettingRow(stringResource(R.string.settings_logs), tag = "settings-logs", onClick = navigation.onLogs) }
            item { Spacer(Modifier.height(24.dp)) }
        }

        when (dialog) {
            SettingsDialog.Opener -> ChoiceDialog(
                title = stringResource(R.string.settings_opener),
                choices = ArticleOpener.entries.map { it to openerLabel(it) },
                selected = current.articleOpener,
                onSelect = viewModel::setOpener,
                onDismiss = { dialog = null },
            )
            SettingsDialog.BackgroundRefresh -> ChoiceDialog(
                title = stringResource(R.string.settings_background_refresh),
                choices = BACKGROUND_REFRESH_CHOICES.map { it to backgroundRefreshLabel(it) },
                selected = current.backgroundRefreshMinutes,
                onSelect = viewModel::setBackgroundRefresh,
                onDismiss = { dialog = null },
                note = stringResource(R.string.settings_background_refresh_note),
            )
            SettingsDialog.Retention -> ChoiceDialog(
                title = stringResource(R.string.settings_retention),
                choices = RETENTION_CHOICES.map { it to retentionLabel(it) },
                selected = current.retentionDays,
                onSelect = viewModel::setRetention,
                onDismiss = { dialog = null },
            )
            SettingsDialog.DeleteCache -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.delete_cache_title)) },
                text = { Text(stringResource(R.string.delete_cache_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        viewModel.deleteCache()
                    }) { Text(stringResource(R.string.action_delete)) }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_cancel)) } },
            )
            SettingsDialog.Feedback -> FeedbackDialog(
                onSend = { includeLog ->
                    dialog = null
                    viewModel.prepareFeedback(includeLog)
                },
                onDismiss = { dialog = null },
            )
            null -> Unit
        }
    }
}

@Composable
private fun FeedbackDialog(onSend: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var includeLog by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feedback_title)) },
        text = {
            Column {
                Text(stringResource(R.string.feedback_body))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .toggleable(value = includeLog, role = Role.Checkbox, onValueChange = { includeLog = it })
                        .testTag("feedback-include-log"),
                ) {
                    Checkbox(checked = includeLog, onCheckedChange = null)
                    Text(stringResource(R.string.feedback_include_log), modifier = Modifier.padding(start = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSend(includeLog) }) { Text(stringResource(R.string.feedback_send)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun AppearanceScreen(viewModel: SettingsViewModel, onBack: () -> Unit, dynamicColorAvailable: Boolean = supportsDynamicColor) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { SettingsTopBar(stringResource(R.string.settings_appearance), onBack) }) { padding ->
        val current = settings
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            GroupHeader(stringResource(R.string.settings_theme))
            Column(Modifier.selectableGroup()) {
                ThemeModeSetting.entries.forEach { mode ->
                    ListItem(
                        headlineContent = { Text(themeLabel(mode)) },
                        leadingContent = { RadioButton(selected = current.themeMode == mode, onClick = null) },
                        modifier = Modifier
                            .selectable(selected = current.themeMode == mode, role = Role.RadioButton) { viewModel.setThemeMode(mode) }
                            .testTag("theme-${mode.name}"),
                    )
                }
            }
            val dynamicOn = dynamicColorAvailable && current.dynamicColor
            if (dynamicColorAvailable) {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                SwitchRow(
                    stringResource(R.string.settings_dynamic_color),
                    current.dynamicColor,
                    stringResource(R.string.settings_dynamic_color_summary),
                    tag = "settings-dynamic-color",
                    onChange = viewModel::setDynamicColor,
                )
            }
            GroupHeader(stringResource(R.string.settings_accent))
            if (dynamicOn) {
                Text(
                    stringResource(R.string.settings_accent_dynamic),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            AccentGrid(selected = current.accentColor(), enabled = !dynamicOn, onSelect = viewModel::setAccent)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AccentGrid(selected: AccentColor, enabled: Boolean, onSelect: (AccentColor) -> Unit) {
    val resources = LocalResources.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .padding(16.dp)
            .selectableGroup()
            .alpha(if (enabled) 1f else 0.38f),
    ) {
        AccentColor.entries.forEach { accent ->
            val name = resources.getString(accent.labelRes())
            val isSelected = accent == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .background(accent.color, CircleShape)
                    .then(if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                    .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton) { onSelect(accent) }
                    .semantics { contentDescription = if (isSelected) resources.getString(R.string.accent_selected, name) else name }
                    .testTag("accent-${accent.name}"),
            ) {
                if (isSelected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Color.White)
            }
        }
    }
}

@Composable
fun LicencesScreen(viewModel: LicencesViewModel, onBack: () -> Unit) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toolbarColor = MaterialTheme.colorScheme.surface.toArgb()
    var open by remember { mutableStateOf<LibraryItem?>(null) }
    Scaffold(topBar = { SettingsTopBar(stringResource(R.string.licences_title), onBack) }) { padding ->
        val libraries = items
        if (libraries == null) {
            Box(Modifier.fillMaxSize().padding(padding)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("licences-list")) {
            item {
                Text(
                    stringResource(R.string.licences_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(libraries, key = { it.id }) { library ->
                ListItem(
                    headlineContent = { Text(library.name) },
                    supportingContent = {
                        Text(listOfNotNull(library.version, library.licences.joinToString { it.name }.ifEmpty { null }).joinToString(" · "))
                    },
                    modifier = Modifier.clickable { open = library },
                )
            }
        }
    }
    open?.let { library ->
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(library.name) },
            text = {
                Column {
                    library.version?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    library.licences.forEach { licence ->
                        val url = licence.url
                        TextButton(onClick = { if (url != null) openInCustomTab(context, url, toolbarColor) }, enabled = url != null) {
                            Text(licence.name)
                        }
                    }
                    library.website?.let { website ->
                        TextButton(onClick = { openInCustomTab(context, website, toolbarColor) }) { Text(website) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = null }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

@Composable
fun LogsScreen(viewModel: LogsViewModel, onBack: () -> Unit) {
    val lines by viewModel.lines.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.logs_share)
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(viewModel) { viewModel.shares.collect { shareFile(context, it, shareTitle) } }
    LaunchedEffect(lines?.size) {
        val count = lines?.size ?: 0
        if (count > 0) listState.scrollToItem(count)
    }

    Scaffold(
        topBar = {
            SettingsTopBar(stringResource(R.string.logs_title), onBack) {
                val hasLines = !lines.isNullOrEmpty()
                IconButton(onClick = viewModel::share, enabled = hasLines) {
                    Icon(painterResource(R.drawable.ic_share), contentDescription = shareTitle)
                }
                IconButton(onClick = { confirmClear = true }, enabled = hasLines) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.logs_clear))
                }
            }
        },
    ) { padding ->
        val current = lines
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            return@Scaffold
        }
        val colors = LogColors(
            info = MaterialTheme.colorScheme.primary,
            warning = MaterialTheme.colorScheme.tertiary,
            error = MaterialTheme.colorScheme.error,
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding).testTag("logs-list")) {
            item {
                Text(
                    stringResource(R.string.logs_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                if (current.isEmpty()) Text(stringResource(R.string.logs_empty), modifier = Modifier.padding(horizontal = 16.dp))
            }
            items(current) { line ->
                Text(
                    highlightLevel(line, colors),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.logs_clear_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clear()
                }) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

data class LogColors(val info: Color, val warning: Color, val error: Color)

fun highlightLevel(line: String, colors: LogColors): AnnotatedString = buildAnnotatedString {
    val level = LogLevel.entries.firstOrNull { line.contains(" ${it.label} ") }
    if (level == null) {
        append(line)
        return@buildAnnotatedString
    }
    val start = line.indexOf(" ${level.label} ") + 1
    append(line.substring(0, start))
    val color = when (level) {
        LogLevel.Info -> colors.info
        LogLevel.Warning -> colors.warning
        LogLevel.Error -> colors.error
    }
    withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(level.label) }
    append(line.substring(start + level.label.length))
}

private fun Context.shareableUri(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", file)

fun shareFile(context: Context, file: File, chooserTitle: String): Boolean = try {
    val uri = context.shareableUri(file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(file.name, uri)
    context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}

fun feedbackFooter(context: Context): String = context.getString(
    R.string.feedback_footer,
    BuildConfig.VERSION_NAME,
    BuildConfig.VERSION_CODE,
    Build.VERSION.RELEASE,
    Build.VERSION.SDK_INT,
)

fun composeFeedbackEmail(context: Context, log: File?): Boolean = try {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_ADDRESS))
        .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.feedback_subject))
        .putExtra(Intent.EXTRA_TEXT, "\n\n—\n" + feedbackFooter(context))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (log != null) {
        val uri = context.shareableUri(log)
        intent.putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(log.name, uri)
    }
    intent.selector = Intent(Intent.ACTION_SENDTO, "mailto:".toUri())
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
}
