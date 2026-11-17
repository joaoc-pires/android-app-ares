// SPDX-License-Identifier: GPL-3.0-or-later

package com.joaopires.ares

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

fun backgroundDispatcher(name: String, threads: Int): CoroutineDispatcher {
    val counter = AtomicInteger()
    return Executors.newFixedThreadPool(threads) { task ->
        Thread(
            {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            },
            "$name-${counter.incrementAndGet()}",
        ).apply { isDaemon = true }
    }.asCoroutineDispatcher()
}

fun <T> Flow<T>.throttleLatest(periodMillis: Long): Flow<T> = conflate().transform { value ->
    emit(value)
    delay(periodMillis)
}
