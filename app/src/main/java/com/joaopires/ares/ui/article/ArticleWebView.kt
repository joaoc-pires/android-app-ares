// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.article

import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

private const val FALLBACK_BASE_URL = "https://ares.invalid/"

private class ArticleWebViewClient(
    var allowYouTube: Boolean,
    var onLink: (String) -> Unit,
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        when (subresourceDecision(request.url.toString(), allowYouTube)) {
            RequestDecision.Allow -> null
            RequestDecision.Block -> WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        onLink(request.url.toString())
        return true
    }
}

@Composable
fun ArticleWebView(
    html: String,
    baseUrl: String?,
    allowYouTube: Boolean,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnLink by rememberUpdatedState(onLink)
    val client = remember { ArticleWebViewClient(allowYouTube) { currentOnLink(it) } }
    val textZoom = (LocalDensity.current.fontScale * 100).toInt()
    AndroidView(
        modifier = modifier.testTag("article-webview"),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.apply {
                    javaScriptEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    setGeolocationEnabled(false)
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    safeBrowsingEnabled = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    builtInZoomControls = false
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                webViewClient = client
            }
        },
        update = { view ->
            client.allowYouTube = allowYouTube
            view.settings.textZoom = textZoom
            if (view.tag != html) {
                view.tag = html
                view.loadDataWithBaseURL(baseUrl ?: FALLBACK_BASE_URL, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}
