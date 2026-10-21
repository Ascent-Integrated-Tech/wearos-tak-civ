package com.aitech.weartak_civ.cep.metric

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory publication point for Baseline V2 snapshots.
 *
 * This store is deliberately separate from FeatureStore. It is an output surface
 * for the Trends-only implementation and must not publish legacy CEP keys or
 * provide model inputs back to the V2 pipeline.
 */
class BaselineV2SnapshotStore {
    private var currentBatch: BaselineV2SnapshotBatch? = null
    private val _snapshots = MutableStateFlow<BaselineV2SnapshotBatch?>(null)
    val snapshots: StateFlow<BaselineV2SnapshotBatch?> = _snapshots.asStateFlow()

    @Synchronized
    fun publish(batch: BaselineV2SnapshotBatch) {
        require(batch.snapshots.values.all { it.generation == batch.generation }) {
            "All snapshots in a batch must use the batch generation"
        }
        require(batch.generation >= (currentBatch?.generation ?: 0L)) {
            "Snapshot generations must not move backwards"
        }
        val merged = (currentBatch?.snapshots.orEmpty() + batch.snapshots)
            .mapValues { (_, snapshot) -> snapshot.copy(generation = batch.generation) }
        currentBatch = batch.copy(snapshots = merged)
        _snapshots.value = currentBatch
    }

    @Synchronized
    fun current(): BaselineV2SnapshotBatch? = currentBatch

    @Synchronized
    fun snapshot(metricId: String): BaselineV2MetricSnapshot? =
        currentBatch?.snapshots?.get(metricId)

    @Synchronized
    fun clear() {
        currentBatch = null
        _snapshots.value = null
    }
}

/** One immutable publication unit for all V2 snapshots produced together. */
data class BaselineV2SnapshotBatch(
    val generation: Long,
    val publishedAtMs: Long,
    val snapshots: Map<String, BaselineV2MetricSnapshot>
)
