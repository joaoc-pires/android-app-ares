// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.feed

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.net.InetAddress
import java.time.Clock
import java.time.ZoneOffset

class ArticleTest {

    private fun html(name: String) = String(fixtureBytes(name))

    @Test
    fun extractsTheArticleBodyAndDropsTheClutter() {
        val article = extractArticle(html("article-wordpress.html"), "https://workshop.example/2026/11/bicycle/")!!
        assertTrue(article.html.contains("steel touring frame"))
        assertTrue(article.html.contains("good things are often worth repairing"))
        assertFalse(article.html.contains("Popular posts"))
        assertFalse(article.html.contains("All rights reserved"))
        assertFalse(article.html.contains("<script"))
        assertFalse(article.html.contains("googletagmanager"))
        assertTrue(article.html.contains("src=\"https://workshop.example/wp-content/uploads/2026/11/frame-1600x900.jpg\""))
        assertEquals("Restoring an old bicycle", article.title?.substringBefore(" \u2013")?.trim())
    }

    @Test
    fun handlesClutteredNewsPages() {
        val article = extractArticle(html("article-news-cluttered.html"), "https://news.example/tram")!!
        assertTrue(article.html.contains("tram line connecting the riverside district"))
        assertTrue(article.html.contains("public consultation"))
        assertFalse(article.html.contains("Sign up for our newsletter"))
        assertFalse(article.html.contains("We use cookies"))
        assertFalse(article.html.contains("Bus fares to rise"))
    }

    @Test
    fun pagesThatAreNotArticlesAreRejected() {
        assertNull(extractArticle(html("not-article.html"), "https://login.example/"))
        assertNull(extractArticle("", "https://empty.example/"))
        assertNull(extractArticle("<html><body><p>Too short.</p></body></html>", "https://short.example/"))
    }

    @Test
    fun blockedHostsIncludeSubdomains() {
        assertTrue(isBlockedHost("www.google-analytics.com"))
        assertTrue(isBlockedHost("fonts.gstatic.com"))
        assertTrue(isBlockedHost("stats.g.doubleclick.net"))
        assertTrue(isBlockedHost("FONTS.GOOGLEAPIS.COM."))
        assertFalse(isBlockedHost("example.com"))
        assertFalse(isBlockedHost("notdoubleclick.net"))
    }
}

class FetchArticleTest {

    private val host = InetAddress.getByName("localhost").canonicalHostName
    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName(host).build()
    private val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    private val trustingClient = OkHttpClient.Builder()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        .build()
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory())
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client(config: FeedClientConfig = FeedClientConfig(appVersion = "1")) =
        FeedClient(config, { KXmlParser() }, Clock.fixed(NOW, ZoneOffset.UTC), trustingClient)

    @Test
    fun fetchesAndExtractsAnArticle() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(String(fixtureBytes("article-wordpress.html"))).build())
        val outcome = client().fetchArticle(server.url("/post").toString()) as ArticleOutcome.Extracted
        assertTrue(outcome.article.html.contains("steel touring frame"))
    }

    @Test
    fun nonArticlePagesAreReported() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(String(fixtureBytes("not-article.html"))).build())
        assertEquals(ArticleOutcome.NotExtractable, client().fetchArticle(server.url("/login").toString()))
    }

    @Test
    fun httpErrorsAndLimitsApply() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build())
        assertEquals(ArticleOutcome.Failed(FetchFailure.Http(404)), client().fetchArticle(server.url("/missing").toString()))
        server.enqueue(MockResponse.Builder().code(200).body(String(fixtureBytes("article-wordpress.html"))).build())
        val small = FeedClientConfig(appVersion = "1", maxBytes = 1_000)
        assertEquals(ArticleOutcome.Failed(FetchFailure.TooLarge), client(small).fetchArticle(server.url("/big").toString()))
    }

    @Test
    fun articlesAreFetchedWithoutCookies() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(String(fixtureBytes("article-wordpress.html"))).addHeader("Set-Cookie", "id=1").build())
        server.enqueue(MockResponse.Builder().code(200).body(String(fixtureBytes("article-wordpress.html"))).build())
        val client = client()
        client.fetchArticle(server.url("/a").toString())
        client.fetchArticle(server.url("/a").toString())
        server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        assertNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.headers["Cookie"])
    }
}
