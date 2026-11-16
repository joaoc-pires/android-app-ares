// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.feed

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.net.InetAddress
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class FeedClientTest {

    private val rss = """<?xml version="1.0"?><rss version="2.0"><channel><title>Test Feed</title>""" +
        """<link>https://site.example/</link><item><title>One</title><link>https://site.example/1</link></item></channel></rss>"""

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

    private fun client(config: FeedClientConfig = FeedClientConfig(appVersion = "9.9.9")) =
        FeedClient(config, { KXmlParser() }, Clock.fixed(NOW, ZoneOffset.UTC), trustingClient)

    private fun ok(body: String = rss) = MockResponse.Builder().code(200).body(body).build()

    private fun route(vararg routes: Pair<String, MockResponse>) {
        val map = routes.toMap()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                map[request.url.encodedPath] ?: MockResponse.Builder().code(404).build()
        }
    }

    private fun url(path: String) = server.url(path).toString()

    @Test
    fun fetchesAndParsesAFeed() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(rss).addHeader("ETag", "\"v1\"").addHeader("Last-Modified", "Mon, 16 Nov 2026 10:00:00 GMT").build())
        val outcome = client().fetch(url("/feed")) as FetchOutcome.Updated
        assertEquals("Test Feed", outcome.feed.title)
        assertEquals(1, outcome.feed.entries.size)
        assertEquals(Validators("\"v1\"", "Mon, 16 Nov 2026 10:00:00 GMT"), outcome.validators)
        assertNull(outcome.newUrl)
    }

    @Test
    fun sendsHonestHeaders() = runBlocking {
        server.enqueue(ok())
        client().fetch(url("/feed"))
        val request = server.takeRequest()
        assertEquals("Ares/9.9.9 (+https://joaopires.com/ares)", request.headers["User-Agent"])
        assertTrue(request.headers["Accept"]!!.startsWith("application/rss+xml"))
    }

    @Test
    fun conditionalGetReturnsNotModified() = runBlocking {
        server.enqueue(MockResponse.Builder().code(304).build())
        val outcome = client().fetch(url("/feed"), Validators("\"v1\"", "Mon, 16 Nov 2026 10:00:00 GMT"))
        assertEquals(FetchOutcome.NotModified(null), outcome)
        val request = server.takeRequest()
        assertEquals("\"v1\"", request.headers["If-None-Match"])
        assertEquals("Mon, 16 Nov 2026 10:00:00 GMT", request.headers["If-Modified-Since"])
    }

    @Test
    fun permanentRedirectsReportTheNewUrl() = runBlocking {
        route(
            "/a" to MockResponse.Builder().code(301).addHeader("Location", "/b").build(),
            "/b" to MockResponse.Builder().code(308).addHeader("Location", "/c").build(),
            "/c" to ok(),
        )
        val outcome = client().fetch(url("/a")) as FetchOutcome.Updated
        assertEquals(url("/c"), outcome.newUrl)
        assertEquals(url("/c"), outcome.feed.url)
    }

    @Test
    fun temporaryRedirectsKeepTheOriginalUrl() = runBlocking {
        route(
            "/a" to MockResponse.Builder().code(301).addHeader("Location", "/b").build(),
            "/b" to MockResponse.Builder().code(302).addHeader("Location", "/c").build(),
            "/c" to ok(),
        )
        val outcome = client().fetch(url("/a")) as FetchOutcome.Updated
        assertNull(outcome.newUrl)
        assertEquals(url("/a"), outcome.feed.url)
    }

    @Test
    fun tooManyRedirectsFail() = runBlocking {
        route("/loop" to MockResponse.Builder().code(302).addHeader("Location", "/loop").build())
        assertEquals(FetchOutcome.Failed(FetchFailure.Http(302)), client().fetch(url("/loop")))
    }

    @Test
    fun redirectToHttpIsRefused() = runBlocking {
        val insecure = url("/x").replaceFirst("https://", "http://")
        server.enqueue(MockResponse.Builder().code(301).addHeader("Location", insecure).build())
        assertEquals(FetchOutcome.Failed(FetchFailure.InsecureOnly), client().fetch(url("/feed")))
    }

    @Test
    fun httpUrlsAreUpgradedToHttps() = runBlocking {
        server.enqueue(ok())
        val outcome = client().fetch(url("/feed").replaceFirst("https://", "http://")) as FetchOutcome.Updated
        assertEquals(url("/feed"), outcome.newUrl)
        assertEquals("https", server.takeRequest().url.scheme)
    }

    @Test
    fun httpOnlyServersAreReportedAsInsecure() = runBlocking {
        val plain = MockWebServer()
        plain.start()
        try {
            plain.enqueue(ok())
            val outcome = client().fetch(plain.url("/feed").toString())
            assertEquals(FetchOutcome.Failed(FetchFailure.InsecureOnly), outcome)
        } finally {
            plain.close()
        }
    }

    @Test
    fun goneAndHttpErrors() = runBlocking {
        route(
            "/gone" to MockResponse.Builder().code(410).build(),
            "/error" to MockResponse.Builder().code(500).build(),
        )
        assertEquals(FetchOutcome.Gone, client().fetch(url("/gone")))
        assertEquals(FetchOutcome.Failed(FetchFailure.Http(404)), client().fetch(url("/missing")))
        assertEquals(FetchOutcome.Failed(FetchFailure.Http(500)), client().fetch(url("/error")))
    }

    @Test
    fun slowServersTimeOut() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(rss).headersDelay(3, TimeUnit.SECONDS).build())
        val config = FeedClientConfig(appVersion = "1", readTimeout = Duration.ofMillis(300))
        assertEquals(FetchOutcome.Failed(FetchFailure.Timeout), client(config).fetch(url("/feed")))
    }

    @Test
    fun responsesOverTheLimitAreRejected() = runBlocking {
        val big = rss.replace("<title>One</title>", "<title>" + "x".repeat(5_000) + "</title>")
        val config = FeedClientConfig(appVersion = "1", maxBytes = 1_000)
        server.enqueue(ok(big))
        assertEquals(FetchOutcome.Failed(FetchFailure.TooLarge), client(config).fetch(url("/feed")))
        server.enqueue(MockResponse.Builder().code(200).chunkedBody(big, 256).build())
        assertEquals(FetchOutcome.Failed(FetchFailure.TooLarge), client(config).fetch(url("/feed")))
    }

    @Test
    fun gzipResponsesAreDecoded() = runBlocking {
        val compressed = Buffer().also { buffer -> GzipSink(buffer).buffer().use { it.writeUtf8(rss) } }
        server.enqueue(MockResponse.Builder().code(200).body(compressed).addHeader("Content-Encoding", "gzip").build())
        val outcome = client().fetch(url("/feed")) as FetchOutcome.Updated
        assertEquals("Test Feed", outcome.feed.title)
    }

    @Test
    fun cookiesAreNeverStoredOrSent() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(rss).addHeader("Set-Cookie", "session=abc; Path=/").build())
        server.enqueue(ok())
        val client = client()
        client.fetch(url("/feed"))
        client.fetch(url("/feed"))
        server.takeRequest()
        assertNull(server.takeRequest().headers["Cookie"])
    }

    @Test
    fun cancellationAbortsTheRequest() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(rss).headersDelay(10, TimeUnit.SECONDS).build())
        val job = async(start = CoroutineStart.DEFAULT) { client().fetch(url("/feed")) }
        delay(200)
        job.cancel()
        withTimeout(2_000) { runCatching { job.await() } }
        assertTrue(job.isCancelled)
    }

    @Test
    fun nonFeedPagesFail() = runBlocking {
        server.enqueue(ok(String(fixtureBytes("html-page.html"))))
        assertEquals(FetchOutcome.Failed(FetchFailure.NotAFeed), client().fetch(url("/page")))
    }

    @Test
    fun discoveryReturnsTheFeedWhenTheUrlIsAlreadyAFeed() = runBlocking {
        server.enqueue(ok())
        val outcome = client().discover(url("/feed")) as DiscoveryOutcome.Found
        assertEquals("Test Feed", outcome.feed.title)
    }

    @Test
    fun discoveryFollowsASingleAlternateLink() = runBlocking {
        route("/" to ok(String(fixtureBytes("discovery-one.html"))), "/feed.xml" to ok())
        val outcome = client().discover(url("/")) as DiscoveryOutcome.Found
        assertEquals(url("/feed.xml"), outcome.feed.url)
    }

    @Test
    fun discoveryListsSeveralCandidates() = runBlocking {
        route("/" to ok(String(fixtureBytes("discovery-many.html"))))
        val outcome = client().discover(url("/")) as DiscoveryOutcome.Candidates
        assertEquals(
            listOf(DiscoveredFeed(url("/posts.xml"), "Posts"), DiscoveredFeed("https://disc.example/comments.atom", "Comments")),
            outcome.feeds,
        )
    }

    @Test
    fun discoveryProbesCommonPaths() = runBlocking {
        route("/" to ok(String(fixtureBytes("html-page.html"))), "/rss" to ok())
        val outcome = client().discover(url("/")) as DiscoveryOutcome.Found
        assertEquals(url("/rss"), outcome.feed.url)
    }

    @Test
    fun discoveryReportsNothingFound() = runBlocking {
        route("/" to ok(String(fixtureBytes("html-page.html"))))
        assertEquals(DiscoveryOutcome.NothingFound, client().discover(url("/")))
    }
}
