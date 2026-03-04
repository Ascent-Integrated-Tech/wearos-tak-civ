# CEP sustained_for_sec Override — Code Change Snippets Only

**No changes are applied.** This document shows only the code snippets that would need to be added or modified, by file.

---

## 1. `app/src/main/java/com/aitech/weartak_civ/cep/engine/CepConfig.kt`

**Change:** Add `allowSustainOverride` to `RuleDef`.

**Current:**
```kotlin
data class RuleDef(
    val id: String,
    val description: String?,
    val condition: org.json.JSONObject,
    val sustainedForSec: Long,
    val holdDownSec: Long,
    val action: RuleAction,
    val snapshotKeys: List<String> = emptyList()
)
```

**Replace with:**
```kotlin
data class RuleDef(
    val id: String,
    val description: String?,
    val condition: org.json.JSONObject,
    val sustainedForSec: Long,
    val holdDownSec: Long,
    val action: RuleAction,
    val snapshotKeys: List<String> = emptyList(),
    val allowSustainOverride: Boolean = false
)
```

---

## 2. `app/src/main/java/com/aitech/weartak_civ/cep/engine/RuleCompiler.kt`

**Change 1:** In `parseRules()`, when building each `RuleDef`, add parsing of `allow_sustain_override` and pass it into `RuleDef`.

**Current (excerpt):**
```kotlin
            out += RuleDef(
                id = o.getString("id"),
                description = o.optString("description", null),
                condition = o.getJSONObject("when"),
                sustainedForSec = o.optLong("sustained_for_sec", 0),
                holdDownSec = o.optLong("hold_down_sec", 0),
                action = RuleAction(
```

**Replace with:**
```kotlin
            out += RuleDef(
                id = o.getString("id"),
                description = o.optString("description", null),
                condition = o.getJSONObject("when"),
                sustainedForSec = o.optLong("sustained_for_sec", 0),
                holdDownSec = o.optLong("hold_down_sec", 0),
                action = RuleAction(
```

**And add one more parameter to the same `RuleDef(...)` call (after `snapshotKeys`):**

**Current (closing of RuleDef):**
```kotlin
                snapshotKeys = o.optJSONArray("snapshot_keys")?.let { ja ->
                    List(ja.length()) { idx -> ja.getString(idx) }
                } ?: emptyList()
            )
```

**Replace with:**
```kotlin
                snapshotKeys = o.optJSONArray("snapshot_keys")?.let { ja ->
                    List(ja.length()) { idx -> ja.getString(idx) }
                } ?: emptyList(),
                allowSustainOverride = o.optBoolean("allow_sustain_override", false)
            )
```

---

## 3. `app/src/main/java/com/aitech/weartak_civ/cep/engine/TriggerTimer.kt`

**Change:** Remove sustain from constructor; add `effectiveSustainSec` parameter to `tick` and use it for maturity.

**Current (full file):**
```kotlin
package com.aitech.weartak_civ.cep.engine

class TriggerTimer(
    private val sustainedForSec: Long,
    private val holdDownSec: Long
) {
    private var activeFor = 0L
    private var lastFireAt = 0L
    fun tick(isTrue: Boolean, now: Long): Boolean {
        if (isTrue) activeFor++ else activeFor = 0
        val mature = activeFor >= sustainedForSec
        val cooldown = (now - lastFireAt) >= holdDownSec * 1000
        val fire = mature && cooldown
        if (fire) lastFireAt = now
        return fire
    }
}
```

**Replace with:**
```kotlin
package com.aitech.weartak_civ.cep.engine

class TriggerTimer(
    private val holdDownSec: Long
) {
    private var activeFor = 0L
    private var lastFireAt = 0L

    fun tick(isTrue: Boolean, now: Long, effectiveSustainSec: Long): Boolean {
        if (isTrue) activeFor++ else activeFor = 0
        val mature = activeFor >= effectiveSustainSec
        val cooldown = (now - lastFireAt) >= holdDownSec * 1000
        val fire = mature && cooldown
        if (fire) lastFireAt = now
        return fire
    }
}
```

---

## 4. `app/src/main/java/com/aitech/weartak_civ/cep/engine/RuleEngine.kt`

**Change 1:** Add constructor parameter `sustainOverrideProvider` and build timers with only `holdDownSec`. Add constant for max sustain (or use same as DataRepository).

**Current:**
```kotlin
class RuleEngine(
    private val scope: CoroutineScope,
    private val config: CepConfig,
    private val fs: FeatureStore
) {
    private val dispatcher = CepProvider.getActionDispatcher()
    private val compiledTimers = config.rules.associate { it.id to TriggerTimer(it.sustainedForSec, it.holdDownSec) }
```

**Replace with:**
```kotlin
class RuleEngine(
    private val scope: CoroutineScope,
    private val config: CepConfig,
    private val fs: FeatureStore,
    private val sustainOverrideProvider: () -> Map<String, Long> = { emptyMap() }
) {
    private val dispatcher = CepProvider.getActionDispatcher()
    private val compiledTimers = config.rules.associate { it.id to TriggerTimer(it.holdDownSec) }
```

**Change 2:** In `processTick`, compute effective sustain per rule and pass it into `tick`. Use a shared max-sustain constant (e.g. same as DataRepository: 86400L).

**Current:**
```kotlin
    suspend fun processTick(now: Long = System.currentTimeMillis()) {
        config.rules.forEach { r ->
            val cond = JsonLogic.eval(r.condition, fs)
            //val fired = compiledTimers[r.id]!!.tick(cond, now)

            val fired = compiledTimers[r.id]?.tick(cond, now) ?: false
```

**Replace with:**
```kotlin
    suspend fun processTick(now: Long = System.currentTimeMillis()) {
        val overrides = sustainOverrideProvider()
        config.rules.forEach { r ->
            val cond = JsonLogic.eval(r.condition, fs)
            val effectiveSustainSec = if (r.allowSustainOverride) {
                (overrides[r.id] ?: r.sustainedForSec).coerceIn(0L, MAX_SUSTAIN_SEC)
            } else {
                r.sustainedForSec
            }
            val fired = compiledTimers[r.id]?.tick(cond, now, effectiveSustainSec) ?: false
```

**Add at top of file (after imports) or in companion:**
```kotlin
    companion object {
        private const val MAX_SUSTAIN_SEC = 86400L
    }
```
(If the constant is defined in DataRepository or a shared CEP constants file, use that instead and import it.)

---

## 5. `app/src/main/java/com/aitech/weartak_civ/cep/engine/CepEngine.kt`

**Change 1:** Add override state and pass provider into RuleEngine.

**Current:**
```kotlin
    private val featureStore = FeatureStore()
    private val windows = WindowStore(config.tickHz, RingConfig.default())
    private val ruleEngine = RuleEngine(scope, config, featureStore)

    @Volatile
    private var currentYearOfBirth: Int = yearOfBirth
```

**Replace with:**
```kotlin
    private val featureStore = FeatureStore()
    private val windows = WindowStore(config.tickHz, RingConfig.default())

    @Volatile
    private var sustainOverrides: Map<String, Long> = emptyMap()

    private val ruleEngine = RuleEngine(
        scope,
        config,
        featureStore,
        sustainOverrideProvider = { sustainOverrides }
    )

    @Volatile
    private var currentYearOfBirth: Int = yearOfBirth
```

**Change 2:** Add `updateSustainOverrides()` and call it from `start()`.

**Current:**
```kotlin
    fun start() {
        Log.d("CEP", "CEP Engine started")

        //Call this fn whenever the yob is changed
        updateHrProfile()
```

**Replace with:**
```kotlin
    fun start() {
        Log.d("CEP", "CEP Engine started")

        //Call this fn whenever the yob is changed
        updateHrProfile()
        scope.launch { updateSustainOverrides() }
```

**Add new method (e.g. after `updateHrProfile()`):**
```kotlin
    suspend fun updateSustainOverrides() {
        try {
            sustainOverrides = dataRepository.getCepSustainOverrides().first()
        } catch (t: Throwable) {
            Log.e("CEP", "Failed to load CEP sustain overrides from DataRepository", t)
            sustainOverrides = emptyMap()
        }
    }
```

---

## 6. `app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt`

**Change 1:** Add preference key (in the object that holds other keys, e.g. near `RESTING_HR_HIGH_ALERT_BPM_THRESHOLD`).

**Add:**
```kotlin
        private val CEP_SUSTAINED_FOR_SEC_OVERRIDES_JSON = stringPreferencesKey("cep_sustained_for_sec_overrides_json")
```

**Change 2:** Add constant and parsing helper (can be a private function or inline in the Flow). Use existing `gson` and `TypeToken` pattern.

**Add constant (e.g. in same object or top-level):**
```kotlin
        private const val CEP_MAX_SUSTAIN_SEC = 86400L
```

**Add parsing helper (same file, e.g. as private function):**
```kotlin
    private fun parseCepSustainOverrides(json: String?): Map<String, Long> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, Long>>() {}.type
            val raw = gson.fromJson<Map<String, Long>>(json, type) ?: return emptyMap()
            raw.mapValues { (_, v) -> v.coerceIn(0L, CEP_MAX_SUSTAIN_SEC) }
        } catch (e: Exception) {
            android.util.Log.w("DataRepository", "Invalid CEP sustain overrides JSON", e)
            emptyMap()
        }
    }
```
(If the key `CEP_MAX_SUSTAIN_SEC` is inside a nested object, use the correct path, e.g. `Keys.CEP_MAX_SUSTAIN_SEC` or move the constant to where other CEP/DataRepository constants live.)

**Change 3:** Add Flow and store (e.g. after the resting HR threshold APIs).

**Add:**
```kotlin
    val getCepSustainOverrides: Flow<Map<String, Long>> = context.dataStore.data.map { prefs ->
        parseCepSustainOverrides(prefs[CEP_SUSTAINED_FOR_SEC_OVERRIDES_JSON])
    }

    suspend fun storeCepSustainOverrides(map: Map<String, Long>) {
        context.dataStore.edit { prefs ->
            val sanitized = map.mapValues { (_, v) -> v.coerceIn(0L, CEP_MAX_SUSTAIN_SEC) }
            prefs[CEP_SUSTAINED_FOR_SEC_OVERRIDES_JSON] = gson.toJson(sanitized)
        }
    }
```

(Use the actual key name if it lives in a nested object, e.g. `Keys.CEP_SUSTAINED_FOR_SEC_OVERRIDES_JSON`.)

---

## 7. Call site after saving overrides (e.g. Settings or CEP settings screen)

**When the user saves CEP sustain overrides:**

1. Call `dataRepository.storeCepSustainOverrides(newMap)` (suspend or from coroutine).
2. Then call `CepProvider.getOrCreateEngine(context).updateSustainOverrides()`.

**Snippet (conceptual; exact file/location depends on where you add the UI):**
```kotlin
// After persisting overrides, e.g. in a save callback or coroutine:
scope.launch {
    dataRepository.storeCepSustainOverrides(newOverridesMap)
    CepProvider.getOrCreateEngine(context).updateSustainOverrides()
}
```
Or if the engine is already available and `updateSustainOverrides` is suspend:
```kotlin
    dataRepository.storeCepSustainOverrides(newOverridesMap)
    cepEngine.updateSustainOverrides()
```

---

## 8. Optional: `res/raw/cep_rules.json` (per-rule opt-in)

To make specific rules use overrides, add the flag to those rules only. No change to rules that should stay static.

**Example (one rule that may be overridden):**
```json
{
  "id": "cold_state_alert",
  "description": "...",
  "when": { ... },
  "sustained_for_sec": 60,
  "allow_sustain_override": true,
  "action": { ... }
}
```

Rules without `"allow_sustain_override"` (or with `false`) keep static sustain from `sustained_for_sec`.

---

## Summary of files to touch

| File | Changes |
|------|--------|
| `CepConfig.kt` | Add `allowSustainOverride: Boolean = false` to `RuleDef`. |
| `RuleCompiler.kt` | Parse `allow_sustain_override` and pass into `RuleDef`. |
| `TriggerTimer.kt` | Constructor takes only `holdDownSec`; `tick(isTrue, now, effectiveSustainSec)`. |
| `RuleEngine.kt` | Add `sustainOverrideProvider`, build `TriggerTimer(holdDownSec)`; in `processTick` compute effective sustain and call `tick(..., effectiveSustainSec)`. |
| `CepEngine.kt` | Add `sustainOverrides`, pass provider to `RuleEngine`, add `updateSustainOverrides()`, call it in `start()`. |
| `DataRepository.kt` | Add key, `getCepSustainOverrides` Flow, `storeCepSustainOverrides`, and parse helper. |
| Call site (e.g. Settings) | After saving overrides: `storeCepSustainOverrides` then `updateSustainOverrides()`. |
| `cep_rules.json` (optional) | Add `"allow_sustain_override": true` to rules that may be overridden. |

No other files need to be modified for the core behavior. CepProvider stays as-is (engine is still built the same way; only `RuleEngine` and `CepEngine` internals change).
