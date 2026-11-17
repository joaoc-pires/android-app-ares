// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui

import com.joaopires.ares.ui.common.iconCandidates
import com.joaopires.ares.ui.addfeed.normalizeFeedInput
import com.joaopires.ares.ui.images.imageRequestUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PureLogicTest {

    @Test
    fun imagesAreUpgradedToHttps() {
        assertEquals("https://cdn.example/a.png", imageRequestUrl("http://cdn.example/a.png", allowYouTube = true))
        assertEquals("https://cdn.example/a.png", imageRequestUrl("https://cdn.example/a.png", allowYouTube = true))
        assertNull(imageRequestUrl("ftp://cdn.example/a.png", allowYouTube = true))
        assertNull(imageRequestUrl("https://", allowYouTube = true))
    }

    @Test
    fun youTubeImagesCanBeBlocked() {
        val thumbnail = "https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg"
        assertEquals(thumbnail, imageRequestUrl(thumbnail, allowYouTube = true))
        assertNull(imageRequestUrl(thumbnail, allowYouTube = false))
        assertNull(imageRequestUrl("http://i1.ytimg.com/vi/x/hqdefault.jpg", allowYouTube = false))
        assertEquals("https://notytimg.com.example/a.jpg", imageRequestUrl("https://notytimg.com.example/a.jpg", allowYouTube = false))
    }

    @Test
    fun feedIconsUseNoThirdPartyService() {
        assertEquals(
            listOf("https://site.example/icon.png", "https://site.example/favicon.ico"),
            iconCandidates("https://site.example/icon.png", "https://site.example/blog", "https://feeds.example/rss"),
        )
        assertEquals(listOf("https://feeds.example/favicon.ico"), iconCandidates(null, null, "https://feeds.example/rss"))
        assertEquals(emptyList<String>(), iconCandidates(null, null, null))
    }

    @Test
    fun feedInputIsNormalised() {
        assertEquals("https://blog.example", normalizeFeedInput("  blog.example "))
        assertEquals("https://blog.example/feed", normalizeFeedInput("feed://blog.example/feed"))
        assertEquals("https://blog.example/feed", normalizeFeedInput("feeds://blog.example/feed"))
        assertEquals("https://blog.example/feed", normalizeFeedInput("feed:https://blog.example/feed"))
        assertEquals("http://blog.example/feed", normalizeFeedInput("http://blog.example/feed"))
        assertNull(normalizeFeedInput(""))
        assertNull(normalizeFeedInput("not a url"))
        assertNull(normalizeFeedInput("localhost"))
        assertNull(normalizeFeedInput("ftp://blog.example/feed"))
    }
}
