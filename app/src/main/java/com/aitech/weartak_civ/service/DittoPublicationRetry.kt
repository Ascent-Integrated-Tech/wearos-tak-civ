package com.aitech.weartak_civ.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** A new ledger generation interrupts backoff; an empty ledger has no retry timer. */
internal class DittoPublicationRetry(
    scope: CoroutineScope,
    changes: StateFlow<Long>,
    private val pending: () -> Boolean,
    private val retry: () -> Unit,
    allowed: Flow<Boolean> = flowOf(true),
) : AutoCloseable {
    private val job: Job = scope.launch {
        combine(changes, allowed) { _, enabled -> enabled }.collectLatest { enabled ->
            var wait = INITIAL_MS
            while (enabled && pending()) {
                delay(wait)
                retry()
                wait = (wait * 2).coerceAtMost(MAX_MS)
            }
        }
    }

    override fun close() { job.cancel() }

    companion object {
        const val INITIAL_MS = 10_000L
        const val MAX_MS = 300_000L
    }
}
