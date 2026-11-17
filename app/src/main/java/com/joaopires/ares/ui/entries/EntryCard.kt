// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.entries

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.joaopires.ares.R
import com.joaopires.ares.core.data.model.EntryListItem
import com.joaopires.ares.ui.common.FeedIcon
import com.joaopires.ares.ui.common.SwipeAction
import com.joaopires.ares.ui.common.SwipeActionBox
import com.joaopires.ares.ui.common.relativeTime
import java.time.Instant

private const val BANNER_MIN_SIDE = 100

fun EntryListItem.hasBanner(): Boolean {
    val width = thumbnailWidth ?: return false
    val height = thumbnailHeight ?: return false
    return thumbnailUrl != null && width > BANNER_MIN_SIDE && height > BANNER_MIN_SIDE && width > height
}

@Composable
fun EntryCard(
    item: EntryListItem,
    hideUnread: Boolean,
    now: Instant,
    onOpen: () -> Unit,
    onToggleRead: () -> Unit,
    onToggleBookmark: () -> Unit,
) {
    val bookmarkLabel = stringResource(if (item.isBookmarked) R.string.action_remove_bookmark else R.string.action_bookmark)
    val readLabel = stringResource(if (item.isRead) R.string.action_mark_unread else R.string.action_mark_read)
    SwipeActionBox(
        startToEnd = SwipeAction(if (item.isBookmarked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_add, bookmarkLabel, onToggleBookmark),
        endToStart = SwipeAction(if (item.isRead) R.drawable.ic_mark_email_unread else R.drawable.ic_drafts, readLabel, onToggleRead),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onOpen)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(bookmarkLabel) {
                            onToggleBookmark()
                            true
                        },
                        CustomAccessibilityAction(readLabel) {
                            onToggleRead()
                            true
                        },
                    )
                }
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("entry-${item.id}"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardHeader(item, hideUnread)
            if (item.hasBanner()) {
                AsyncImage(
                    model = item.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Title(item)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { Title(item) }
                    item.thumbnailUrl?.let { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.author.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    relativeTime(item.sortDate, now),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CardHeader(item: EntryListItem, hideUnread: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FeedIcon(item.feedIconUrl, item.feedSiteUrl, null, size = 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            item.feedTitle.orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (item.isBookmarked) {
            val description = stringResource(R.string.entry_bookmarked)
            Icon(
                painterResource(R.drawable.ic_bookmark_filled),
                contentDescription = description,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        if (!item.isRead && !hideUnread) {
            val description = stringResource(R.string.entry_unread)
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .semantics { contentDescription = description },
            )
        }
    }
}

@Composable
private fun Title(item: EntryListItem) {
    Text(
        item.title ?: stringResource(R.string.entry_untitled),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = if (item.isRead) FontWeight.Normal else FontWeight.SemiBold,
        color = if (item.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}
