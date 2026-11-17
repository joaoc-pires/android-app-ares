// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data

import com.joaopires.ares.core.data.db.AresDatabase
import com.joaopires.ares.core.feed.ArticleOutcome
import com.joaopires.ares.core.feed.FetchFailure
import kotlinx.coroutines.CancellationException

public sealed interface FullArticleResult {
    public data object Loaded : FullArticleResult
    public data object NotExtractable : FullArticleResult
    public data object NoLink : FullArticleResult
    public data class Failed(val failure: FetchFailure) : FullArticleResult
}

public class ArticleRepository(
    private val db: AresDatabase,
    private val source: FeedSource,
    private val log: EventLog = EventLog.None,
) {

    public suspend fun loadFullArticle(entryId: String): FullArticleResult {
        val url = db.entryDao().urlOf(entryId) ?: return FullArticleResult.NoLink
        val outcome = try {
            source.fetchArticle(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log(LogLevel.Error, "Full article from ${domainForLog(url)} crashed: ${e.javaClass.simpleName}")
            ArticleOutcome.Failed(FetchFailure.Network)
        }
        return when (outcome) {
            is ArticleOutcome.Extracted -> {
                db.entryDao().setFullContent(entryId, outcome.article.html)
                FullArticleResult.Loaded
            }
            ArticleOutcome.NotExtractable -> {
                log.log(LogLevel.Info, "No article could be extracted from ${domainForLog(url)}")
                FullArticleResult.NotExtractable
            }
            is ArticleOutcome.Failed -> {
                log.log(LogLevel.Warning, "Full article from ${domainForLog(url)} failed: ${outcome.failure.code()}")
                FullArticleResult.Failed(outcome.failure)
            }
        }
    }

    public suspend fun entryIdForUrl(url: String): String? {
        val trimmed = url.trim().substringBefore('#')
        if (trimmed.isEmpty()) return null
        val variants = listOf(trimmed, trimmed.trimEnd('/'), "$trimmed/").flatMap { variant ->
            when {
                variant.startsWith("https://") -> listOf(variant, "http://" + variant.removePrefix("https://"))
                variant.startsWith("http://") -> listOf(variant, "https://" + variant.removePrefix("http://"))
                else -> listOf(variant)
            }
        }.distinct()
        return db.entryDao().idForUrl(variants)
    }
}
