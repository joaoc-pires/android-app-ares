// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.ui.opml

import android.content.ContentResolver
import android.net.Uri
import android.util.Xml
import com.joaopires.ares.core.data.FeedImporter
import com.joaopires.ares.core.data.FeedRepository
import com.joaopires.ares.core.data.ImportCandidate
import com.joaopires.ares.core.data.ImportProgress
import com.joaopires.ares.core.data.ImportSummary
import com.joaopires.ares.core.opml.OpmlDocument
import com.joaopires.ares.core.opml.OpmlFeed
import com.joaopires.ares.core.opml.OpmlReader
import com.joaopires.ares.core.opml.OpmlWriter
import com.joaopires.ares.core.opml.of
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Clock

class OpmlService(private val resolver: ContentResolver, private val feeds: FeedRepository, private val clock: Clock) {

    suspend fun read(uri: Uri): OpmlDocument = withContext(Dispatchers.IO) {
        val input = resolver.openInputStream(uri) ?: throw IOException("cannot open $uri")
        input.use { OpmlReader({ Xml.newPullParser() }).read(it) }
    }

    suspend fun export(uri: Uri): Int = withContext(Dispatchers.IO) {
        val exported = feeds.exportList()
        val document = OpmlDocument.of(
            title = "Ares",
            feeds = exported.map { OpmlFeed(it.url, it.title, it.siteUrl, it.section) },
            now = clock.instant(),
        )
        val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("cannot write $uri")
        output.use { OpmlWriter({ Xml.newSerializer() }).write(document, it) }
        exported.size
    }
}

class ImportController(private val importer: FeedImporter, private val scope: CoroutineScope) {
    val progress: StateFlow<ImportProgress> = importer.progress

    private val summaryState = MutableStateFlow<ImportSummary?>(null)
    val summary: StateFlow<ImportSummary?> = summaryState.asStateFlow()

    fun start(candidates: List<ImportCandidate>) {
        scope.launch { summaryState.value = importer.import(candidates) }
    }

    fun clearSummary() {
        summaryState.value = null
    }
}
