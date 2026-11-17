// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.feed

import com.joaopires.ares.core.feed.parse.decodeText
import com.joaopires.ares.core.xml.LimitExceededException
import com.joaopires.ares.core.xml.LimitedInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Clock
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

public class FeedClient internal constructor(
    private val config: FeedClientConfig,
    private val newParser: () -> XmlPullParser,
    private val clock: Clock,
    baseClient: OkHttpClient,
) {

    public constructor(
        config: FeedClientConfig,
        newParser: () -> XmlPullParser,
        clock: Clock = Clock.systemUTC(),
    ) : this(config, newParser, clock, OkHttpClient())

    private val http: OkHttpClient = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cache(null)
        .connectTimeout(config.connectTimeout)
        .readTimeout(config.readTimeout)
        .callTimeout(config.callTimeout)
        .build()

    public suspend fun fetch(url: String, validators: Validators? = null, feedKey: String = url): FetchOutcome =
        when (val download = download(url, validators)) {
            is Download.Failed -> FetchOutcome.Failed(download.failure)
            is Download.NotModified -> FetchOutcome.NotModified(download.newUrl)
            Download.Gone -> FetchOutcome.Gone
            is Download.Body -> when (val parsed = parse(download, feedKey)) {
                is Parsed.Ok -> FetchOutcome.Updated(parsed.feed, download.validators, download.newUrl)
                is Parsed.Error -> FetchOutcome.Failed(parsed.failure)
            }
        }

    public suspend fun discover(url: String): DiscoveryOutcome {
        val download = download(url, null)
        if (download !is Download.Body) {
            return when (download) {
                is Download.Failed -> DiscoveryOutcome.Failed(download.failure)
                Download.Gone -> DiscoveryOutcome.Failed(FetchFailure.Http(410))
                else -> DiscoveryOutcome.NothingFound
            }
        }
        when (val parsed = parse(download, download.feedUrl)) {
            is Parsed.Ok -> return DiscoveryOutcome.Found(parsed.feed, download.validators)
            is Parsed.Error -> if (parsed.failure != FetchFailure.NotAFeed) return DiscoveryOutcome.Failed(parsed.failure)
        }

        val candidates = discoverLinks(download)
        if (candidates.size > 1) return DiscoveryOutcome.Candidates(candidates)
        candidates.singleOrNull()?.let { candidate ->
            return when (val outcome = fetch(candidate.url)) {
                is FetchOutcome.Updated -> DiscoveryOutcome.Found(outcome.feed, outcome.validators)
                else -> DiscoveryOutcome.Candidates(candidates)
            }
        }

        val origin = download.finalUrl.toHttpUrlOrNull() ?: return DiscoveryOutcome.NothingFound
        for (path in COMMON_FEED_PATHS) {
            val probe = origin.newBuilder().encodedPath(path).query(null).fragment(null).build().toString()
            val outcome = fetch(probe)
            if (outcome is FetchOutcome.Updated) return DiscoveryOutcome.Found(outcome.feed, outcome.validators)
        }
        return DiscoveryOutcome.NothingFound
    }

    private fun discoverLinks(download: Download.Body): List<DiscoveredFeed> {
        val document = Jsoup.parse(decodeText(download.bytes, download.charset), download.finalUrl)
        return document.select("link[rel~=(?i)alternate][href]")
            .filter { link -> link.attr("type").lowercase().let { type -> FEED_TYPES.any { type.startsWith(it) } } }
            .mapNotNull { link ->
                link.absUrl("href").takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    ?.let { DiscoveredFeed(it, link.attr("title").trim().ifEmpty { null }) }
            }
            .distinctBy { it.url }
    }

    private suspend fun parse(download: Download.Body, feedKey: String): Parsed = withContext(Dispatchers.Default) {
        try {
            Parsed.Ok(
                parseFeedDocument(
                    bytes = download.bytes,
                    url = download.feedUrl,
                    newParser = newParser,
                    now = clock.instant(),
                    charset = download.charset,
                    documentUrl = download.finalUrl,
                    feedKey = feedKey,
                ),
            )
        } catch (e: FeedParseError) {
            Parsed.Error(e.failure)
        }
    }

    private suspend fun download(url: String, validators: Validators?): Download {
        val start = url.trim().toHttpUrlOrNull() ?: return Download.Failed(FetchFailure.Network)
        val upgraded = !start.isHttps
        var current = if (upgraded) start.newBuilder().scheme("https").build() else start
        var allPermanent = true
        var hops = 0
        while (true) {
            val request = Request.Builder()
                .url(current)
                .header("User-Agent", config.userAgent)
                .header("Accept", ACCEPT)
                .apply {
                    validators?.etag?.let { header("If-None-Match", it) }
                    validators?.lastModified?.let { header("If-Modified-Since", it) }
                }
                .build()
            val response = try {
                http.newCall(request).await()
            } catch (e: InterruptedIOException) {
                return Download.Failed(FetchFailure.Timeout)
            } catch (e: SSLException) {
                return Download.Failed(if (upgraded && hops == 0) FetchFailure.InsecureOnly else FetchFailure.Network)
            } catch (e: IOException) {
                return Download.Failed(if (upgraded && hops == 0) FetchFailure.InsecureOnly else FetchFailure.Network)
            }
            response.use {
                val code = response.code
                if (code in REDIRECT_CODES) {
                    val location = response.header("Location")?.let { current.resolve(it) }
                        ?: return Download.Failed(FetchFailure.Http(code))
                    if (!location.isHttps) return Download.Failed(FetchFailure.InsecureOnly)
                    if (++hops > config.maxRedirects) return Download.Failed(FetchFailure.Http(code))
                    if (code != 301 && code != 308) allPermanent = false
                    current = location
                    return@use
                }
                val newUrl = when {
                    hops > 0 && allPermanent -> current.toString()
                    upgraded -> current.toString()
                    else -> null
                }
                return when {
                    code == 304 -> Download.NotModified(newUrl)
                    code == 410 -> Download.Gone
                    code !in 200..299 -> Download.Failed(FetchFailure.Http(code))
                    else -> readBody(response, current, feedUrl = newUrl ?: start.toString(), newUrl = newUrl)
                }
            }
        }
    }

    private suspend fun readBody(response: Response, finalUrl: HttpUrl, feedUrl: String, newUrl: String?): Download =
        withContext(Dispatchers.IO) {
        val body = response.body
        if (body.contentLength() > config.maxBytes) return@withContext Download.Failed(FetchFailure.TooLarge)
        try {
            val bytes = LimitedInputStream(body.byteStream(), config.maxBytes).use { it.readBytes() }
            Download.Body(
                bytes = bytes,
                charset = body.contentType()?.charset()?.name(),
                feedUrl = feedUrl,
                finalUrl = finalUrl.toString(),
                validators = Validators(response.header("ETag"), response.header("Last-Modified")),
                newUrl = newUrl,
            )
        } catch (_: LimitExceededException) {
            Download.Failed(FetchFailure.TooLarge)
        } catch (_: InterruptedIOException) {
            Download.Failed(FetchFailure.Timeout)
        } catch (_: IOException) {
            Download.Failed(FetchFailure.Network)
        }
    }

    private sealed interface Download {
        data class Body(
            val bytes: ByteArray,
            val charset: String?,
            val feedUrl: String,
            val finalUrl: String,
            val validators: Validators,
            val newUrl: String?,
        ) : Download

        data class NotModified(val newUrl: String?) : Download
        data object Gone : Download
        data class Failed(val failure: FetchFailure) : Download
    }

    private sealed interface Parsed {
        data class Ok(val feed: Feed) : Parsed
        data class Error(val failure: FetchFailure) : Parsed
    }

    private companion object {
        const val ACCEPT = "application/rss+xml, application/atom+xml, application/feed+json, " +
            "application/xml;q=0.9, text/xml;q=0.9, */*;q=0.8"
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val FEED_TYPES = listOf("application/rss+xml", "application/atom+xml", "application/feed+json", "application/json", "application/rdf+xml")
        val COMMON_FEED_PATHS = listOf("/feed", "/rss", "/atom.xml", "/index.xml", "/feed.xml", "/rss.xml")
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation { cancel() }
}
