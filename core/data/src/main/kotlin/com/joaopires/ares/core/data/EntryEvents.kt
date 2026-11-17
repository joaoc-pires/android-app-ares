// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

public sealed interface EntryEvent {
    public data class MarkedRead(val entryIds: List<String>) : EntryEvent
    public data object Removed : EntryEvent
}

public class EntryEvents {
    private val flow = MutableSharedFlow<EntryEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    public val events: SharedFlow<EntryEvent> = flow.asSharedFlow()

    internal fun emit(event: EntryEvent) {
        flow.tryEmit(event)
    }
}
