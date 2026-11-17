// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class SwipeAction(@param:DrawableRes val icon: Int, val label: String, val onAction: () -> Unit)

@Composable
fun SwipeActionBox(
    modifier: Modifier = Modifier,
    startToEnd: SwipeAction? = null,
    endToStart: SwipeAction? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val currentStartToEnd by rememberUpdatedState(startToEnd)
    val currentEndToStart by rememberUpdatedState(endToStart)
    val onDismiss: (SwipeToDismissBoxValue) -> Unit = remember(state, scope) {
        { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> currentStartToEnd?.onAction?.invoke()
                SwipeToDismissBoxValue.EndToStart -> currentEndToStart?.onAction?.invoke()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            scope.launch { state.reset() }
        }
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = startToEnd != null,
        enableDismissFromEndToStart = endToStart != null,
        onDismiss = onDismiss,
        backgroundContent = {
            val action = when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> startToEnd
                SwipeToDismissBoxValue.EndToStart -> endToStart
                SwipeToDismissBoxValue.Settled -> null
            }
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 24.dp),
                horizontalArrangement = if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (action != null) {
                    Icon(painterResource(action.icon), contentDescription = action.label, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        },
        content = content,
    )
}
