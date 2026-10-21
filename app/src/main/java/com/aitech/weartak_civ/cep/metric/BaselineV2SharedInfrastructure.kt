package com.aitech.weartak_civ.cep.metric

/** Capabilities are explicit so an unsupported output is never inferred from null. */
enum class BaselineV2Capability {
    CENTRE,
    DISTRIBUTION,
    EXPECTED_BOUNDS,
    CANDIDATE_BOUNDARIES,
    CONTEXT,
    RESPONSE,
    TRENDS,
    PERSISTENCE,
    QUALITY_AND_FRESHNESS,
    CEP_AUTHORITY
}

data class BaselineV2CapabilityDeclaration(
    val metricId: String,
    val strategy: BaselineV2Strategy,
    val supported: Set<BaselineV2Capability>,
    val unsupportedReason: Map<BaselineV2Capability, String> = emptyMap()
) {
    init {
        require(unsupportedReason.keys.all { it !in supported }) {
            "A capability cannot be both supported and unsupported"
        }
    }

    fun supports(capability: BaselineV2Capability): Boolean = capability in supported
}

object BaselineV2LifecycleTransitions {
    private val allowed: Map<BaselineV2LifecycleState, Set<BaselineV2LifecycleState>> = mapOf(
        BaselineV2LifecycleState.NEW to setOf(BaselineV2LifecycleState.COLLECTING, BaselineV2LifecycleState.RESET),
        BaselineV2LifecycleState.COLLECTING to setOf(
            BaselineV2LifecycleState.CENTRE_READY,
            BaselineV2LifecycleState.INVALIDATED,
            BaselineV2LifecycleState.RESET
        ),
        BaselineV2LifecycleState.CENTRE_READY to setOf(
            BaselineV2LifecycleState.DISTRIBUTION_READY,
            BaselineV2LifecycleState.INVALIDATED,
            BaselineV2LifecycleState.RESET
        ),
        BaselineV2LifecycleState.DISTRIBUTION_READY to setOf(
            BaselineV2LifecycleState.MODEL_ESTABLISHED,
            BaselineV2LifecycleState.DISPLAY_READY,
            BaselineV2LifecycleState.INVALIDATED,
            BaselineV2LifecycleState.RESET
        ),
        BaselineV2LifecycleState.MODEL_ESTABLISHED to setOf(
            BaselineV2LifecycleState.DISPLAY_READY,
            BaselineV2LifecycleState.FROZEN,
            BaselineV2LifecycleState.STALE,
            BaselineV2LifecycleState.INVALIDATED,
            BaselineV2LifecycleState.RESET
        ),
        BaselineV2LifecycleState.DISPLAY_READY to setOf(
            BaselineV2LifecycleState.FROZEN,
            BaselineV2LifecycleState.STALE,
            BaselineV2LifecycleState.INVALIDATED,
            BaselineV2LifecycleState.RESET
        ),
        BaselineV2LifecycleState.FROZEN to setOf(
            BaselineV2LifecycleState.DISPLAY_READY,
            BaselineV2LifecycleState.STALE,
            BaselineV2LifecycleState.INVALIDATED,
            BaselineV2LifecycleState.RESET
        ),
        BaselineV2LifecycleState.STALE to setOf(
            BaselineV2LifecycleState.DISPLAY_READY,
            BaselineV2LifecycleState.INVALIDATED,
            BaselineV2LifecycleState.RESET
        ),
        BaselineV2LifecycleState.INVALIDATED to setOf(
            BaselineV2LifecycleState.COLLECTING,
            BaselineV2LifecycleState.RESET
        ),
        BaselineV2LifecycleState.RESET to setOf(BaselineV2LifecycleState.NEW)
    )

    fun requireTransition(from: BaselineV2LifecycleState, to: BaselineV2LifecycleState) {
        require(from == to || to in allowed.getValue(from)) {
            "Invalid Baseline V2 lifecycle transition: $from -> $to"
        }
    }
}

sealed interface BaselineV2QualificationResult {
    data class Accepted(val observation: QualifiedMetricObservation) : BaselineV2QualificationResult
    data class Rejected(val reason: String) : BaselineV2QualificationResult
}

/** Common typed qualification seam. Metric hooks decide signal-specific eligibility. */
class BaselineV2QualifiedObservationPipeline<T>(
    private val qualify: (T) -> QualifiedMetricObservation?
) {
    fun process(raw: T): BaselineV2QualificationResult =
        qualify(raw)?.let(BaselineV2QualificationResult::Accepted)
            ?: BaselineV2QualificationResult.Rejected("qualification_rejected")
}

data class BaselineV2DependencySnapshot(
    val versions: Map<String, String>
)

/** Deterministic dependency invalidation order for future multi-metric models. */
class BaselineV2DependencyGraph(dependencies: Map<String, Set<String>>) {
    private val dependentsByDependency = dependencies.entries
        .flatMap { (metric, upstream) -> upstream.map { it to metric } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, values) -> values.toSortedSet() }

    fun invalidatedBy(changedDependencies: Set<String>): List<String> {
        val invalidated = sortedSetOf<String>()
        val pending = ArrayDeque(changedDependencies.sorted())
        while (pending.isNotEmpty()) {
            dependentsByDependency[pending.removeFirst()].orEmpty().forEach { dependent ->
                if (invalidated.add(dependent)) pending.addLast(dependent)
            }
        }
        return invalidated.toList()
    }
}

data class BaselineV2ModelUpdateTransaction(
    val generation: Long,
    val snapshot: BaselineV2MetricSnapshot
)

interface BaselineV2SnapshotCommitter {
    fun persist(transaction: BaselineV2ModelUpdateTransaction)
    fun publish(transaction: BaselineV2ModelUpdateTransaction)
}

/** Enforces persist-before-publish for authority/lifecycle changes. */
class BaselineV2ModelUpdateCoordinator(
    private val committer: BaselineV2SnapshotCommitter
) {
    fun commit(transaction: BaselineV2ModelUpdateTransaction) {
        committer.persist(transaction)
        committer.publish(transaction)
    }
}
