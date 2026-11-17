// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.images

import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import java.net.URI

class BlockedImageException(url: String) : Exception("image blocked by privacy settings: $url")

private val youTubeImageHosts = Regex("""(^|\.)ytimg\.com$""")

fun imageRequestUrl(url: String, allowYouTube: Boolean): String? {
    val upgraded = if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url
    if (!upgraded.startsWith("https://", ignoreCase = true)) return null
    val host = try {
        URI(upgraded).host?.lowercase()
    } catch (_: Exception) {
        null
    } ?: return null
    if (!allowYouTube && youTubeImageHosts.containsMatchIn(host)) return null
    return upgraded
}

class AresImageInterceptor(private val allowYouTube: () -> Boolean) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = (chain.request.data as? String) ?: chain.request.data.toString().takeIf { it.startsWith("http", ignoreCase = true) }
            ?: return chain.proceed()
        if (!url.startsWith("http", ignoreCase = true)) return chain.proceed()
        val allowed = imageRequestUrl(url, allowYouTube())
            ?: return ErrorResult(image = null, request = chain.request, throwable = BlockedImageException(url))
        if (allowed == url) return chain.proceed()
        return chain.withRequest(chain.request.newBuilder().data(allowed).build()).proceed()
    }
}
