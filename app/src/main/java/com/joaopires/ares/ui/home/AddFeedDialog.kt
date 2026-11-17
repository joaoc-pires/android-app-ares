// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joaopires.ares.R
import com.joaopires.ares.core.feed.DiscoveredFeed
import com.joaopires.ares.ui.common.failureMessage

@Composable
fun AddFeedDialog(
    state: AddFeedState,
    onUrlChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onChoose: (DiscoveredFeed) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_feed_title)) },
        text = {
            when (state) {
                is AddFeedState.Editing -> Column {
                    OutlinedTextField(
                        value = state.url,
                        onValueChange = onUrlChange,
                        label = { Text(stringResource(R.string.add_feed_hint)) },
                        singleLine = true,
                        isError = state.error != null,
                        supportingText = state.error?.let { error -> { Text(addFeedErrorText(error)) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                        modifier = Modifier.fillMaxWidth().testTag("add-feed-url"),
                    )
                }
                is AddFeedState.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is AddFeedState.Choosing -> Column {
                    Text(stringResource(R.string.add_feed_choose))
                    state.candidates.forEach { candidate ->
                        ListItem(
                            headlineContent = { Text(candidate.title ?: candidate.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(candidate.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.clickable { onChoose(candidate) },
                        )
                    }
                }
                AddFeedState.Hidden -> Unit
            }
        },
        confirmButton = {
            if (state is AddFeedState.Editing) {
                TextButton(onClick = onSubmit, enabled = state.url.isNotBlank()) { Text(stringResource(R.string.action_add)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun addFeedErrorText(error: AddFeedError): String = when (error) {
    AddFeedError.InvalidUrl -> stringResource(R.string.error_invalid_url)
    is AddFeedError.Fetch -> failureMessage(error.failure)
}
