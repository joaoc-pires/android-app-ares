// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.feed

import com.joaopires.ares.core.feed.normalize.cleanContent
import net.dankito.readability4j.extended.Readability4JExtended
import org.jsoup.Jsoup
import kotlin.coroutines.cancellation.CancellationException

private const val MIN_ARTICLE_TEXT = 200

public data class ExtractedArticle(val html: String, val title: String?, val byline: String?)

public sealed interface ArticleOutcome {
    public data class Extracted(val article: ExtractedArticle) : ArticleOutcome
    public data object NotExtractable : ArticleOutcome
    public data class Failed(val failure: FetchFailure) : ArticleOutcome
}

internal fun extractArticle(html: String, pageUrl: String): ExtractedArticle? = try {
    val document = Jsoup.parse(html, pageUrl)
    val article = Readability4JExtended(pageUrl, document).parse()
    val content = article.content
    if (content.isNullOrBlank() || article.textContent.orEmpty().trim().length < MIN_ARTICLE_TEXT) {
        null
    } else {
        val clean = cleanContent(content, pageUrl)
        if (clean.text.length < MIN_ARTICLE_TEXT) {
            null
        } else {
            ExtractedArticle(
                html = clean.html,
                title = article.title?.trim()?.ifEmpty { null },
                byline = article.byline?.trim()?.ifEmpty { null },
            )
        }
    }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
} catch (_: StackOverflowError) {
    null
}
