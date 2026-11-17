// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.time.Clock
import java.time.temporal.ChronoUnit

public enum class LogLevel(public val label: String) { Info("INFO"), Warning("WARNING"), Error("ERROR") }

public interface EventLog {
    public suspend fun log(level: LogLevel, message: String)

    public object None : EventLog {
        override suspend fun log(level: LogLevel, message: String): Unit = Unit
    }
}

public class FileEventLog(
    private val directory: File,
    private val clock: Clock,
    private val maxBytesPerFile: Long = 128L * 1024,
) : EventLog {
    private val mutex = Mutex()
    private val current = File(directory, "ares.log")
    private val previous = File(directory, "ares.1.log")
    private val versionState = MutableStateFlow(0)

    public val version: StateFlow<Int> = versionState.asStateFlow()

    override suspend fun log(level: LogLevel, message: String) {
        val line = "${clock.instant().truncatedTo(ChronoUnit.SECONDS)} ${level.label} ${message.replace('\n', ' ')}\n"
        withContext(Dispatchers.IO) {
            mutex.withLock {
                directory.mkdirs()
                if (current.length() + line.length > maxBytesPerFile) {
                    previous.delete()
                    current.renameTo(previous)
                }
                current.appendText(line)
            }
        }
        versionState.update { it + 1 }
    }

    public suspend fun read(): String = withContext(Dispatchers.IO) {
        mutex.withLock { listOf(previous, current).filter { it.exists() }.joinToString("") { it.readText() } }
    }

    public suspend fun clear() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                previous.delete()
                current.delete()
            }
        }
        versionState.update { it + 1 }
    }
}

internal fun hostOrNull(url: String): String? = try {
    URI(url.trim()).host?.lowercase()
} catch (_: Exception) {
    null
}

internal fun hostKey(url: String): String = hostOrNull(url) ?: url

internal fun domainForLog(url: String): String = hostOrNull(url) ?: "unknown host"
