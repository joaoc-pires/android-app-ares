// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data

import androidx.room.withTransaction
import com.joaopires.ares.core.data.db.AresDatabase
import com.joaopires.ares.core.data.db.FilterEntity
import com.joaopires.ares.core.data.model.Filter
import kotlinx.coroutines.flow.Flow

private const val SCAN_BATCH = 500

public class FilterRepository(private val db: AresDatabase) {

    public val filters: Flow<List<Filter>> = db.filterDao().observeAll()

    public suspend fun add(expression: String = ""): Long = db.filterDao().insert(FilterEntity(expression = expression))

    public suspend fun update(filterId: Long, expression: String) {
        db.filterDao().update(filterId, expression)
    }

    public suspend fun delete(filterId: Long) {
        db.filterDao().delete(filterId)
    }

    public suspend fun countMatching(): Int = matchingIds().size

    public suspend fun applyToExisting(): Int = db.withTransaction {
        val ids = matchingIds()
        ids.chunked(SCAN_BATCH).forEach { db.entryDao().deleteByIds(it) }
        ids.size
    }

    private suspend fun matchingIds(): List<String> {
        val expressions = cleanExpressions(db.filterDao().expressions())
        if (expressions.isEmpty()) return emptyList()
        val matches = mutableListOf<String>()
        var after = 0L
        while (true) {
            val batch = db.entryDao().scanForFilters(after, SCAN_BATCH)
            if (batch.isEmpty()) break
            batch.filter { matchesAnyFilter(it.title, it.contentText, expressions) }.mapTo(matches) { it.id }
            after = batch.last().rowId
        }
        return matches
    }
}
