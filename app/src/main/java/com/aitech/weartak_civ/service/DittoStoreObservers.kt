package com.aitech.weartak_civ.service

import android.util.Log
import com.aitech.weartak_civ.cot.DittoCotDocument
import com.aitech.weartak_civ.cot.DittoCotReceiver
import java.io.Closeable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import live.ditto.DittoQueryResult
import live.ditto.DittoStore

/** Materializes native results inside the SDK callback; only detached maps cross threads. */
internal class DittoStoreObservers(
    store: DittoStore,
    parentScope: CoroutineScope,
    private val receiver: DittoCotReceiver
) : Closeable {
    private val lock = Any()
    private var closed = false
    private val job = Job(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val observers = mutableListOf<Closeable>()

    init {
        try {
            DittoCotDocument.COLLECTIONS.forEach { collection ->
                observers += store.registerObserver(
                    "SELECT * FROM $collection",
                    changeHandler = { result: DittoQueryResult, next: () -> Unit ->
                        onSnapshot(collection, result, next)
                    }
                )
            }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private fun onSnapshot(collection: String, result: DittoQueryResult, next: () -> Unit) {
        synchronized(lock) {
            val snapshot = try {
                result.use {
                    if (closed) return
                    val items = it.items
                    require(items.size <= MAX_SNAPSHOT_DOCUMENTS) { "Too many Ditto documents" }
                    items.mapNotNull { item ->
                        try {
                            item.value
                        } catch (error: Exception) {
                            Log.w(TAG, "Rejected unmaterializable Ditto $collection document", error)
                            null
                        }
                    }.also { docs ->
                        require(docs.sumOf { doc ->
                            (doc["r"] as? String)?.length?.toLong() ?: 0L
                        } <= MAX_SNAPSHOT_CHARACTERS) { "Oversized Ditto snapshot detail" }
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Rejected Ditto $collection snapshot", error)
                signalNext(next)
                return
            }
            scope.launch {
                try {
                    receiver.receive(collection, snapshot)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e(TAG, "Ditto $collection ingestion failed", error)
                } finally {
                    synchronized(lock) { if (!closed) signalNext(next) }
                }
            }
        }
    }

    private fun signalNext(next: () -> Unit) {
        try {
            next()
        } catch (error: Exception) {
            Log.e(TAG, "Ditto observer could not request its next snapshot", error)
        }
    }

    override fun close() {
        val handles = synchronized(lock) {
            if (closed) return
            closed = true
            observers.toList().also { observers.clear() }
        }
        scope.cancel()
        handles.forEach {
            try {
                it.close()
            } catch (error: Exception) {
                Log.e(TAG, "Ditto observer cleanup failed", error)
            }
        }
    }

    companion object {
        const val MAX_SNAPSHOT_DOCUMENTS = 4096
        const val MAX_SNAPSHOT_CHARACTERS = 2 * 1024 * 1024
        private const val TAG = "WearTAK-DittoObservers"
    }
}
