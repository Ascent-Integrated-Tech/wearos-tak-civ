package com.aitech.weartak_civ.cep.engine.action

import com.aitech.weartak_civ.cep.engine.ExitCondition
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks which rule IDs currently have an active alert, so we don't
 * keep re-firing the same alert every tick while the condition
 * remains true, and so we can auto-clear alerts when their exit
 * conditions are met.
 */
object AlertRegistry {

    data class ActiveAlert(
        val ruleId: String,
        val uid: String,
        val startedAtMs: Long,
        val exitConditions: List<ExitCondition>,
        val exitLogic: String,
        val exitSustainSec: Long,

        // Internal: when exit condition first became true
        var exitSatisfiedSinceMs: Long? = null
    )

    // ruleId -> active alert
    private val active = ConcurrentHashMap<String, ActiveAlert>()

    /**
     * Try to mark this rule as "active".
     *
     * @param ruleId CEP rule id
     * @param uid    UID we plan to use for the TAK marker
     * @param exitMetricKey metric to watch for clearing (e.g. "features.hrr_percent")
     * @param exitBelow clear when metric < (exitBelow - hysteresis)
     * @param exitAbove clear when metric > (exitAbove + hysteresis)
     */
    fun tryActivate(
        ruleId: String,
        uid: String,
        exitConditions: List<ExitCondition>,
        exitLogic: String,
        exitSustainSec: Long
    ): Boolean {
        val now = System.currentTimeMillis()
        if (active.containsKey(ruleId)) return false

        active[ruleId] = ActiveAlert(
            ruleId = ruleId,
            uid = uid,
            startedAtMs = now,
            exitConditions = exitConditions,
            exitLogic = exitLogic,
            exitSustainSec = exitSustainSec
        )
        return true
    }


    /** Mark the rule's alert as cleared. */
    fun clear(ruleId: String) {
        active.remove(ruleId)
    }

    fun get(ruleId: String): ActiveAlert? = active[ruleId]

    /** Snapshot of all active alerts for the engine to inspect each tick. */
    fun allActive(): Collection<ActiveAlert> = active.values.toList()
}
