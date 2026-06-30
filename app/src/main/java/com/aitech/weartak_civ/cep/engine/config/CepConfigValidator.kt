package com.aitech.weartak_civ.cep.engine.config

import com.aitech.weartak_civ.cep.engine.CepConfig
import com.aitech.weartak_civ.cep.engine.FeatureDef
import com.aitech.weartak_civ.cep.engine.ExitCondition
import com.aitech.weartak_civ.cep.engine.RuleAction
import com.aitech.weartak_civ.cep.engine.RuleDef
import org.json.JSONArray
import org.json.JSONObject

/**
 * Validation rules per WEAR-508 design (Validation rules + [Update v1.1 — U6]).
 *
 * Validation runs on the merged definitional candidate AFTER [CepConfigMerger.merge].
 * Runtime sustain overrides are not part of the candidate (they are post-merge runtime
 * state restored during warm-up); validation must not depend on them.
 */
object CepConfigValidator {

    private val SUPPORTED_OPERATORS = setOf(
        "and", "or", "not", "all", "any",
        "ref", "is",
        "eq_key", "ne_key",
        "lte_key", "lt_key", "gte_key", "gt_key",
        "lte_key_fresh", "gte_key_fresh",
        "lte_keys", "gte_keys",
        "between", "zscore_lt"
    )

    private val SUPPORTED_SEVERITIES = setOf("INFO", "WARN", "ALERT", "EMERGENCY")

    /**
     * Validate the merged candidate.
     *
     * @param merge the merger output
     * @param overlay the parsed managed overlay (or null for built-in-only validation)
     * @param bounds tunable structural caps
     * @param effectiveHashBefore canonical hash of the previous authoritative config (for diagnostics)
     */
    fun validate(
        merge: CepConfigMerger.MergeResult,
        overlay: ParsedManagedOverlay?,
        bounds: CepConfigBounds = CepConfigBounds()
    ): ValidationResult {
        val cfg = merge.config
        validateCaps(cfg, bounds)?.let { return it }
        validateCadence(cfg, bounds)?.let { return it }
        validateFeatureDefinitions(cfg)?.let { return it }
        validateRuleDefinitions(cfg)?.let { return it }
        validateOverlayDuplicates(overlay)?.let { return it }
        validateDerivedGraph(cfg, bounds)?.let { return it }
        validateOrphans(cfg, overlay)?.let { return it }

        val effectiveHash = effectiveHash(cfg)
        val resolved = Resolved(
            engineConfig = cfg,
            featureSource = merge.featureSource,
            ruleSource = merge.ruleSource,
            effectiveHash = effectiveHash,
            pluginCollisions = merge.pluginCollisions
        )
        return ValidationResult.Ok(resolved)
    }

    private fun validateCaps(cfg: CepConfig, bounds: CepConfigBounds): ValidationResult? {
        if (cfg.rules.size > bounds.maxRules) {
            return ValidationResult.Err(
                ReasonCodes.MAX_RULES_EXCEEDED,
                "rules=${cfg.rules.size} exceeds cap ${bounds.maxRules}",
            )
        }
        if (cfg.features.size > bounds.maxFeatures) {
            return ValidationResult.Err(
                ReasonCodes.MAX_FEATURES_EXCEEDED,
                "features=${cfg.features.size} exceeds cap ${bounds.maxFeatures}",
            )
        }
        return null
    }

    private fun validateCadence(cfg: CepConfig, bounds: CepConfigBounds): ValidationResult? {
        val tickMs = if (cfg.tickHz <= 0) Int.MAX_VALUE else (1000 / cfg.tickHz)
        if (tickMs < bounds.minTickMs || tickMs > bounds.maxTickMs) {
            return ValidationResult.Err(
                ReasonCodes.CADENCE_OUT_OF_BOUNDS,
                "window_tick_ms=$tickMs outside [${bounds.minTickMs}, ${bounds.maxTickMs}]",
            )
        }
        if (cfg.ruleEvalIntervalMs < bounds.minTickMs || cfg.ruleEvalIntervalMs > bounds.maxTickMs) {
            return ValidationResult.Err(
                ReasonCodes.CADENCE_OUT_OF_BOUNDS,
                "rule_eval_interval_ms=${cfg.ruleEvalIntervalMs} outside [${bounds.minTickMs}, ${bounds.maxTickMs}]",
            )
        }
        return null
    }

    private fun validateFeatureDefinitions(cfg: CepConfig): ValidationResult? {
        val seenFeatures = HashSet<String>()
        for (f in cfg.features) {
            if (f.id.startsWith("__")) {
                return ValidationResult.Err(
                    ReasonCodes.RESERVED_ID,
                    "feature id ${f.id} starts with reserved __",
                )
            }
            if (!seenFeatures.add(f.id)) {
                return ValidationResult.Err(ReasonCodes.DUPLICATE_ID, "duplicate feature id ${f.id}")
            }
        }
        return null
    }

    private fun validateRuleDefinitions(cfg: CepConfig): ValidationResult? {
        val seenRules = HashSet<String>()
        for (r in cfg.rules) {
            if (r.id.startsWith("__")) {
                return ValidationResult.Err(
                    ReasonCodes.RESERVED_ID,
                    "rule id ${r.id} starts with reserved __",
                )
            }
            if (!seenRules.add(r.id)) {
                return ValidationResult.Err(ReasonCodes.DUPLICATE_ID, "duplicate rule id ${r.id}")
            }
            if (r.action.severity.uppercase() !in SUPPORTED_SEVERITIES) {
                return ValidationResult.Err(
                    ReasonCodes.UNSUPPORTED_SEVERITY,
                    "rule ${r.id} has unsupported severity ${r.action.severity}",
                )
            }
            if (r.sustainedForSec < 0 || r.holdDownSec < 0) {
                return ValidationResult.Err(
                    ReasonCodes.NEGATIVE_DURATION,
                    "rule ${r.id} has negative sustain/hold-down",
                )
            }
            val unsupported = collectUnsupportedOperators(r.condition)
            if (unsupported.isNotEmpty()) {
                return ValidationResult.Err(
                    ReasonCodes.UNSUPPORTED_OPERATOR,
                    "rule ${r.id} uses unsupported operators: ${unsupported.joinToString(",")}",
                )
            }
        }
        return null
    }

    private fun validateOverlayDuplicates(overlay: ParsedManagedOverlay?): ValidationResult? {
        if (overlay == null) return null
        val ovFeatureIds = HashSet<String>()
        for (f in overlay.features) {
            if (!ovFeatureIds.add(f.id)) {
                return ValidationResult.Err(
                    ReasonCodes.DUPLICATE_ID,
                    "overlay duplicate feature id ${f.id}",
                )
            }
        }
        val ovRuleIds = HashSet<String>()
        for (r in overlay.rules) {
            if (!ovRuleIds.add(r.id)) {
                return ValidationResult.Err(
                    ReasonCodes.DUPLICATE_ID,
                    "overlay duplicate rule id ${r.id}",
                )
            }
        }
        return null
    }

    private fun validateDerivedGraph(cfg: CepConfig, bounds: CepConfigBounds): ValidationResult? {
        val derivedDepth = checkDerivedGraph(cfg, bounds.maxDerivedDepth)
        if (derivedDepth is DerivedCheck.Cycle) {
            return ValidationResult.Err(
                ReasonCodes.DERIVED_CYCLE,
                "derived feature cycle detected at ${derivedDepth.atFeature}",
            )
        }
        if (derivedDepth is DerivedCheck.DepthExceeded) {
            return ValidationResult.Err(
                ReasonCodes.DERIVED_DEPTH_EXCEEDED,
                "derived chain exceeds depth ${bounds.maxDerivedDepth} at ${derivedDepth.atFeature}",
            )
        }
        return null
    }

    private fun validateOrphans(cfg: CepConfig, overlay: ParsedManagedOverlay?): ValidationResult? {
        val featureIds = cfg.features.map { it.id }.toHashSet()
        for (r in cfg.rules) {
            val refs = collectFeatureRefs(r)
            for (ref in refs) {
                if (!isAvailableFeatureRef(ref, featureIds)) {
                    val isReplaceAsymmetry = overlay != null &&
                        overlay.featuresMode == ParsedManagedOverlay.MODE_REPLACE &&
                        overlay.rulesMode != ParsedManagedOverlay.MODE_REPLACE
                    val reason = if (isReplaceAsymmetry) ReasonCodes.REPLACE_ASYMMETRY_ORPHANS_RULE
                    else ReasonCodes.POST_COMPOSE_ORPHAN
                    return ValidationResult.Err(
                        reason,
                        "rule ${r.id} references missing feature $ref",
                        mapOf("ruleId" to r.id, "missingFeatureId" to ref),
                    )
                }
            }
        }
        return null
    }

    /**
     * Canonical hash of the resolved definitional surface.
     * Includes engine cadence, features, and rules in canonical JSON.
     */
    fun effectiveHash(cfg: CepConfig): String {
        val root = JSONObject()
        root.put("tickHz", cfg.tickHz)
        root.put("ruleEvalIntervalMs", cfg.ruleEvalIntervalMs)
        val featuresArr = JSONArray()
        for (f in cfg.features) featuresArr.put(featureToJson(f))
        root.put("features", featuresArr)
        val rulesArr = JSONArray()
        for (r in cfg.rules) rulesArr.put(ruleToJson(r))
        root.put("rules", rulesArr)
        return CanonicalJson.sha256(CanonicalJson.canonicalize(root))
    }

    private fun isAvailableFeatureRef(ref: String, featureIds: Set<String>): Boolean {
        if (ref in featureIds) return true
        // The ruleset references some non-feature keys (e.g. `thresholds.*`, `settings.*`,
        // `profile.*`, raw topic values like `pressure`, `pressure.accuracy`). Only entries
        // that look like feature ids ("features.*") need to resolve to a defined feature.
        if (!ref.startsWith("features.")) return true
        return false
    }

    private fun collectFeatureRefs(rule: RuleDef): Set<String> {
        val out = HashSet<String>()
        collectRefsInJson(rule.condition, out)
        for (m in rule.action.monitor) {
            out += m.feature
            m.exitBelowKey?.let { out += it }
            m.exitAboveKey?.let { out += it }
        }
        return out
    }

    private fun collectRefsInJson(node: Any?, out: MutableSet<String>) {
        when (node) {
            is JSONObject -> collectRefsFromObject(node, out)
            is JSONArray -> collectRefsFromArray(node, out)
            else -> Unit
        }
    }

    private fun collectRefsFromObject(node: JSONObject, out: MutableSet<String>) {
        val it = node.keys()
        while (it.hasNext()) {
            val k = it.next()
            val v = node.opt(k)
            if (k.endsWith("_key") || k == "ref") {
                collectRefValue(v, out)
            }
            collectRefsInJson(v, out)
        }
    }

    private fun collectRefsFromArray(node: JSONArray, out: MutableSet<String>) {
        for (i in 0 until node.length()) collectRefsInJson(node.opt(i), out)
    }

    private fun collectRefValue(v: Any?, out: MutableSet<String>) {
        // A handful of shapes carry `[featureId, threshold, freshness]`;
        // pull the first string element if present.
        when (v) {
            is JSONArray -> {
                if (v.length() > 0) {
                    val firstStr = v.opt(0)
                    if (firstStr is String) out += firstStr
                }
            }
            is String -> out += v
        }
    }

    private fun collectUnsupportedOperators(node: Any?): Set<String> {
        val out = HashSet<String>()
        collectOperators(node, out)
        return out
    }

    private fun collectOperators(node: Any?, out: MutableSet<String>) {
        when (node) {
            is JSONObject -> collectOperatorsFromObject(node, out)
            is JSONArray -> collectOperatorsFromArray(node, out)
            else -> Unit
        }
    }

    private fun collectOperatorsFromObject(node: JSONObject, out: MutableSet<String>) {
        val it = node.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (k !in SUPPORTED_OPERATORS && !k.startsWith("__") && looksLikeOperator(k)) {
                out += k
            }
            collectOperators(node.opt(k), out)
        }
    }

    private fun collectOperatorsFromArray(node: JSONArray, out: MutableSet<String>) {
        for (i in 0 until node.length()) collectOperators(node.opt(i), out)
    }

    private fun looksLikeOperator(k: String): Boolean {
        // Operator names are short, lowercase, and contain only letters/underscores and digits.
        if (k.isEmpty() || k.length > 32) return false
        for (ch in k) {
            if (!ch.isLowerCase() && !ch.isDigit() && ch != '_') return false
        }
        return true
    }

    private sealed class DerivedCheck {
        data object Ok : DerivedCheck()
        data class Cycle(val atFeature: String) : DerivedCheck()
        data class DepthExceeded(val atFeature: String) : DerivedCheck()
    }

    private fun checkDerivedGraph(cfg: CepConfig, maxDepth: Int): DerivedCheck {
        val derivedById = cfg.features.filterIsInstance<FeatureDef.Derived>().associateBy { it.id }
        if (derivedById.isEmpty()) return DerivedCheck.Ok
        val deps = buildDerivedDependencies(derivedById)
        return detectDerivedGraphIssues(derivedById, deps, maxDepth)
    }

    private fun buildDerivedDependencies(derivedById: Map<String, FeatureDef.Derived>): Map<String, Set<String>> {
        val deps = HashMap<String, Set<String>>()
        for ((id, def) in derivedById) {
            val refs = HashSet<String>()
            collectRefsInJson(def.expr, refs)
            deps[id] = refs
        }
        return deps
    }

    private fun detectDerivedGraphIssues(
        derivedById: Map<String, FeatureDef.Derived>,
        deps: Map<String, Set<String>>,
        maxDepth: Int,
    ): DerivedCheck {
        val state = DerivedTraversalState(
            derivedById = derivedById,
            deps = deps,
            maxDepth = maxDepth,
            color = HashMap<String, Int>().apply {
                for (id in derivedById.keys) this[id] = COLOR_WHITE
            }
        )
        return traverseDerivedGraph(state)
    }

    private data class DerivedTraversalState(
        val derivedById: Map<String, FeatureDef.Derived>,
        val deps: Map<String, Set<String>>,
        val maxDepth: Int,
        val color: MutableMap<String, Int>,
    )

    private const val COLOR_WHITE = 0
    private const val COLOR_GRAY = 1
    private const val COLOR_BLACK = 2

    private fun traverseDerivedGraph(state: DerivedTraversalState): DerivedCheck {
        for (id in state.derivedById.keys) {
            if (state.color[id] == COLOR_WHITE) {
                val result = visitDerivedNode(state, id, 0)
                if (result !is DerivedCheck.Ok) return result
            }
        }
        return DerivedCheck.Ok
    }

    private fun visitDerivedNode(
        state: DerivedTraversalState,
        id: String,
        depth: Int,
    ): DerivedCheck {
        if (depth > state.maxDepth) return DerivedCheck.DepthExceeded(id)
        when (state.color[id]) {
            COLOR_GRAY -> return DerivedCheck.Cycle(id)
            COLOR_BLACK -> return DerivedCheck.Ok
            else -> Unit
        }
        state.color[id] = COLOR_GRAY
        val edges = state.deps[id] ?: emptySet()
        for (edge in edges) {
            if (edge == id) return DerivedCheck.Cycle(id)
            if (edge in state.derivedById) {
                val result = visitDerivedNode(state, edge, depth + 1)
                if (result !is DerivedCheck.Ok) return result
            }
        }
        state.color[id] = COLOR_BLACK
        return DerivedCheck.Ok
    }

    private fun featureToJson(f: FeatureDef): JSONObject {
        val o = JSONObject()
        o.put("id", f.id)
        o.put("update_interval_ms", f.updateIntervalMs)
        when (f) {
            is FeatureDef.Window -> {
                o.put("type", "window"); o.put("source", f.source); o.put("op", f.op)
                o.put("window_sec", f.windowSec); f.maxAgeSec?.let { o.put("max_age_sec", it) }
            }
            is FeatureDef.MultiWindow -> {
                o.put("type", "window_multi"); o.put("source", f.source)
                o.put("multi_policy", f.multiPolicy); o.put("op", f.op); o.put("window_sec", f.windowSec)
                f.maxAgeSec?.let { o.put("max_age_sec", it) }
            }
            is FeatureDef.SlopeMulti -> {
                o.put("type", "slope_multi"); o.put("source", f.source)
                o.put("multi_policy", f.multiPolicy); o.put("window_sec", f.windowSec)
                o.put("units_per_sec", f.unitsPerSec); f.maxAgeSec?.let { o.put("max_age_sec", it) }
            }
            is FeatureDef.Delta -> {
                o.put("type", "delta"); o.put("source", f.source); o.put("horizon_sec", f.horizonSec)
            }
            is FeatureDef.Derived -> {
                o.put("type", "derived"); o.put("expr", f.expr)
                f.maxAgeSec?.let { o.put("max_age_sec", it) }
            }
            is FeatureDef.SampleWindow -> {
                o.put("type", "sample_window"); o.put("source", f.source); o.put("op", f.op)
                o.put("sample_count", f.sampleCount); o.put("min_samples", f.minSamples)
                f.maxAgeSec?.let { o.put("max_age_sec", it) }
            }
            is FeatureDef.SampleDelta -> {
                o.put("type", "sample_delta"); o.put("source", f.source)
                o.put("horizon_samples", f.horizonSamples); f.maxAgeSec?.let { o.put("max_age_sec", it) }
            }
            is FeatureDef.SampleSlope -> {
                o.put("type", "sample_slope"); o.put("source", f.source)
                o.put("sample_count", f.sampleCount); o.put("units_per_sec", f.unitsPerSec)
                f.maxAgeSec?.let { o.put("max_age_sec", it) }
            }
        }
        return o
    }

    private fun ruleToJson(r: RuleDef): JSONObject {
        val o = JSONObject()
        o.put("id", r.id)
        if (r.description != null) o.put("description", r.description)
        o.put("when", r.condition)
        o.put("sustained_for_sec", r.sustainedForSec)
        if (r.sustainedForSecWarn != null) o.put("sustained_for_sec_warn", r.sustainedForSecWarn)
        o.put("hold_down_sec", r.holdDownSec)
        o.put("allow_sustain_override", r.allowSustainOverride)
        if (r.snapshotKeys.isNotEmpty()) o.put("snapshot_keys", JSONArray(r.snapshotKeys))
        o.put("action", actionToJson(r.action))
        return o
    }

    private fun actionToJson(action: RuleAction): JSONObject {
        val a = JSONObject()
        a.put("type", action.type)
        a.put("severity", action.severity)
        a.put("message", action.message)
        if (action.tags.isNotEmpty()) a.put("tags", JSONArray(action.tags))
        if (action.category != null) a.put("category", action.category)
        a.put("isTak", action.isTak)
        a.put("isLocal", action.isLocal)
        if (action.priorityFrom != null) a.put("priority_from", action.priorityFrom)
        if (action.priority != null) a.put("priority", action.priority)
        a.put("sendSms", action.sendSms)
        if (action.timeoutSec != null) a.put("timeout", action.timeoutSec)
        if (action.uid != null && action.uid.isNotEmpty()) a.put("uid", action.uid)
        val mon = monitorToJson(action.monitor)
        if (mon.length() > 0) a.put("monitor", mon)
        if (action.exitSustainSec != null) a.put("exit_sustain_sec", action.exitSustainSec)
        if (action.exitLogic != null) a.put("exit_logic", action.exitLogic)
        return a
    }

    private fun monitorToJson(monitor: List<ExitCondition>): JSONArray {
        val mon = JSONArray()
        for (m in monitor) {
            val mo = JSONObject()
            mo.put("feature", m.feature)
            if (m.exitBelow != null) mo.put("exit_below", m.exitBelow) else if (m.exitBelowKey != null) mo.put("exit_below", m.exitBelowKey)
            if (m.exitAbove != null) mo.put("exit_above", m.exitAbove) else if (m.exitAboveKey != null) mo.put("exit_above", m.exitAboveKey)
            mo.put("hysteresis", m.hysteresis)
            mon.put(mo)
        }
        return mon
    }
}
