package com.aitech.weartak_civ.data.takserver

import android.util.Log
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Serializes blocking TAK transport teardown (TLS close_notify, OkHttp cancel/evict) off the
 * caller's thread. Callers invalidate state synchronously, then enqueue the close here; connect
 * paths call [awaitPendingCloses] from a background thread so a close finishes before the
 * reconnect it gates.
 */
object TakConnectionCloser {
    private const val TAG = "TakConnectionCloser"
    const val THREAD_NAME = "WearTAK-TakCloser"
    private const val AWAIT_TIMEOUT_MS = 5_000L

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, THREAD_NAME).apply { isDaemon = true }
    }

    fun execute(block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (error: Throwable) {
                Log.w(TAG, "TAK transport close failed", error)
            }
        }
    }

    fun close(resources: Collection<Closeable>) {
        if (resources.isEmpty()) return
        val pending = resources.distinct()
        execute { pending.forEach { runCatching { it.close() } } }
    }

    /** Blocks until every close queued before this call completes. Never call on main. */
    fun awaitPendingCloses(timeoutMs: Long = AWAIT_TIMEOUT_MS): Boolean =
        runCatching { executor.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS); true }.getOrDefault(false)
}
