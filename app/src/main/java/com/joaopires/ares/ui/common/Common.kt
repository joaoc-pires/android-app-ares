// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import com.joaopires.ares.AppContainer
import com.joaopires.ares.R
import com.joaopires.ares.core.feed.FetchFailure
import java.net.URI
import java.time.Instant

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

fun iconCandidates(iconUrl: String?, siteUrl: String?, feedUrl: String?): List<String> {
    val host = listOfNotNull(siteUrl, feedUrl).firstNotNullOfOrNull { hostOf(it) }
    return listOfNotNull(iconUrl, host?.let { "https://$it/favicon.ico" }).distinct()
}

private fun hostOf(url: String): String? = try {
    URI(url).host?.ifBlank { null }
} catch (_: Exception) {
    null
}

@Composable
fun FeedIcon(iconUrl: String?, siteUrl: String?, feedUrl: String?, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val candidates = remember(iconUrl, siteUrl, feedUrl) { iconCandidates(iconUrl, siteUrl, feedUrl) }
    var attempt by remember(candidates) { mutableIntStateOf(0) }
    val shape = RoundedCornerShape(size / 6)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        val model = candidates.getOrNull(attempt)
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { attempt++ },
                modifier = Modifier.size(size),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_ares_glyph),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(size / 6)
                    .size(size),
            )
        }
    }
}

fun relativeTime(instant: Instant, now: Instant): String =
    DateUtils.getRelativeTimeSpanString(instant.toEpochMilli(), now.toEpochMilli(), DateUtils.MINUTE_IN_MILLIS).toString()

fun openInBrowser(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}

@StringRes
fun FetchFailure.messageRes(): Int = when (this) {
    FetchFailure.Network -> R.string.error_network
    FetchFailure.Timeout -> R.string.error_timeout
    is FetchFailure.Http -> R.string.error_http
    FetchFailure.TooLarge -> R.string.error_too_large
    FetchFailure.NotAFeed -> R.string.error_not_a_feed
    FetchFailure.Malformed -> R.string.error_malformed
    FetchFailure.InsecureOnly -> R.string.error_insecure
}

@Composable
fun failureMessage(failure: FetchFailure): String =
    if (failure is FetchFailure.Http) stringResource(R.string.error_http, failure.code) else stringResource(failure.messageRes())

@Composable
fun storedErrorMessage(code: String): String = when {
    code == "Gone" -> stringResource(R.string.error_gone)
    code.startsWith("Http:") -> stringResource(R.string.error_http, code.removePrefix("Http:").toIntOrNull() ?: 0)
    else -> when (code) {
        "Timeout" -> stringResource(R.string.error_timeout)
        "TooLarge" -> stringResource(R.string.error_too_large)
        "NotAFeed" -> stringResource(R.string.error_not_a_feed)
        "Malformed" -> stringResource(R.string.error_malformed)
        "InsecureOnly" -> stringResource(R.string.error_insecure)
        else -> stringResource(R.string.error_network)
    }
}
