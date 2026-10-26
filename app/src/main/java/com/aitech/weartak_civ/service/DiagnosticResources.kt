package com.aitech.weartak_civ.service

import android.os.SystemClock

/** Fixed IDs describe ownership, never sensor samples or platform identifiers. */
internal enum class DiagnosticResource(val code: Long, val resourceClass: Long) {
    LOCATION_CPU(1, 1), DEBUG_CPU(2, 1), DEBUG_SCREEN(3, 2), DEBUG_WIFI(4, 3),
    MULTICAST_LOCK(5, 4), DEBUG_NETWORK(6, 5), MULTICAST_NETWORK(7, 5),
    HEADING(8, 6), HEART_RATE(9, 6), OFF_BODY(10, 6), PRESSURE(11, 6),
    ENV_PRESSURE(12, 6), ENV_LIGHT(13, 6), ENV_HUMIDITY(14, 6),
    SAMSUNG_SKIN(15, 7), SAMSUNG_PPG(16, 7), SAMSUNG_SPO2(17, 7),
    SAMSUNG_HR(18, 7), ACTIVITY(19, 8), PASSIVE_HEALTH(20, 9),
    LOCATION_NATIVE(21, 10), LOCATION_FUSED(22, 10)
}

internal enum class ResourceAction(val code: Long) {
    BEGIN(1), END(2), ALREADY_ACTIVE(3), FAILED(4), UNCERTAIN(5),
    REMOVAL_REQUESTED(6), LOCAL_DISABLED(7), EXPIRED_OBSERVED(8), OVERFLOW(9),
    SUMMARY(10), STALE_CALLBACK(11)
}

internal object DiagnosticResources {
    private val tracker = DiagnosticResourceTracker()
    fun start(emit: (DiagnosticEvent, Map<DiagnosticMetric, Long>) -> Boolean) = tracker.start(emit)
    fun stop() = tracker.stop()
    fun reset(emit: ((DiagnosticEvent, Map<DiagnosticMetric, Long>) -> Boolean)? = null) = tracker.reset(emit)
    fun flush(observeActive: Boolean = false) = tracker.flush(observeActive)
    fun begin(id: DiagnosticResource, owner: Any, timeoutMs: Long = 0, periodUs: Long = 0) =
        tracker.begin(id, owner, timeoutMs, periodUs)
    fun end(id: DiagnosticResource, owner: Any, expired: Boolean = false) = tracker.end(id, owner, expired)
    fun note(id: DiagnosticResource, owner: Any, action: ResourceAction, onlyIfActive: Boolean = false) =
        tracker.note(id, owner, action, onlyIfActive)

    fun <T> request(id: DiagnosticResource, owner: Any, removal: Boolean = false, operation: () -> T): T {
        if (removal) note(id, owner, ResourceAction.REMOVAL_REQUESTED)
        var submitted = false
        try {
            return operation().also { submitted = true }
        } finally {
            if (!submitted) note(id, owner, if (removal) ResourceAction.UNCERTAIN else ResourceAction.FAILED)
        }
    }

    fun <T> acquire(
        id: DiagnosticResource, owner: Any, timeoutMs: Long = 0, periodUs: Long = 0,
        successful: (T) -> Boolean = { true }, operation: () -> T
    ): T {
        var completed = false
        try {
            val result = operation()
            completed = true
            if (successful(result)) begin(id, owner, timeoutMs, periodUs)
            else note(id, owner, ResourceAction.FAILED)
            return result
        } finally {
            if (!completed) note(id, owner, ResourceAction.FAILED)
        }
    }

    fun <T> release(id: DiagnosticResource, owner: Any, operation: () -> T): T {
        note(id, owner, ResourceAction.REMOVAL_REQUESTED)
        var completed = false
        try {
            val result = operation()
            completed = true
            end(id, owner)
            return result
        } finally {
            if (!completed) note(id, owner, ResourceAction.UNCERTAIN)
        }
    }
}

internal class DiagnosticResourceTracker(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private data class Hold(
        val id: DiagnosticResource, val owner: Any, val token: Long, val start: Long,
        val timeout: Long, val period: Long, var uncertain: Boolean = false
    )
    private data class Totals(
        var first: Long, var last: Long, var count: Long = 0, var failures: Long = 0,
        var union: Long = 0, var unionStart: Long? = null, var suppressed: Long = 0,
        var uncertain: Boolean = false, var touched: Boolean = true
    )
    private val monitor = Any()
    private val active = ArrayList<Hold>(64)
    private val totals = linkedMapOf<DiagnosticResource, Totals>()
    private var nextToken = 0L
    @Volatile private var generation = 0L
    @Volatile private var emitter: ((DiagnosticEvent, Map<DiagnosticMetric, Long>) -> Boolean)? = null

    private fun summary(id: DiagnosticResource, now: Long): Totals =
        totals.getOrPut(id) { Totals(now, now) }

    private fun advance(id: DiagnosticResource, now: Long) {
        val total = summary(id, now)
        val previous = total.unionStart ?: return
        val holds = active.filter { it.id == id }
        val upperEnd = holds.maxOfOrNull {
            // Sensor measurement deadlines are not automatic listener expiry.
            if (it.id == DiagnosticResource.LOCATION_CPU && it.timeout > 0)
                minOf(now, it.start + it.timeout) else now
        } ?: previous
        if (holds.any { it.id == DiagnosticResource.LOCATION_CPU &&
                it.timeout > 0 && now >= it.start + it.timeout }) total.uncertain = true
        total.union += (upperEnd - previous).coerceAtLeast(0)
        total.unionStart = now
    }

    private fun metrics(id: DiagnosticResource, action: ResourceAction, now: Long, hold: Hold? = null) =
        linkedMapOf(
            DiagnosticMetric.CONTEXT_VERSION to 1L,
            DiagnosticMetric.GENERATION to generation,
            DiagnosticMetric.RESOURCE_ID to id.code,
            DiagnosticMetric.RESOURCE_CLASS to id.resourceClass,
            DiagnosticMetric.ACTION to action.code,
            DiagnosticMetric.RESULT to if (action == ResourceAction.FAILED ||
                action == ResourceAction.UNCERTAIN || action == ResourceAction.OVERFLOW ||
                action == ResourceAction.EXPIRED_OBSERVED || action == ResourceAction.STALE_CALLBACK ||
                hold?.uncertain == true) 0L else 1L,
            DiagnosticMetric.ACTIVE to active.count { it.id == id }.toLong(),
            DiagnosticMetric.LAST_MS to now
        ).apply {
            hold?.let {
                put(DiagnosticMetric.TOKEN, it.token)
                put(DiagnosticMetric.START_MS, it.start)
                put(DiagnosticMetric.TIMEOUT_MS, it.timeout)
                put(DiagnosticMetric.PERIOD_US, it.period)
                // An expired lock's actual end is unknown; this is the observation bound.
                if (action == ResourceAction.END) put(DiagnosticMetric.DURATION_MS, (now - it.start).coerceAtLeast(0))
            }
        }

    // Never call recorder code under monitor: recorder lifecycle/flush may call back into us.
    private fun emit(rows: List<Map<DiagnosticMetric, Long>>) {
        val sink = emitter ?: return
        rows.forEach { row ->
            if (emitter !== sink || row[DiagnosticMetric.GENERATION] != generation) return
            if (!sink(DiagnosticEvent.RESOURCE_LIFETIME, row)) synchronized(monitor) {
                if (row[DiagnosticMetric.GENERATION] == generation) {
                    DiagnosticResource.values().firstOrNull { it.code == row[DiagnosticMetric.RESOURCE_ID] }
                        ?.let { summary(it, clock()).suppressed++ }
                }
            }
        }
    }

    fun start(emit: (DiagnosticEvent, Map<DiagnosticMetric, Long>) -> Boolean) {
        val rows = synchronized(monitor) {
            emitter = emit
            generation++
            val now = clock()
            totals.clear()
            active.forEach {
                summary(it.id, now).apply {
                    unionStart = now
                    uncertain = uncertain || it.uncertain
                }
            }
            active.map { metrics(it.id, ResourceAction.ALREADY_ACTIVE, now, it) }
        }
        this.emit(rows)
    }

    fun stop() {
        val stoppingGeneration = generation
        flush()
        synchronized(monitor) {
            if (generation == stoppingGeneration) {
                emitter = null
                generation++
            }
        }
    }

    fun reset(emit: ((DiagnosticEvent, Map<DiagnosticMetric, Long>) -> Boolean)? = null) {
        synchronized(monitor) {
            val now = clock()
            generation++
            if (emit != null) emitter = emit
            totals.clear()
            active.forEach {
                summary(it.id, now).apply {
                    unionStart = now
                    uncertain = uncertain || it.uncertain
                    touched = false
                }
            }
        }
    }

    fun begin(id: DiagnosticResource, owner: Any, timeoutMs: Long = 0, periodUs: Long = 0) {
        val row = synchronized(monitor) {
            val now = clock()
            val existing = active.firstOrNull { it.id == id && it.owner === owner }
            if (existing != null) {
                summary(id, now).touched = true
                return@synchronized metrics(id, ResourceAction.ALREADY_ACTIVE, now, existing)
            }
            val total = summary(id, now)
            total.touched = true
            advance(id, now)
            total.last = now
            total.count++
            if (active.size == 64) {
                total.failures++
                total.uncertain = true
                metrics(id, ResourceAction.OVERFLOW, now)
            } else {
                if (active.none { it.id == id }) total.unionStart = now
                val hold = Hold(id, owner, ++nextToken, now, timeoutMs, periodUs)
                active.add(hold)
                metrics(id, ResourceAction.BEGIN, now, hold)
            }
        }
        emit(listOf(row))
    }

    fun end(id: DiagnosticResource, owner: Any, expired: Boolean = false) {
        val row = synchronized(monitor) {
            val now = clock()
            val hold = active.firstOrNull { it.id == id && it.owner === owner } ?: return
            advance(id, now)
            active.remove(hold)
            val total = summary(id, now)
            total.touched = true
            total.last = now
            if (active.none { it.id == id }) {
                total.unionStart = null
            }
            if (expired) {
                total.uncertain = true
                hold.uncertain = true
            }
            metrics(id, if (expired) ResourceAction.EXPIRED_OBSERVED else ResourceAction.END, now, hold)
        }
        emit(listOf(row))
    }

    fun note(id: DiagnosticResource, owner: Any, action: ResourceAction, onlyIfActive: Boolean = false) {
        val row = synchronized(monitor) {
            val now = clock()
            val hold = active.firstOrNull { it.id == id && it.owner === owner }
            if (onlyIfActive && hold == null) return
            val total = summary(id, now)
            total.touched = true
            total.last = now
            if (action == ResourceAction.FAILED || action == ResourceAction.UNCERTAIN) total.failures++
            if (action == ResourceAction.UNCERTAIN) total.uncertain = true
            if (action == ResourceAction.UNCERTAIN) hold?.uncertain = true
            metrics(id, action, now, hold)
        }
        emit(listOf(row))
    }

    fun flush(observeActive: Boolean = false) {
        val rows = synchronized(monitor) {
            if (emitter == null) return
            val now = clock()
            if (observeActive) active.forEach { summary(it.id, now).touched = true }
            totals.filterValues { it.touched }.map { (id, total) ->
                advance(id, now)
                total.last = now
                metrics(id, ResourceAction.SUMMARY, now).apply {
                    put(DiagnosticMetric.FIRST_MS, total.first)
                    put(DiagnosticMetric.LAST_MS, total.last)
                    put(DiagnosticMetric.COUNT, total.count)
                    put(DiagnosticMetric.FAILURES, total.failures)
                    put(DiagnosticMetric.SUPPRESSED, total.suppressed)
                    put(DiagnosticMetric.UNION_MS, total.union)
                    put(DiagnosticMetric.RESULT, if (total.uncertain) 0L else 1L)
                }
            }
        }
        emit(rows)
    }
}
