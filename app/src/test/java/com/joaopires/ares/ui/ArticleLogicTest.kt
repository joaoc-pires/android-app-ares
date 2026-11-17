// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui

import com.joaopires.ares.core.data.ArticleOpener
import com.joaopires.ares.ui.article.ARTICLE_CSP
import com.joaopires.ares.ui.article.ArticleAttachment
import com.joaopires.ares.ui.article.ArticleColors
import com.joaopires.ares.ui.article.ArticleLabels
import com.joaopires.ares.ui.article.ArticlePage
import com.joaopires.ares.ui.article.LinkAction
import com.joaopires.ares.ui.article.RequestDecision
import com.joaopires.ares.ui.article.articleHtml
import com.joaopires.ares.ui.article.classifyLink
import com.joaopires.ares.ui.article.subresourceDecision
import com.joaopires.ares.ui.common.EntryOpenAction
import com.joaopires.ares.ui.common.entryOpenAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleLogicTest {

    private val colors = ArticleColors("#FFFFFF", "#111111", "#555555", "#FF2D55", "#FFE0E6", "#3D0010", "#CCCCCC")
    private val labels = ArticleLabels(watch = "Watch video", attachments = "Attachments")

    private fun page(
        title: String = "Title",
        body: String = "<p>Body</p>",
        watchUrl: String? = null,
        attachments: List<ArticleAttachment> = emptyList(),
    ) = ArticlePage(title, "Feed", "Ana", "16 Nov 2026", body, watchUrl, watchUrl?.let { "https://i.ytimg.com/vi/x/hqdefault.jpg" }, attachments)

    @Test
    fun templateHasAStrictContentSecurityPolicy() {
        val html = articleHtml(page(), colors, labels)
        assertTrue(html.contains("<meta http-equiv=\"Content-Security-Policy\" content=\"$ARTICLE_CSP\">"))
        assertTrue(ARTICLE_CSP.startsWith("default-src 'none'"))
        assertFalse(ARTICLE_CSP.contains("script-src"))
        assertFalse(html.contains("<script"))
    }

    @Test
    fun headerTextIsEscaped() {
        val html = articleHtml(page(title = "<script>alert(1)</script> & \"quotes\""), colors, labels)
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;quotes&quot;"))
        assertFalse(html.contains("<script>alert"))
    }

    @Test
    fun templateUsesThemeColoursAndAutoDirection() {
        val html = articleHtml(page(), colors, labels)
        assertTrue(html.contains("background: #FFFFFF"))
        assertTrue(html.contains("a { color: #FF2D55; }"))
        assertTrue(html.contains("<body dir=\"auto\">"))
        assertTrue(html.contains("Feed \u00B7 Ana \u00B7 16 Nov 2026"))
    }

    @Test
    fun videosGetAWatchLinkAndAttachmentsAreListed() {
        val html = articleHtml(
            page(watchUrl = "https://www.youtube.com/watch?v=abcdefghijk", attachments = listOf(ArticleAttachment("https://cdn.example/ep1.mp3", "ep1.mp3"))),
            colors,
            labels,
        )
        assertTrue(html.contains("<a class=\"ares-watch\" href=\"https://www.youtube.com/watch?v=abcdefghijk\">"))
        assertTrue(html.contains("Watch video"))
        assertTrue(html.contains("<a class=\"ares-attachment\" href=\"https://cdn.example/ep1.mp3\">ep1.mp3</a>"))
        assertFalse(html.contains("<iframe"))
    }

    @Test
    fun webViewOnlyLoadsHttpsAndBlocksTrackers() {
        assertEquals(RequestDecision.Allow, subresourceDecision("https://cdn.example/a.jpg", allowYouTube = true))
        assertEquals(RequestDecision.Allow, subresourceDecision("data:image/png;base64,AAAA", allowYouTube = true))
        assertEquals(RequestDecision.Block, subresourceDecision("http://cdn.example/a.jpg", allowYouTube = true))
        assertEquals(RequestDecision.Block, subresourceDecision("https://www.google-analytics.com/collect", allowYouTube = true))
        assertEquals(RequestDecision.Block, subresourceDecision("https://fonts.gstatic.com/s/roboto.woff2", allowYouTube = true))
        assertEquals(RequestDecision.Block, subresourceDecision("file:///etc/passwd", allowYouTube = true))
        assertEquals(RequestDecision.Allow, subresourceDecision("https://i.ytimg.com/vi/x/hqdefault.jpg", allowYouTube = true))
        assertEquals(RequestDecision.Block, subresourceDecision("https://i.ytimg.com/vi/x/hqdefault.jpg", allowYouTube = false))
    }

    @Test
    fun linksAreClassified() {
        val watch = "https://www.youtube.com/watch?v=abcdefghijk"
        val attachments = mapOf("https://cdn.example/ep1.mp3" to "audio/mpeg")
        assertEquals(LinkAction.OpenVideo(watch), classifyLink(watch, watch, attachments))
        assertEquals(LinkAction.OpenAttachment("https://cdn.example/ep1.mp3", "audio/mpeg"), classifyLink("https://cdn.example/ep1.mp3", watch, attachments))
        assertEquals(LinkAction.OpenLink("mailto:a@b.example"), classifyLink("mailto:a@b.example", watch, attachments))
        assertEquals(LinkAction.Ignore, classifyLink("javascript:alert(1)", watch, attachments))
        assertEquals(LinkAction.Ignore, classifyLink("about:blank", watch, attachments))
        assertNull(classifyLink("https://site.example/other", watch, attachments))
    }

    @Test
    fun openerSettingDecidesWhereEntriesOpen() {
        assertEquals(EntryOpenAction.AresReader, entryOpenAction(ArticleOpener.AresReader))
        assertEquals(EntryOpenAction.CustomTab, entryOpenAction(ArticleOpener.InAppBrowser))
        assertEquals(EntryOpenAction.ExternalBrowser, entryOpenAction(ArticleOpener.ExternalBrowser))
    }
}
