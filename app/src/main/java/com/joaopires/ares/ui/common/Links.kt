// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.joaopires.ares.core.data.ArticleOpener

enum class EntryOpenAction { AresReader, CustomTab, ExternalBrowser }

fun entryOpenAction(opener: ArticleOpener): EntryOpenAction = when (opener) {
    ArticleOpener.AresReader -> EntryOpenAction.AresReader
    ArticleOpener.InAppBrowser -> EntryOpenAction.CustomTab
    ArticleOpener.ExternalBrowser -> EntryOpenAction.ExternalBrowser
}

fun openInCustomTab(context: Context, url: String, toolbarColor: Int): Boolean {
    if (CustomTabsClient.getPackageName(context, null) == null) return openInBrowser(context, url)
    val intent = CustomTabsIntent.Builder()
        .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(toolbarColor).build())
        .setShowTitle(true)
        .setShareState(CustomTabsIntent.SHARE_STATE_ON)
        .build()
    intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        intent.launchUrl(context, url.toUri())
        true
    } catch (_: ActivityNotFoundException) {
        openInBrowser(context, url)
    }
}

fun openLink(context: Context, url: String, opener: ArticleOpener, toolbarColor: Int): Boolean =
    if (opener == ArticleOpener.ExternalBrowser) openInBrowser(context, url) else openInCustomTab(context, url, toolbarColor)

fun openWithType(context: Context, url: String, type: String?): Boolean = try {
    val intent = Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (type.isNullOrBlank()) intent.data = url.toUri() else intent.setDataAndType(url.toUri(), type)
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    openInBrowser(context, url)
}

fun share(context: Context, title: String?, url: String, chooserTitle: String): Boolean = try {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, url)
        .apply { title?.let { putExtra(Intent.EXTRA_SUBJECT, it) } }
    context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}

fun openEntryItem(
    context: Context,
    url: String?,
    opener: ArticleOpener,
    toolbarColor: Int,
    onOpenInReader: () -> Unit,
    onOpenedOutside: () -> Unit,
): Boolean = when (entryOpenAction(opener)) {
    EntryOpenAction.AresReader -> {
        onOpenInReader()
        true
    }
    EntryOpenAction.CustomTab -> url != null && openInCustomTab(context, url, toolbarColor).also { if (it) onOpenedOutside() }
    EntryOpenAction.ExternalBrowser -> url != null && openInBrowser(context, url).also { if (it) onOpenedOutside() }
}
