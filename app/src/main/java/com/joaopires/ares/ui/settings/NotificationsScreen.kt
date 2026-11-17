// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.joaopires.ares.R
import com.joaopires.ares.core.data.FeedRepository
import com.joaopires.ares.core.data.SettingsRepository
import com.joaopires.ares.notifications.notificationPermissionGranted
import com.joaopires.ares.notifications.notificationsAllowed
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FeedNotifyRow(val id: Long, val title: String, val notify: Boolean)

data class NotificationsUiState(val enabled: Boolean, val feeds: List<FeedNotifyRow>)

class NotificationsViewModel(private val settings: SettingsRepository, private val feeds: FeedRepository) : ViewModel() {

    val state: StateFlow<NotificationsUiState?> = combine(settings.settings, feeds.feeds) { appSettings, feedList ->
        NotificationsUiState(appSettings.notificationsEnabled, feedList.map { FeedNotifyRow(it.id, it.title, it.notify) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.update { it.copy(notificationsEnabled = enabled) } }
    }

    fun setFeed(feedId: Long, notify: Boolean) {
        viewModelScope.launch { feeds.setNotify(feedId, notify) }
    }

    fun setAll(notify: Boolean) {
        viewModelScope.launch { feeds.setNotifyForAll(notify) }
    }
}

fun openNotificationSettings(context: Context): Boolean = try {
    context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
} catch (_: ActivityNotFoundException) {
    false
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(viewModel: NotificationsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(notificationsAllowed(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = notificationsAllowed(context) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = notificationsAllowed(context)
        if (granted) viewModel.setEnabled(true)
    }

    fun turnOn() {
        if (notificationPermissionGranted(context)) {
            viewModel.setEnabled(true)
        } else {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notifications_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        val current = state
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("notifications-list")) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.notifications_switch)) },
                    trailingContent = { Switch(checked = current.enabled, onCheckedChange = null) },
                    modifier = Modifier
                        .toggleable(value = current.enabled, role = Role.Switch) { on -> if (on) turnOn() else viewModel.setEnabled(false) }
                        .testTag("notifications-switch"),
                )
                Text(
                    stringResource(R.string.notifications_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                if (!allowed) {
                    Column(Modifier.padding(16.dp).testTag("notifications-blocked")) {
                        Text(
                            stringResource(R.string.notifications_blocked),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(onClick = { openNotificationSettings(context) }, modifier = Modifier.padding(top = 8.dp)) {
                            Text(stringResource(R.string.notifications_open_system_settings))
                        }
                    }
                }
            }
            if (current.enabled) {
                item {
                    HorizontalDivider(Modifier.padding(top = 16.dp))
                    Text(
                        stringResource(R.string.notifications_feeds),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                    )
                    if (current.feeds.isEmpty()) {
                        Text(stringResource(R.string.notifications_no_feeds), modifier = Modifier.padding(horizontal = 16.dp))
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                            OutlinedButton(onClick = { viewModel.setAll(true) }, modifier = Modifier.testTag("notifications-all-on")) {
                                Text(stringResource(R.string.notifications_all_on))
                            }
                            OutlinedButton(onClick = { viewModel.setAll(false) }, modifier = Modifier.testTag("notifications-all-off")) {
                                Text(stringResource(R.string.notifications_all_off))
                            }
                        }
                    }
                }
                items(current.feeds, key = { it.id }) { feed ->
                    ListItem(
                        headlineContent = { Text(feed.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { Switch(checked = feed.notify, onCheckedChange = null) },
                        modifier = Modifier
                            .toggleable(value = feed.notify, role = Role.Switch) { viewModel.setFeed(feed.id, it) }
                            .testTag("notify-feed-${feed.id}"),
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
