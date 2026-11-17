// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.article

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joaopires.ares.R
import com.joaopires.ares.core.data.model.EntryDetail
import com.joaopires.ares.core.data.model.EntryEnclosure
import com.joaopires.ares.core.feed.FetchFailure
import com.joaopires.ares.ui.common.messageRes
import com.joaopires.ares.ui.common.openInBrowser
import com.joaopires.ares.ui.common.openLink
import com.joaopires.ares.ui.common.openWithType
import com.joaopires.ares.ui.common.share
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryScreen(viewModel: EntryViewModel, onBack: () -> Unit, onOpenEntry: (String) -> Unit) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val enclosures by viewModel.enclosures.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val showFull by viewModel.showFull.collectAsStateWithLifecycle()
    val loadingFull by viewModel.loadingFull.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val toolbarColor = colors.surface.toArgb()

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbar.showSnackbar(
                when (message) {
                    EntryMessage.MarkedUnread -> resources.getString(R.string.entry_marked_unread)
                    EntryMessage.NotExtractable -> resources.getString(R.string.full_article_not_extractable)
                    EntryMessage.NoLink -> resources.getString(R.string.full_article_no_link)
                    is EntryMessage.FullArticleFailed -> resources.getString(
                        R.string.full_article_failed,
                        if (message.failure is FetchFailure.Http) {
                            resources.getString(R.string.error_http, message.failure.code)
                        } else {
                            resources.getString(message.failure.messageRes())
                        },
                    )
                },
            )
        }
    }

    val noBrowser = stringResource(R.string.no_browser)
    fun reportIfFailed(opened: Boolean) {
        if (!opened) scope.launch { snackbar.showSnackbar(noBrowser) }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(detail?.feedTitle.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
                if (loadingFull) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            val entry = detail
            BottomAppBar(
                actions = {
                    IconToggleButton(checked = entry?.isBookmarked == true, onCheckedChange = { viewModel.toggleBookmark() }) {
                        Icon(
                            painterResource(if (entry?.isBookmarked == true) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark),
                            contentDescription = stringResource(if (entry?.isBookmarked == true) R.string.action_remove_bookmark else R.string.action_bookmark),
                        )
                    }
                    IconButton(onClick = viewModel::markUnread) {
                        Icon(painterResource(R.drawable.ic_mark_email_unread), contentDescription = stringResource(R.string.action_mark_unread))
                    }
                    val url = entry?.url
                    if (url != null) {
                        IconToggleButton(checked = showFull, onCheckedChange = { viewModel.toggleFullArticle() }, enabled = !loadingFull) {
                            Icon(
                                painterResource(R.drawable.ic_article),
                                contentDescription = stringResource(if (showFull) R.string.action_feed_content else R.string.action_full_article),
                            )
                        }
                        val shareTitle = stringResource(R.string.action_share)
                        IconButton(onClick = { reportIfFailed(share(context, entry.title, url, shareTitle)) }) {
                            Icon(painterResource(R.drawable.ic_share), contentDescription = shareTitle)
                        }
                        IconButton(onClick = { reportIfFailed(openLink(context, url, settings.articleOpener, toolbarColor)) }) {
                            Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = stringResource(R.string.action_open_original))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val entry = detail
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (entry == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                val html = articlePageHtml(entry, enclosures, showFull, colors)
                ArticleWebView(
                    html = html,
                    baseUrl = entry.url,
                    allowYouTube = settings.loadYouTubeImages,
                    onLink = { url ->
                        scope.launch {
                            when (val action = viewModel.resolveLink(url)) {
                                is LinkAction.OpenEntry -> onOpenEntry(action.entryId)
                                is LinkAction.OpenVideo -> reportIfFailed(openInBrowser(context, action.url))
                                is LinkAction.OpenAttachment -> reportIfFailed(openWithType(context, action.url, action.type))
                                is LinkAction.OpenLink ->
                                    if (action.url.startsWith("http", ignoreCase = true)) {
                                        reportIfFailed(openLink(context, action.url, settings.articleOpener, toolbarColor))
                                    } else {
                                        reportIfFailed(openInBrowser(context, action.url))
                                    }
                                LinkAction.Ignore -> Unit
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun articlePageHtml(entry: EntryDetail, enclosures: List<EntryEnclosure>, showFull: Boolean, colors: ColorScheme): String {
    val context = LocalContext.current
    val untitled = stringResource(R.string.entry_untitled)
    val labels = ArticleLabels(watch = stringResource(R.string.watch_video), attachments = stringResource(R.string.attachments_title))
    val date = remember(entry.publishedAt, entry.sortDate) {
        DateUtils.formatDateTime(
            context,
            (entry.publishedAt ?: entry.sortDate).toEpochMilli(),
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME,
        )
    }
    val articleColors = ArticleColors(
        background = colors.surface.css(),
        text = colors.onSurface.css(),
        secondaryText = colors.onSurfaceVariant.css(),
        accent = colors.primary.css(),
        accentContainer = colors.secondaryContainer.css(),
        onAccentContainer = colors.onSecondaryContainer.css(),
        outline = colors.outlineVariant.css(),
    )
    val attachments = enclosures.map { enclosure ->
        val size = enclosure.length?.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) }
        val name = enclosure.url.substringAfterLast('/').substringBefore('?').ifBlank { enclosure.url }
        ArticleAttachment(enclosure.url, listOfNotNull(name, enclosure.type, size).joinToString(" \u00B7 "))
    }
    val body = (if (showFull) entry.fullContentHtml else null) ?: entry.contentHtml.orEmpty()
    return remember(entry, enclosures, showFull, articleColors, labels, date) {
        articleHtml(
            ArticlePage(
                title = entry.title ?: untitled,
                feedTitle = entry.feedTitle,
                author = entry.author,
                date = date,
                bodyHtml = body,
                watchUrl = watchUrlFor(entry),
                watchThumbnailUrl = entry.videoId?.let { entry.thumbnailUrl },
                attachments = attachments,
            ),
            articleColors,
            labels,
        )
    }
}

private fun Color.css(): String = "#%06X".format(toArgb() and 0xFFFFFF)
