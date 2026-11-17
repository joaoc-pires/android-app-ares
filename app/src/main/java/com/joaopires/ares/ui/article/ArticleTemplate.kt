// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.article

data class ArticleColors(
    val background: String,
    val text: String,
    val secondaryText: String,
    val accent: String,
    val accentContainer: String,
    val onAccentContainer: String,
    val outline: String,
)

data class ArticleAttachment(val url: String, val label: String)

data class ArticlePage(
    val title: String,
    val feedTitle: String?,
    val author: String?,
    val date: String?,
    val bodyHtml: String,
    val watchUrl: String? = null,
    val watchThumbnailUrl: String? = null,
    val attachments: List<ArticleAttachment> = emptyList(),
)

data class ArticleLabels(val watch: String, val attachments: String)

const val ARTICLE_CSP = "default-src 'none'; img-src https: data:; media-src https:; style-src 'unsafe-inline'"

fun escapeHtml(value: String): String = buildString(value.length) {
    value.forEach {
        when (it) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(it)
        }
    }
}

fun articleHtml(page: ArticlePage, colors: ArticleColors, labels: ArticleLabels): String {
    val meta = listOfNotNull(page.feedTitle, page.author, page.date)
        .filter { it.isNotBlank() }
        .joinToString(" \u00B7 ") { escapeHtml(it) }
    val watch = page.watchUrl?.let { url ->
        val thumbnail = page.watchThumbnailUrl?.let { "<img src=\"${escapeHtml(it)}\" alt=\"\">" }.orEmpty()
        "<a class=\"ares-watch\" href=\"${escapeHtml(url)}\">$thumbnail<span>${escapeHtml(labels.watch)}</span></a>"
    }.orEmpty()
    val attachments = if (page.attachments.isEmpty()) {
        ""
    } else {
        "<section class=\"ares-attachments\"><h2>${escapeHtml(labels.attachments)}</h2><ul>" +
            page.attachments.joinToString("") { "<li><a class=\"ares-attachment\" href=\"${escapeHtml(it.url)}\">${escapeHtml(it.label)}</a></li>" } +
            "</ul></section>"
    }
    return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="$ARTICLE_CSP">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
html { -webkit-text-size-adjust: 100%; }
body { margin: 0; padding: 16px 16px 48px; background: ${colors.background}; color: ${colors.text};
  font-family: sans-serif; font-size: 17px; line-height: 1.6; overflow-wrap: break-word; }
h1.ares-title { font-size: 1.6em; line-height: 1.25; margin: 0 0 8px; }
p.ares-meta { color: ${colors.secondaryText}; font-size: 0.85em; margin: 0 0 24px; }
a { color: ${colors.accent}; }
img, video { max-width: 100%; height: auto; border-radius: 8px; }
figure { margin: 16px 0; }
figcaption { color: ${colors.secondaryText}; font-size: 0.85em; border-left: 2px solid ${colors.accent}; padding-left: 8px; }
blockquote { margin: 16px 0; padding-left: 12px; border-left: 3px solid ${colors.accent}; color: ${colors.secondaryText}; }
pre { overflow-x: auto; padding: 12px; border-radius: 8px; border: 1px solid ${colors.outline}; }
code { font-family: monospace; font-size: 0.9em; }
table { display: block; overflow-x: auto; border-collapse: collapse; }
td, th { border: 1px solid ${colors.outline}; padding: 4px 8px; }
hr { border: 0; border-top: 1px solid ${colors.outline}; }
a.ares-embed, a.ares-attachment { display: block; padding: 12px 16px; margin: 12px 0; border-radius: 12px;
  background: ${colors.accentContainer}; color: ${colors.onAccentContainer}; text-decoration: none; }
a.ares-watch { display: block; margin: 0 0 24px; border-radius: 12px; overflow: hidden; text-decoration: none;
  background: ${colors.accentContainer}; color: ${colors.onAccentContainer}; }
a.ares-watch img { display: block; width: 100%; border-radius: 0; }
a.ares-watch span { display: block; padding: 12px 16px; font-weight: bold; }
section.ares-attachments h2 { font-size: 1em; margin-top: 32px; }
section.ares-attachments ul { list-style: none; padding: 0; }
</style>
</head>
<body dir="auto">
<h1 class="ares-title" dir="auto">${escapeHtml(page.title)}</h1>
<p class="ares-meta" dir="auto">$meta</p>
$watch
<div class="ares-content" dir="auto">
${page.bodyHtml}
</div>
$attachments
</body>
</html>
    """.trimIndent()
}
