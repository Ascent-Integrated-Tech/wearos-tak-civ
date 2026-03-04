# CEP Sustained_for_sec Override — Detailed Design (Hybrid Static + Dynamic)

**Design only.** No file edits, no diffs, no implementation. Illustrative snippets show intended API shape and logic.

---

## 1) Architecture / Control-Flow Diagram

### End-to-end control flow (textual)

1. **JSON load**  
   `RuleCompiler.loadFromRaw(context)` reads `res/raw/cep_rules.json` into a string and passes it to `parse(JSONObject(json))`.

2. **Parse**  
   `RuleCompiler.parse(root)`:
   - Reads `engine.window_tick_ms` → tickHz.
   - `parseFeatures(root.optJSONArray("features"))` → `List<FeatureDef>`.
   - `parseRules(root.optJSONArray("rules"))` → `List<RuleDef>` (each rule gets `id`, `condition`, `sustained_for_sec`, `hold_down_sec`, `action`, etc.).

3. **In-memory rule model**  
   Each `RuleDef` is built with:
   - `sustainedForSec = o.optLong("sustained_for_sec", 0)`
   - **New:** `allowSustainOverride = o.optBoolean("allow_sustain_override", false)`  
   No other code path mutates these; they are immutable after parse.

4. **Engine init**  
   `CepProvider.buildEngine(context)`:
   - Calls `RuleCompiler.loadFromRaw(context)` → `CepConfig` (includes rules with `allowSustainOverride`).
   - Gets `DataRepository` (e.g. from `WearTakApplication.repo`).
   - Constructs `CepEngine(context, scope, config, yearOfBirth, dataRepo)`.
   - Inside `CepEngine`: constructs `RuleEngine(scope, config, featureStore, sustainOverrideProvider)` where `sustainOverrideProvider` is a lambda that returns the engine’s current override map snapshot.
   - `RuleEngine` builds `compiledTimers = config.rules.associate { it.id to TriggerTimer(it.holdDownSec) }` — timers no longer store sustain (see Section 4); they only need hold-down for cooldown.
   - On `CepEngine.start()`: `updateHrProfile()` and **`updateSustainOverrides()`** are invoked so the override map is populated before the first tick.

5. **Timer state**  
   Each `TriggerTimer` holds only:
   - `holdDownSec` (immutable, from rule).
   - `activeFor` (mutable: consecutive ticks the condition has been true).
   - `lastFireAt` (mutable: last fire timestamp for cooldown).  
   Sustain is **not** stored in the timer; it is supplied each tick (see intervention point below).

6. **Tick loop**  
   `CepEngine`’s tick loop (e.g. in `start()`):
   - `featureStore.beginTick()`
   - `computeFeaturesSafely()` (windows, derived)
   - `ruleEngine.processTick(now)`
   - `checkAndClearAlerts()`
   - `delay(tickMs)`

7. **Maturity decision (intervention point)**  
   Inside `RuleEngine.processTick(now)`:
   - For each rule `r`:
     - Evaluate condition: `cond = JsonLogic.eval(r.condition, fs)`.
     - **Intervention:** Compute effective sustain:
       - `effectiveSustainSec = if (r.allowSustainOverride) (overrideMap[r.id] ?: r.sustainedForSec) else r.sustainedForSec`
       - Apply validation: `effectiveSustainSec = effectiveSustainSec.coerceIn(0L, MAX_SUSTAIN_SEC)` (see Section 3).
     - Call `compiledTimers[r.id]?.tick(cond, now, effectiveSustainSec)`.
     - Timer uses **passed-in** `effectiveSustainSec` for maturity: `mature = activeFor >= effectiveSustainSec` (and existing cooldown logic).
   - Override map is obtained **once per tick** via `sustainOverrideProvider()` so the whole tick sees a consistent snapshot.

8. **Action emission**  
   If `tick(...)` returns true: build `TriggerContext`, pass to dispatcher; handlers (e.g. `LogActionHandler`) emit alerts as today. No change to action path.

### Intervention point summary

- **Where:** Inside `RuleEngine.processTick()`, between condition evaluation and `TriggerTimer.tick(...)`.
- **What:** Compute `effectiveSustainSec` using the gate `if (rule.allowSustainOverride) override else rule.sustainedForSec`, then pass `effectiveSustainSec` into `TriggerTimer.tick(..., effectiveSustainSec)`.
- **When:** Every tick, for every rule; no engine rebuild, no timer recreation.

---

## 2) Gating Mechanism (Choice and Justification)

### Recommendation: **Option A — JSON flag**

Add an optional rule-level field in `cep_rules.json`:

- **Key:** `"allow_sustain_override"`
- **Type:** boolean
- **Default when absent:** `false`

**Parsing:** In `RuleCompiler.parseRules()`, when building each `RuleDef`:

- `allowSustainOverride = o.optBoolean("allow_sustain_override", false)`

**Model:** Add to `RuleDef` in `CepConfig.kt`:

- `val allowSustainOverride: Boolean = false`

**Justification**

- **Single source of truth:** Rule author explicitly marks which rules are overridable. No second list in code that can drift from the rule set.
- **Long-term maintainability:** Adding/removing dynamic behavior is a JSON edit; no code change or release needed for new rules.
- **Minimal risk:** Default `false` keeps all existing rules static; only rules that opt in are affected by overrides.
- **Clear semantics:** Static rules are “immune” by definition: the gate `if (rule.allowSustainOverride)` ensures that when `allowSustainOverride == false`, `effectiveSustainSec` is always `rule.sustainedForSec`, regardless of what is in the override map (including stray entries for that rule id).
- **Auditability:** A reviewer can grep for `allow_sustain_override` in the JSON to see which rules are dynamic.

**Option B (code allowlist) — why not**

- **Duplication:** Rule ids live in JSON and again in a Kotlin `Set`; easy to forget to add a new rule id when adding a rule.
- **Fragility:** Renaming a rule id in JSON requires a code change.
- **Weaker semantics:** “Static” is defined by absence from the allowlist rather than by an explicit rule property; harder to reason about and document.

**Conclusion:** Option A is the primary mechanism. Static rules are immune because the gate checks `rule.allowSustainOverride` first; override map is only consulted when that is true.

---

## 3) DataRepository Design (Detailed)

### Storage backend and key

- **Backend:** DataStore (same as existing app preferences), i.e. `context.dataStore` from `preferencesDataStore(name = "data")`.
- **Key type:** `stringPreferencesKey` (DataStore does not support arbitrary maps; string is the standard way to store structured data).
- **Key name:** e.g. `"cep_sustained_for_sec_overrides_json"` (constant in the same style as `RESTING_HR_HIGH_ALERT_BPM_THRESHOLD`).

**Why JSON string**

- DataStore supports only primitive/value types; a map requires serialization. The repo already uses Gson + string keys for list/map-like data (e.g. SMS list, TAK server list, category roles). Reusing that pattern keeps consistency and avoids new dependencies. Alternative (e.g. multiple keys like `cep_sustain_override_<ruleId>`) would require iterating known rule ids or maintaining a separate “keys” list and is more brittle. One JSON blob is one read/write and one place to validate.

### API shape

- **Read:**  
  `fun getCepSustainOverrides(): Flow<Map<String, Long>>`  
  Emits the current override map (rule id → sustain seconds). Empty map when key is missing or invalid.

- **Write:**  
  `suspend fun storeCepSustainOverrides(map: Map<String, Long>)`  
  Replaces the stored override map with the given map (full replace; no per-key merge in the minimal design).

### Serialization format

- **Stored value:** JSON object with string keys (rule id) and number values (seconds), e.g. `{"cold_state_alert":30,"hr_high_threshold_resting_alert":20}`.
- **Gson:** Use a type such as `Map<String, Long>` (or `Map<String, Number>` then convert to Long). Serialize with `Gson().toJson(map)`; deserialize with something like `Gson().fromJson(json, object : TypeToken<Map<String, Long>>(){}.type)` (or equivalent for the project’s Gson usage). All numeric values stored as seconds (Long).

### Validation rules

- **Negative values:** Clamp to 0 at read time (when building the map to return). So `effectiveSustainSec` in the engine will never be negative. Optionally also clamp in `storeCepSustainOverrides` before persisting so stored data is always valid.
- **Upper bound:** Propose a **safe cap** (e.g. `MAX_SUSTAIN_SEC = 86400` = 24 hours). Reason: avoids accidental “never fires” (e.g. typo 864000) and keeps timer math within a reasonable range. Apply the cap when **reading** (in the Flow map transformation) so that even old/corrupted stored values are bounded. Document that values above the cap are clamped to the cap.

### Migration / backward compatibility

- **Missing key:** If the preference key is absent, `prefs[KEY]` is null. Return `emptyMap()` and do not write. No migration step required.
- **Corrupted / invalid JSON:** In the Flow’s `map { }` block, catch exceptions from `Gson.fromJson`, log, and return `emptyMap()`. Same in any synchronous helper that parses the string. Never throw to the caller; always fall back to empty map so the engine simply uses JSON defaults for all rules.
- **Old format:** If a future format change is ever needed (e.g. version prefix), the parser can check a version field and branch; for the initial design, a single unversioned JSON object is sufficient.

### Illustrative snippets (DataRepository)

**Key and serialization:**

```kotlin
// In DataRepository (or its keys object):
private val CEP_SUSTAINED_FOR_SEC_OVERRIDES_JSON = stringPreferencesKey("cep_sustained_for_sec_overrides_json")

private const val MAX_SUSTAIN_SEC = 86400L

private fun parseSustainOverrides(json: String?): Map<String, Long> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
        val raw = gson.fromJson<Map<String, Any?>>(json, ...)
        raw.mapNotNull { (k, v) ->
            when (v) {
                is Number -> k to v.toLong().coerceIn(0L, MAX_SUSTAIN_SEC)
                else -> null
            }
        }.toMap()
    } catch (e: Exception) {
        Log.w(TAG, "Invalid CEP sustain overrides JSON", e)
        emptyMap()
    }
}
```

**Flow and store:**

```kotlin
val getCepSustainOverrides: Flow<Map<String, Long>> = context.dataStore.data.map { prefs ->
    parseSustainOverrides(prefs[CEP_SUSTAINED_FOR_SEC_OVERRIDES_JSON])
}

suspend fun storeCepSustainOverrides(map: Map<String, Long>) {
    context.dataStore.edit { prefs ->
        val sanitized = map.mapValues { (_, v) -> v.coerceIn(0L, MAX_SUSTAIN_SEC) }
        prefs[CEP_SUSTAINED_FOR_SEC_OVERRIDES_JSON] = gson.toJson(sanitized)
    }
}
```

(Implementation will use the project’s existing Gson instance and TypeToken style.)

---

## 4) CEP Engine Integration Design (Detailed)

### Where the override map lives

- **Owner:** `CepEngine`.
- **Storage:** A single field, e.g. `@Volatile private var sustainOverrides: Map<String, Long> = emptyMap()`.
- **Reason:** Same process as `updateHrProfile()`: the engine holds a snapshot that is updated explicitly from DataRepository. RuleEngine does not hold the map; it receives it via a provider lambda so it never depends on DataRepository directly and stays testable.

### How it is updated

- **Method:** `suspend fun updateSustainOverrides()` on `CepEngine`.
- **Body:** Run `sustainOverrides = dataRepository.getCepSustainOverrides().first()` (or the same with a runBlocking if the engine must expose a non-suspend entry; prefer suspend and call from a coroutine). The Flow emits once with the current map; assign that to `sustainOverrides`. No merge with previous map; full replace.
- **Mirrors:** `updateHrProfile()`: same pattern (read from DataRepository, update in-memory state used on the next tick).

### Thread-safety

- **Tick thread:** The CEP tick loop runs in a single coroutine (e.g. on `Dispatchers.Default`). Each tick calls `ruleEngine.processTick(now)` once.
- **Override reads:** In `processTick`, call `val overrides = sustainOverrideProvider()` once at the start of the tick. Use that snapshot for all rules in that tick. So within one tick there is no mid-tick change of overrides.
- **Override writes:** Only `updateSustainOverrides()` writes to `sustainOverrides`. That method is called from the main thread (or from a coroutine) when the user saves settings. Assignment is a single reference write. Using `@Volatile` ensures the tick thread sees the updated reference after the write (no caching of the map reference). The map itself should be an immutable map (e.g. from DataRepository or `.toMap()` so no one mutates it); then no further synchronization is needed.
- **Summary:** One snapshot per tick + volatile map reference + immutable map content gives safe behavior without locks.

### Avoiding reset of TriggerTimer state

- **Do not recreate timers.** `compiledTimers` is built once with one `TriggerTimer` per rule. Each timer keeps `activeFor` and `lastFireAt`.
- **Do not change sustain inside the timer.** Sustain is not stored in the timer; it is passed in each tick as an argument. So when overrides are updated, the next tick simply uses the new effective value for the maturity check; `activeFor` and `lastFireAt` are unchanged. No reset.

### TriggerTimer API shape — recommended approach

**Recommendation: add an `effectiveSustainSec` parameter to `TriggerTimer.tick(...)`.**

**Proposed signature:**

- `fun tick(isTrue: Boolean, now: Long, effectiveSustainSec: Long): Boolean`

**Behavior:**

- Use `effectiveSustainSec` (the parameter) for maturity: `mature = activeFor >= effectiveSustainSec`.
- Keep `holdDownSec` in the constructor and continue to use it for cooldown (unchanged).
- Internal state: still only `activeFor` and `lastFireAt`; no sustain field in the timer.

**Why this is least risky and single source of truth**

- **Single source of truth:** The only place that decides “how many seconds this rule needs” is the caller (RuleEngine). RuleEngine has the rule, the override map, and the gating logic; it computes one value per tick and passes it in. The timer is a pure function of (condition, time, effectiveSustain, holdDown): it does not need to know about overrides or rules.
- **No ambiguity:** There is no “stored sustain” vs “override” inside the timer; there is only “this tick’s effective sustain.” So no risk of using a stale or wrong value inside the timer.
- **Least invasive:** The timer stays stateless with respect to sustain. No new mutable field, no setter, no need to invalidate or sync. Only the signature of `tick` gains one parameter; call sites (one: RuleEngine) pass the computed value.
- **Alternative (updateable sustain in timer) rejected:** If the timer held a mutable `sustainedForSec` and the engine updated it from outside, the timer would have two sources of truth (initial rule value vs override). You’d need rules about when to update (every tick? only when override map changes?) and would have to ensure no race with the tick. Passing the value per tick avoids all of that.

**Illustrative snippets**

**TriggerTimer (conceptual):**

```kotlin
class TriggerTimer(private val holdDownSec: Long) {
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

**RuleEngine (construction and tick path):**

- Constructor: accept `sustainOverrideProvider: () -> Map<String, Long>` (e.g. default `{ emptyMap() }`). Build timers with only hold-down: `config.rules.associate { it.id to TriggerTimer(it.holdDownSec) }`.
- In `processTick(now)`:
  - `val overrides = sustainOverrideProvider()`
  - For each rule `r`:  
    `effectiveSustainSec = if (r.allowSustainOverride) (overrides[r.id] ?: r.sustainedForSec) else r.sustainedForSec`  
    then clamp: `effectiveSustainSec = effectiveSustainSec.coerceIn(0L, MAX_SUSTAIN_SEC)` (reuse same constant as DataRepository or define in a shared place).  
    `fired = compiledTimers[r.id]?.tick(cond, now, effectiveSustainSec) ?: false`

**CepEngine (override state and provider):**

- Field: `@Volatile private var sustainOverrides: Map<String, Long> = emptyMap()`
- When constructing RuleEngine: `RuleEngine(..., sustainOverrideProvider = { sustainOverrides })`
- `suspend fun updateSustainOverrides() { sustainOverrides = dataRepository.getCepSustainOverrides().first() }`
- In `start()`: besides `updateHrProfile()`, call `updateSustainOverrides()` (e.g. in the same or another `scope.launch`).

---

## 5) Runtime Update Flow (Detailed)

### Engine start: load overrides into the engine

- In `CepEngine.start()` (or equivalent), after/subscribe to the same place where `updateHrProfile()` is called:
  - Call `updateSustainOverrides()` so that `sustainOverrides` is populated from DataRepository before the tick loop runs.
  - If the preference was never set, `getCepSustainOverrides().first()` yields `emptyMap()`; then all dynamic rules use their JSON `sustainedForSec`; static rules are unchanged.
- The tick loop then runs; every `processTick` uses `sustainOverrideProvider()` which returns the current `sustainOverrides`.

### After saving overrides: update engine map

- The UI/settings path that persists overrides must:
  1. Call `dataRepository.storeCepSustainOverrides(map)` (or equivalent).
  2. Then call `CepProvider.getOrCreateEngine(context).updateSustainOverrides()` (or a method that does the same). That re-reads from DataRepository and assigns into `sustainOverrides`.
- The **next** tick will see the new map because `sustainOverrideProvider()` returns the updated reference. No engine rebuild, no timer reset.

### Sequence (bullet list)

1. User changes sustain overrides in UI and taps Save.
2. **UI/Settings:** Call `dataRepository.storeCepSustainOverrides(newMap)` (suspend/blocking until write completes).
3. **UI/Settings:** Call `CepProvider.getOrCreateEngine(context).updateSustainOverrides()` (suspend until read + assign completes).
4. **CepEngine.updateSustainOverrides():** `sustainOverrides = dataRepository.getCepSustainOverrides().first()`.
5. Next CEP tick: `ruleEngine.processTick(now)` runs.
6. **RuleEngine:** `overrides = sustainOverrideProvider()` → gets current `sustainOverrides`.
7. For each rule, `effectiveSustainSec` is computed with gate + override map; `TriggerTimer.tick(cond, now, effectiveSustainSec)` uses new value where applicable; `activeFor`/`lastFireAt` unchanged.
8. Actions fire as before when maturity and cooldown are satisfied.

---

## 6) Behavioral Semantics and Edge Cases

| Scenario | Expected behavior |
|----------|-------------------|
| **Override decreases mid-accumulation** | Rule has been true for 40 ticks; override changes from 60s to 30s. On the next tick, `effectiveSustainSec = 30`, `activeFor = 40` → mature. Rule can fire (sooner than before). No reset of `activeFor`; we do not “start over.” |
| **Override increases mid-accumulation** | Rule has been true for 20 ticks; override changes from 10s to 60s. On the next tick, `effectiveSustainSec = 60`, `activeFor = 20` → not mature. Rule needs 40 more consecutive true ticks. No reset; accumulation continues. |
| **Override becomes 0** | Effective sustain 0 → `mature = (activeFor >= 0)` so one tick of true is enough. Intended: “immediate” fire when condition becomes true. |
| **Override removed** | Key removed from stored map (or entire map cleared). Next `updateSustainOverrides()` loads empty or missing entry for that rule. For dynamic rules, fallback: `overrideMap[r.id] ?: r.sustainedForSec` → use JSON default. No need to recreate timers. |
| **Static rule with override present** | Gate: `if (rule.allowSustainOverride)` is false → use `rule.sustainedForSec` only. Entry in override map for that rule id is ignored. Static rules are immune. |
| **Time unit correctness** | All sustain values are in **seconds**. `activeFor` in the current implementation is incremented by 1 per tick; if tick period is 1s, it counts seconds. If tick period differs, the design should either (a) keep “activeFor = ticks” and document that effectiveSustainSec is in “tick count” for that rule, or (b) make TriggerTimer work in wall-clock seconds (e.g. track first-true timestamp and compare with `now`). The existing code uses tick count; the design preserves that: effectiveSustainSec is in the same unit as the current `sustainedForSec` (ticks if the code interprets it as ticks, or seconds if the tick interval is 1s). Clarify in implementation: if `window_tick_ms` is 1000, then 1 tick = 1 second and the numeric value is seconds; otherwise the engine may need to convert (e.g. effectiveSustainSec in seconds → ticks using tickHz). |
| **Clock source / monotonic time** | Cooldown uses `now` (typically `System.currentTimeMillis()`). Same as today; no change. Sustain uses tick count (`activeFor`), not wall clock. So only cooldown depends on clock; maturity does not. |

---

## 7) Test Strategy (Detailed)

### Unit tests: TriggerTimer maturity with dynamic sustain

- **Objective:** Ensure that when `tick(..., effectiveSustainSec)` is used, maturity follows the **passed-in** value, not any stored value.
- **Cases:**
  - Condition true for N ticks; call `tick(true, now, N)` → mature (true); `tick(true, now, N+1)` → not mature.
  - Same `activeFor`, different effectiveSustainSec in two calls: e.g. `tick(true, now, 10)` when `activeFor == 5` → false; then `tick(true, now, 3)` → true (proves parameter is used).
  - effectiveSustainSec = 0: one tick true → fire.
  - Condition goes false: next tick `activeFor` resets; then true again: accumulation restarts.
- **Pseudo-code:**

```kotlin
// TriggerTimer(holdDownSec = 0) for simplicity
val t = TriggerTimer(holdDownSec = 0)
var now = 0L
// Accumulate 5 true ticks
repeat(5) { t.tick(true, now++, 10) }
assertFalse(t.tick(true, now++, 10))  // 6 < 10? no
// Same timer, pass 5 as required
assertTrue(t.tick(true, now++, 5))    // 6 >= 5
```

### Unit tests: gating (static vs dynamic)

- **Objective:** Verify `effectiveSustainSec = if (allowSustainOverride) (overrideMap[id] ?: rule.sustainedForSec) else rule.sustainedForSec`.
- **Setup:** A small RuleEngine (or a test helper that only does the effective-sustain computation) with a config of 2–3 rules: one static (`allowSustainOverride = false`), one or two dynamic (`allowSustainOverride = true`). Override map with entries for both rule ids.
- **Cases:**
  - Static rule: override map has entry for static rule id; assert effective sustain equals rule’s `sustainedForSec`, not the map value.
  - Dynamic rule with override present: effective sustain equals map value.
  - Dynamic rule with no override: effective sustain equals rule’s `sustainedForSec`.
  - Dynamic rule with override 0: effective sustain 0 (after clamp).
- **Pseudo-code:**

```kotlin
val staticRule = RuleDef(id = "static", ..., sustainedForSec = 60, allowSustainOverride = false, ...)
val dynamicRule = RuleDef(id = "dynamic", ..., sustainedForSec = 20, allowSustainOverride = true, ...)
val overrides = mapOf("static" to 100L, "dynamic" to 30L)
fun effective(r: RuleDef) = if (r.allowSustainOverride) (overrides[r.id] ?: r.sustainedForSec) else r.sustainedForSec
assertEquals(60L, effective(staticRule))   // ignored override
assertEquals(30L, effective(dynamicRule))
```

### Integration-style test: update overrides and verify next tick without timer reset

- **Objective:** After updating the override map in the engine, the next tick uses the new value and does not reset `activeFor`.
- **Setup:** Create CepEngine (or RuleEngine + override provider) with a single rule that is dynamic, e.g. sustain 10. Run several ticks with condition true so `activeFor` reaches e.g. 5. Then update the override map (via `updateSustainOverrides()` or by injecting a provider that returns a new map) so the effective sustain becomes 3. Run one more tick.
- **Assert:** Rule fires on that tick (5 >= 3), and that we did not have to wait 3 more ticks from zero (proves no reset).
- **Optional:** Same setup but increase override so effective sustain becomes 20; next tick should not fire; after 15 more true ticks (total 20), should fire (proves accumulation continued).

### Concurrency (optional)

- **Objective:** Ensure that swapping the override map reference is safe when the tick thread reads it.
- **Approach:** Use a volatile reference and immutable maps; no lock. Optional test: one thread repeatedly calls `updateSustainOverrides()` (or assigns new maps), another runs a tight loop calling `sustainOverrideProvider()` and reading the map; no exceptions, and reads always see either the old or the new map (no partial/corrupted state). If the project has stress tests, add a short run of this form; otherwise document the volatile + immutable map design as the guarantee.

---

**End of design document.**
