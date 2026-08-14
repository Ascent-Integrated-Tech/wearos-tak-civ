package com.aitech.weartak_civ.service

import android.util.Log
import com.aitech.weartak_civ.cep.engine.CepEngine
import com.aitech.weartak_civ.cep.engine.ProfileStore
import com.aitech.weartak_civ.cep.model.payloads.Spo2EdgeState
import com.aitech.weartak_civ.cep.model.payloads.Spo2Tier
import com.aitech.weartak_civ.cep.model.payloads.Spo2TierEventPayload
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.max

data class Spo2SequencerConfig(
    val ringCap: Int = 8,
    val consecutiveRequired: Int = 4,
    val emergencyConsecutiveRequired: Int = 2,
    val maxGapMs: Long = 60_000L,
    val warnDeltaFromBaseline: Double = 4.0,
    val alertPct: Double = 90.0,
    val emergencyPct: Double = 88.0,
    val heldIntervalMs: Long = 30_000L,
    val clearHysteresisPct: Double = 1.0,
    val clearSustainMs: Long = 60_000L,
    val ppgRedSustainRequiredMs: Long = 30_000L,
    val pctClampMin: Double = 70.0,
    val pctClampMax: Double = 100.0,
    val traumaWarnDeltaFromBaseline: Double = 3.0,
    val traumaAlertPct: Double = 92.0,
    val traumaEmergencyPct: Double = 89.0,
    val traumaSustainDivisor: Int = 2,
)

enum class InvalidReason { TIMEOUT, ERROR }
enum class ResetReason { OFF_WRIST, SERVICE_STOP, USER_MANUAL }

data class Spo2ValidReading(val pct: Double, val ts: Long, val family: String, val altitudeBucket: Int)
data class Spo2SequencerSnapshot(val ringSize: Int, val currentTier: Spo2Tier, val lastValidTs: Long, val invalidRunCount: Int)
interface Spo2EngineOps {
    fun ingest(path: String, value: Double, ts: Long)
    fun syncProfile()
    fun publish(payload: Spo2TierEventPayload)
}

internal fun buildSpo2EngineOps(
    engine: CepEngine,
    onPublish: (Spo2TierEventPayload) -> Unit = {}
): Spo2EngineOps = object : Spo2EngineOps {
    override fun ingest(path: String, value: Double, ts: Long) {
        engine.ingest(path, value, ts)
    }

    override fun syncProfile() {
        engine.updateSpo2Profile()
    }

    override fun publish(payload: Spo2TierEventPayload) {
        engine.handleTierEvent(payload)
        onPublish(payload)
    }
}

private data class ActiveBaselineSelection(
    val selectedFamilyForUpdate: String,
    val dominantWindowFamily: String?,
    val dominantWindowBucket: Int,
    val baselineFamilyForPredicates: String,
)
private data class TierPredicates(
    val warn: Boolean,
    val alert: Boolean,
    val emergency: Boolean,
)

class Spo2Sequencer(
    private val profile: ProfileStore,
    private val config: Spo2SequencerConfig = Spo2SequencerConfig(),
) {
    private val lock = ReentrantLock()
    private val ring = ArrayDeque<Spo2ValidReading>()
    private var currentTier: Spo2Tier = Spo2Tier.NONE
    private var lastEdgeTs = 0L
    private var lastHeldEmitTs = 0L
    private var lastValidTs = 0L
    private var clearCandidateSince: Long? = null
    private var invalidRunCount = 0

    fun onValid(
        ops: Spo2EngineOps,
        pct: Double,
        ts: Long,
        activityState: String,
        altitudeBucket: Int,
        motionOk: Boolean,
        ppgRedSustainedMs: Long,
        statusOk: Boolean,
    ) = lock.withLock {
        if (!isPctInRange(pct)) {
            invalidRunCount += 1
            ops.ingest("spo2.status_ok", 0.0, ts)
            return
        }
        val valid = statusOk && motionOk && ppgRedSustainedMs >= config.ppgRedSustainRequiredMs
        if (!valid) {
            ops.ingest("spo2.status_ok", if (statusOk) 1.0 else 0.0, ts)
            invalidRunCount += 1
            return
        }
        if (lastValidTs != 0L && (ts - lastValidTs) > config.maxGapMs) ring.clear()
        val bucket = altitudeBucket.coerceIn(0, 3)
        // Activity → baseline-family classification. Only STILL contributes to "resting" and
        // only WALKING/LIGHT_ACTIVITY contribute to "walk". RUN, VEHICLE, UNKNOWN, and any
        // future unclassified state must NOT advance either baseline (would depress resting
        // and degrade Warn sensitivity). Abstaining samples still feed tier detection via the
        // ring with a non-voting sentinel family so the dominant-window vote is unaffected.
        val selectedFamilyForUpdate: String? = when (activityState) {
            "WALKING", "LIGHT_ACTIVITY" -> "walk"
            "STILL" -> "resting"
            else -> null // RUN, VEHICLE, UNKNOWN → abstain from baseline-family updates
        }
        val familyForRing = selectedFamilyForUpdate ?: "abstain"
        ring.addLast(Spo2ValidReading(pct, ts, familyForRing, bucket))
        while (ring.size > config.ringCap) ring.removeFirst()
        lastValidTs = ts
        invalidRunCount = 0

        ops.ingest("spo2.pct", pct, ts)
        ops.ingest("spo2.status_ok", 1.0, ts)
        ops.ingest("spo2.ppg_red_sustained_ms", ppgRedSustainedMs.toDouble(), ts)

        if (selectedFamilyForUpdate != null) {
            profile.updateSpo2BaselinesEma(
                pct,
                ts,
                altitudeBucket = bucket,
                activityState = activityState,
                motionOk = motionOk
            )
        } else {
            Log.d("CEP_SPO2", "baseline_update_abstain reason=ACTIVITY_UNCLASSIFIED activity=$activityState pct=$pct bucket=$bucket")
        }
        ops.syncProfile()
        val baselineSelection = resolveActiveBaselineSelection(selectedFamilyForUpdate ?: "resting", bucket)
        val n = if (profile.userRiskProfile() == "trauma") max(2, config.consecutiveRequired / config.traumaSustainDivisor) else config.consecutiveRequired
        val lastNReadings = ring.takeLast(n)
        val lastN = lastNReadings.map { it.pct }
        val baseline = profile.spo2Baseline(baselineSelection.baselineFamilyForPredicates, baselineSelection.dominantWindowBucket)
        val provisional = profile.spo2BaselineProvisional(baselineSelection.baselineFamilyForPredicates, baselineSelection.dominantWindowBucket)
        val warnTarget = baseline - effectiveWarnDelta()
        val predicates = computePredicates(
            lastNReadings = lastNReadings,
            lastN = lastN,
            n = n,
            warnTarget = warnTarget,
            baseline = baseline,
            provisional = provisional,
            dominantWindowFamily = baselineSelection.dominantWindowFamily
        )
        val targetTier = when {
            predicates.emergency -> Spo2Tier.EMERGENCY
            predicates.alert -> Spo2Tier.ALERT
            predicates.warn -> Spo2Tier.WARN
            else -> Spo2Tier.NONE
        }

        if (targetTier != currentTier) {
            if (targetTier != Spo2Tier.NONE) {
                currentTier = targetTier
                lastEdgeTs = ts
                clearCandidateSince = null
                ops.publish(Spo2TierEventPayload(targetTier, Spo2EdgeState.ENTER, pct, ts))
            }
        } else if (targetTier != Spo2Tier.NONE && ts - lastHeldEmitTs >= config.heldIntervalMs) {
            lastHeldEmitTs = ts
            ops.publish(Spo2TierEventPayload(targetTier, Spo2EdgeState.HELD, pct, ts))
        }

        if (currentTier != Spo2Tier.NONE) {
            val clearThreshold = when (currentTier) {
                Spo2Tier.WARN -> warnTarget + config.clearHysteresisPct
                Spo2Tier.ALERT -> effectiveAlertPct() + config.clearHysteresisPct
                Spo2Tier.EMERGENCY -> effectiveEmergencyPct() + config.clearHysteresisPct
                else -> Double.POSITIVE_INFINITY
            }
            if (pct >= clearThreshold) {
                if (clearCandidateSince == null) clearCandidateSince = ts else if (ts - (clearCandidateSince ?: ts) >= config.clearSustainMs) {
                    ops.publish(Spo2TierEventPayload(currentTier, Spo2EdgeState.CLEAR, pct, ts, reason = "HYSTERESIS"))
                    currentTier = Spo2Tier.NONE
                    clearCandidateSince = null
                }
            } else {
                clearCandidateSince = null
            }
        }
    }

    fun onInvalid(ops: Spo2EngineOps, ts: Long, reason: InvalidReason) = lock.withLock {
        invalidRunCount += 1
        ops.ingest("spo2.status_ok", 0.0, ts)
        if (lastValidTs != 0L && (ts - lastValidTs) > config.maxGapMs) ring.clear()
        if (currentTier != Spo2Tier.NONE && lastValidTs != 0L && (ts - lastValidTs) > 5 * 60_000L) {
            ops.publish(Spo2TierEventPayload(currentTier, Spo2EdgeState.CLEAR, Double.NaN, ts, reason = "LONG_SENSOR_OUTAGE"))
            currentTier = Spo2Tier.NONE
            clearCandidateSince = null
        }
        Log.d("CEP_SPO2", "onInvalid reason=$reason run=$invalidRunCount")
    }

    fun reset(ops: Spo2EngineOps, reason: ResetReason) = lock.withLock {
        val now = System.currentTimeMillis()
        if (currentTier != Spo2Tier.NONE) ops.publish(Spo2TierEventPayload(currentTier, Spo2EdgeState.CLEAR, Double.NaN, now, reason = reason.name))
        ring.clear()
        currentTier = Spo2Tier.NONE
        lastEdgeTs = 0
        lastHeldEmitTs = 0
        lastValidTs = 0
        clearCandidateSince = null
        invalidRunCount = 0
        ops.ingest("spo2.status_ok", 0.0, now)
    }

    fun snapshot(): Spo2SequencerSnapshot = lock.withLock {
        Spo2SequencerSnapshot(ring.size, currentTier, lastValidTs, invalidRunCount)
    }

    private fun effectiveWarnDelta(): Double =
        if (profile.userRiskProfile() == "trauma") config.traumaWarnDeltaFromBaseline else config.warnDeltaFromBaseline
    private fun effectiveAlertPct(): Double =
        if (profile.userRiskProfile() == "trauma") config.traumaAlertPct else config.alertPct
    private fun effectiveEmergencyPct(): Double =
        if (profile.userRiskProfile() == "trauma") config.traumaEmergencyPct else config.emergencyPct

    private fun isPctInRange(pct: Double): Boolean =
        !pct.isNaN() && pct >= config.pctClampMin && pct <= config.pctClampMax

    private fun resolveActiveBaselineSelection(selectedFamilyForUpdate: String, fallbackBucket: Int): ActiveBaselineSelection {
        val familyCounts = ring.groupingBy { it.family }.eachCount()
        val dominantWindowFamily = when {
            familyCounts.isEmpty() -> selectedFamilyForUpdate
            familyCounts.size == 1 -> familyCounts.keys.first()
            familyCounts.getOrDefault("resting", 0) > familyCounts.getOrDefault("walk", 0) -> "resting"
            familyCounts.getOrDefault("walk", 0) > familyCounts.getOrDefault("resting", 0) -> "walk"
            else -> null // explicit mixed-window tie: WARN must abstain
        }
        val dominantWindowBucket = ring.groupingBy { it.altitudeBucket }.eachCount().maxByOrNull { it.value }?.key ?: fallbackBucket
        val baselineFamilyForPredicates = dominantWindowFamily ?: selectedFamilyForUpdate
        return ActiveBaselineSelection(
            selectedFamilyForUpdate = selectedFamilyForUpdate,
            dominantWindowFamily = dominantWindowFamily,
            dominantWindowBucket = dominantWindowBucket,
            baselineFamilyForPredicates = baselineFamilyForPredicates,
        )
    }

    private fun computePredicates(
        lastNReadings: List<Spo2ValidReading>,
        lastN: List<Double>,
        n: Int,
        warnTarget: Double,
        baseline: Double,
        provisional: Boolean,
        dominantWindowFamily: String?,
    ): TierPredicates {
        val highRiskFlag = profile.userRiskProfile() != "default"
        val emergencyCorroboratedByTime = lastNReadings.any { low ->
            low.pct < effectiveEmergencyPct() && lastNReadings.any { sample ->
                sample.ts != low.ts && sample.pct < effectiveAlertPct() && abs(sample.ts - low.ts) <= 120_000L
            }
        }
        val warn = dominantWindowFamily != null &&
            lastN.size == n &&
            lastN.all { it <= warnTarget } &&
            lastN.all { it >= config.alertPct } &&
            !provisional
        if (!warn &&
            dominantWindowFamily == null &&
            lastN.size == n &&
            lastN.all { it <= warnTarget } &&
            lastN.all { it >= config.alertPct } &&
            !provisional
        ) {
            Log.d("CEP_SPO2", "warn_abstain reason=MIXED_WINDOW n=$n baseline=$baseline warnTarget=$warnTarget")
        }
        val alert = lastN.size == n &&
            lastN.all { it < effectiveAlertPct() } &&
            (provisional || lastN.any { it <= (baseline - 4.0) } || highRiskFlag)
        val emergency = lastNReadings.any { it.pct < effectiveEmergencyPct() } && (highRiskFlag || emergencyCorroboratedByTime)
        return TierPredicates(warn = warn, alert = alert, emergency = emergency)
    }
}
