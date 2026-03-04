# CEP sustained_for_sec Override — Audit and Minimal Patch Plan

**Goal:** Override rule `sustained_for_sec` at runtime using values stored in DataRepository, preserving existing behavior when no overrides exist, with minimal invasive changes.

**Status:** Audit and plan only. No code changes until approved.

---

## A) Where cep_rules.json is loaded and sustained_for_sec is consumed

### Load and parse

| Step | Location | Detail |
|------|----------|--------|
| **Load** | `RuleCompiler.loadFromRaw(context, rawResId: Int = R.raw.cep_rules)` | Reads `context.resources.openRawResource(rawResId).bufferedReader().use { it.readText() }` (no disk path; asset from `res/raw/cep_rules.json`). |
| **Parse** | `RuleCompiler.parse(root: JSONObject)` | Calls `parseRules(root.optJSONArray("rules"))` and builds `CepConfig(tickHz, features, rules)`. |
| **Rule model** | `RuleCompiler.parseRules()` → `RuleDef` | Each rule: `sustainedForSec = o.optLong("sustained_for_sec", 0)` (line 167). |
| **Data classes** | `CepConfig.kt` | `CepConfig(tickHz, features, rules)`, `RuleDef(id, description, condition, sustainedForSec, holdDownSec, action, snapshotKeys)`. |

### Engine initialization

| Step | Location | Detail |
|------|----------|--------|
| **Build config** | `CepProvider.buildEngine(context)` | Calls `RuleCompiler.loadFromRaw(context)` → `CepConfig`. |
| **Create engine** | `CepProvider.buildEngine()` | `CepEngine(context, scope, config, yearOfBirth, dataRepo)`. |
| **Create rule engine** | `CepEngine` constructor | `private val ruleEngine = RuleEngine(scope, config, featureStore)`. |

### Where sustained_for_sec is enforced

| Location | Detail |
|----------|--------|
| **RuleEngine.kt** (line 16) | `private val compiledTimers = config.rules.associate { it.id to TriggerTimer(it.sustainedForSec, it.holdDownSec) }`. One `TriggerTimer` per rule, built once with the rule’s `sustainedForSec` and `holdDownSec`. |
| **TriggerTimer.kt** | Constructor takes `sustainedForSec: Long`, `holdDownSec: Long`. `tick(isTrue, now)` uses `sustainedForSec` only: `mature = activeFor >= sustainedForSec`. Internal state: `activeFor`, `lastFireAt`. |
| **RuleEngine.processTick()** | For each rule: `compiledTimers[r.id]?.tick(cond, now)`; if true, builds `TriggerContext` and dispatches. |

**Summary:** Sustain is fixed at engine build time. There is no reload or live lookup of `sustained_for_sec`; changing it requires either rebuilding the timers or making the timer use a per-tick value.

---

## B) Existing “high resting HR” override pattern

### Where the value is stored

| Item | Location | Detail |
|------|----------|--------|
| **Persistence** | `DataRepository` (DataStore) | `context.dataStore` = `preferencesDataStore(name = "data")`. |
| **Keys** | `DataRepository` object (e.g. ~2497–2499) | `RESTING_HR_HIGH_ALERT_BPM_THRESHOLD` = `intPreferencesKey("resting_hr_high_alert_bpm_threshold")`, same pattern for low and length. |
| **Read** | `getRestingHrHighAlertBpmThreshold: Flow<Int>` | `context.dataStore.data.map { prefs -> prefs[RESTING_HR_HIGH_ALERT_BPM_THRESHOLD] ?: DEFAULT_HIGH_RESTING_HR_THRESHOLD }`. |
| **Write** | `storeRestingHrHighAlertBpmThreshold(Int)` | `context.dataStore.edit { prefs -> prefs[RESTING_HR_HIGH_ALERT_BPM_THRESHOLD] = ... }`. |

### How it propagates into CEP

| Step | Location | Detail |
|------|----------|--------|
| **On engine start** | `CepEngine.start()` | Calls `updateHrProfile()`. |
| **Load and push** | `CepEngine.updateHrProfile()` | `scope.launch { ... dataRepository.getRestingHrHighAlertBpmThreshold.first() ... featureStore.put("thresholds.hr.high_bpm", hrRestingHigh) ... }`. So thresholds are read once (per call) and written into **FeatureStore**. |
| **Rule evaluation** | Rules in JSON | Conditions reference derived/feature keys (e.g. `features.hr.high_threshold_bpm`), which are computed from FeatureStore; `features.hr.high_threshold_bpm` is fed from `thresholds.hr.high_bpm`. So CEP sees new thresholds on the **next tick** after `updateHrProfile()` runs. |
| **When settings save** | `SettingsActivity` (e.g. 5432–5446) | After `dataRepository.storeRestingHrHighAlertBpmThreshold(...)`, it calls `CepProvider.getOrCreateEngine(context).updateHrProfile()`. So saving triggers an explicit refresh; no Flow subscription in the engine. |

**Exact functions/classes**

- **DataRepository:** `getRestingHrHighAlertBpmThreshold`, `storeRestingHrHighAlertBpmThreshold`, `getRestingHrLowAlertBpmThreshold`, `storeRestingHrAlertBpmThresholdLength`, etc.
- **CepEngine:** `updateHrProfile()` (reads DataRepository, writes FeatureStore).
- **SettingsActivity:** After storing any of the resting-HR-related settings, calls `CepProvider.getOrCreateEngine(context).updateHrProfile()` so the engine reflects new values without restart.

---

## C) Design options for sustained_for_sec overrides

### Option 1: Apply overrides at rule-load time (preferred only if engine can be refreshed)

- **Idea:** After `RuleCompiler.parse()`, apply overrides from DataRepository to in-memory `RuleDef` list (e.g. replace `sustainedForSec` for matching `rule.id`), then build engine as today.
- **Runtime update:** To reflect new overrides after save, something must re-run “load + apply overrides” and **replace** the rule set or engine. Today the engine is a singleton and `RuleEngine` / `compiledTimers` are built once and never replaced. So Option 1 implies either:
  - Rebuilding the whole CEP engine (drop engine reference, rebuild with new config), or
  - Adding an explicit “reload rules / timers” path that rebuilds `RuleEngine` (or only `compiledTimers`) from an updated config.
- **Minimal changes:** RuleCompiler or CepProvider: after parse, suspend call to DataRepository for override map, apply to `RuleDef` list (copy with modified `sustainedForSec`). CepProvider.buildEngine() would need to be suspend or run in a coroutine to load overrides. When overrides change: call a new `CepEngine.reloadSustainOverrides()` that re-loads overrides, rebuilds config (rules with overrides applied), and **recreates RuleEngine** (and thus all TriggerTimers). That resets all sustain/cooldown state.
- **Risk:** Recreating RuleEngine resets every rule’s `activeFor` and cooldown; rules in progress must re-accumulate sustain time. Medium impact.
- **Runtime update:** Becomes effective only after explicit refresh (e.g. `updateSustainOverrides()` or engine rebuild), same pattern as `updateHrProfile()`.

### Option 2: Rule evaluator consults an override provider each tick (recommended)

- **Idea:** Keep rules and `compiledTimers` as-is for the **default** value. Add a per-tick “effective sustain” that can override it: when evaluating, use `overrideProvider(ruleId) ?: rule.sustainedForSec`, and pass that into the timer.
- **Minimal changes:**
  1. **TriggerTimer:** Add an overload or change signature so the sustain value can be supplied per tick: e.g. `tick(isTrue: Boolean, now: Long, sustainedForSec: Long): Boolean`, using the passed-in `sustainedForSec` for the `mature` check (and keep existing constructor for backward use, or use the parameter only when provided). Prefer a single method `tick(isTrue, now, effectiveSustainForSec)` so the timer is stateless with respect to “which sustain value to use.”
  2. **RuleEngine:** Accept an optional `sustainOverrideProvider: () -> Map<String, Long>` (or `suspend () -> Map<String, Long>`). In `processTick()`, get overrides once per tick; for each rule, `effectiveSustain = overrides[r.id] ?: r.sustainedForSec`, then `compiledTimers[r.id]?.tick(cond, now, effectiveSustain)`.
  3. **CepEngine:** Hold a `@Volatile var sustainOverrides: Map<String, Long> = emptyMap()`, and a new `suspend fun updateSustainOverrides()` that does `sustainOverrides = dataRepository.getCepSustainOverrides().first()`. Pass a provider `{ sustainOverrides }` into `RuleEngine`. Call `updateSustainOverrides()` from `start()` and (from Settings or wherever overrides are saved) after saving overrides.
  4. **DataRepository:** New Flow + store for override map (see D).
- **Risk:** Low. Existing behavior unchanged when overrides map is empty. Only addition is optional provider and one extra parameter on `tick()`.
- **Runtime update:** After save, caller invokes `CepProvider.getOrCreateEngine(context).updateSustainOverrides()`; next tick uses new map. No engine or timer rebuild; no reset of `activeFor` or cooldown.

### Option 3: Rebuild / reinitialize CEP engine when overrides change

- **Idea:** When overrides are saved, clear the cached engine and let the next `getOrCreateEngine()` build a new one with overrides applied at load time (Option 1 style).
- **Minimal changes:** CepProvider: add `fun clearEngine()` or `invalidateEngine()`, and in buildEngine apply overrides (Option 1 load-time apply). After saving overrides, Settings (or equivalent) calls `CepProvider.clearEngine()` so next use gets a new engine. Build engine with overrides applied in CepProvider.buildEngine() (suspend or blocking first() for overrides).
- **Risk:** All CEP state (features, windows, rule timers, alert registry) is reset when engine is recreated. High impact unless the app rarely uses CEP between save and next tick.
- **Runtime update:** Effective after save + engine clear; next `getOrCreateEngine()` builds new engine with new overrides.

**Recommendation:** **Option 2** — override provider per tick, with `TriggerTimer.tick(..., effectiveSustainForSec)` and `updateSustainOverrides()` mirroring `updateHrProfile()`. Smallest diff, no engine or timer rebuild, preserves in-progress sustain and cooldown state.

---

## D) Override data model in DataRepository

- **Semantics:** Override by rule id. Key = rule id string (e.g. `"cold_state_alert"`, `"hr_high_threshold_resting_alert"`). Value = sustained seconds (Int or Long).
- **Persistence:** Match existing pattern: **DataStore** (`context.dataStore`). Use a single **string** preference key with a JSON object (map) for rule id → seconds, same approach as other map-like data in this repo (e.g. SMS list, TAK server list: `stringPreferencesKey` + Gson).
- **Key name:** e.g. `CEP_SUSTAINED_FOR_SEC_OVERRIDES_JSON` = `stringPreferencesKey("cep_sustained_for_sec_overrides_json")`.
- **Value format:** JSON object: `{"rule_id_1": 30, "rule_id_2": 60}`. Parse to `Map<String, Int>` (or `Long`); store with Gson.
- **API:**
  - `getCepSustainOverrides(): Flow<Map<String, Int>>` — map from rule id to override seconds; empty map if missing/invalid.
  - `suspend fun storeCepSustainOverrides(overrides: Map<String, Int>)` — replace full map, or `suspend fun storeCepSustainOverride(ruleId: String, sustainedSec: Int?)` — set one (or remove if null). Single “replace map” is enough for a minimal patch; single-rule set/remove can be added later.
- **Default / fallback:** If key is missing or JSON is invalid, return `emptyMap()`. Engine then uses rule’s own `sustainedForSec` (existing behavior).

---

## E) Edge cases and correctness

| Case | Recommendation |
|------|----------------|
| **Override 0 or negative** | Treat as **immediate fire** (sustain = 0): allow trigger as soon as condition is true (one tick). Clamp to 0 in the engine when applying override: `effectiveSustain = (overrides[r.id] ?: r.sustainedForSec).coerceAtLeast(0)`. |
| **Upper bound (“never triggers”)** | No hard cap required for correctness. If a huge value is stored (e.g. 86400), the rule simply requires that many consecutive true ticks. Optionally cap (e.g. 86400) when storing or when reading to avoid accidental “infinite” sustain. |
| **Thread safety** | CEP tick runs on `scope` (Dispatchers.Default). Overrides read in `processTick()` from a `@Volatile var` or from a provider that returns a snapshot; write to `sustainOverrides` only from the same scope or via a simple volatile/cache. Use a single snapshot per tick (e.g. `val overrides = sustainOverrideProvider()`) so no mid-tick change. |
| **Atomic update** | When calling `updateSustainOverrides()`, do one read from DataRepository and one assign to `sustainOverrides`; no need for locking if the reference is volatile and the map is immutable (e.g. copy from repository). |
| **Rule exit / monitor timers** | Overrides apply only to **entry** sustain (`sustained_for_sec`), not to `exit_sustain_sec` or monitor logic. Exit/monitor timers live in AlertRegistry and `checkAndClearAlerts()`; no change there. Overrides don’t touch `hold_down_sec`. |

---

## F) Deliverables

### Dependency map (load → parse → rule objects → sustain eval → action)

```
res/raw/cep_rules.json
    → RuleCompiler.loadFromRaw(context)
    → RuleCompiler.parse(JSON)
    → parseRules() → List<RuleDef>  [sustainedForSec from JSON]
    → CepConfig(tickHz, features, rules)
    → CepProvider.buildEngine() → CepEngine(..., config, dataRepo)
    → CepEngine: RuleEngine(scope, config, featureStore)
    → RuleEngine: compiledTimers = rules.associate { id -> TriggerTimer(sustainedForSec, holdDownSec) }
    → each tick: CepEngine tick loop
        → ruleEngine.processTick()
        → for each rule: JsonLogic.eval(condition) → cond
        → compiledTimers[rule.id].tick(cond, now)  [uses sustainedForSec for mature]
        → if fired → TriggerContext → dispatcher.dispatch() → handlers → AlertRegistry / CoT
```

### Minimal patch plan (recommended: Option 2)

1. **DataRepository**
   - Add `stringPreferencesKey("cep_sustained_for_sec_overrides_json")`.
   - Add `getCepSustainOverrides(): Flow<Map<String, Int>>` (parse JSON; invalid/missing → emptyMap()).
   - Add `suspend storeCepSustainOverrides(overrides: Map<String, Int>)` (Gson to JSON, write to DataStore).

2. **TriggerTimer**
   - Add `tick(isTrue: Boolean, now: Long, sustainedForSec: Long): Boolean` that uses the passed-in `sustainedForSec` for the mature check (and keep `holdDownSec` from constructor). Deprecate or remove the two-arg `tick` only if all call sites are updated; otherwise overload.

3. **RuleEngine**
   - Add constructor parameter: `sustainOverrideProvider: () -> Map<String, Long>` (default = `{ emptyMap() }`).
   - In `processTick()`: get `overrides = sustainOverrideProvider()`, then for each rule `effectiveSustain = (overrides[r.id] ?: r.sustainedForSec).coerceAtLeast(0)`; call `compiledTimers[r.id]?.tick(cond, now, effectiveSustain)`.

4. **CepEngine**
   - Add `@Volatile private var sustainOverrides: Map<String, Long> = emptyMap()`.
   - Add `suspend fun updateSustainOverrides()`: `sustainOverrides = dataRepository.getCepSustainOverrides().first().mapValues { it.value.toLong() }` (or store Long in repo).
   - In `start()`, call `updateSustainOverrides()` (in the same scope.launch as updateHrProfile or a separate one).
   - Construct RuleEngine with `sustainOverrideProvider = { sustainOverrides }`.

5. **Call site when overrides are saved**
   - Wherever CEP sustain overrides are persisted (e.g. Settings or a future UI), after `dataRepository.storeCepSustainOverrides(...)`, call `CepProvider.getOrCreateEngine(context).updateSustainOverrides()` so the engine sees new values on next tick.

6. **Edge handling**
   - When applying override: use `.coerceAtLeast(0)` so 0 or negative means “immediate” (one tick). Optionally validate/cap when storing (e.g. max 86400) in DataRepository or UI.

**Files to touch (minimal):**

- `DataRepository.kt` — keys, Flow, store, Gson parse/serialize.
- `TriggerTimer.kt` — add `tick(..., sustainedForSec: Long)`.
- `RuleEngine.kt` — provider parameter, use effective sustain in processTick.
- `CepEngine.kt` — sustainOverrides, updateSustainOverrides(), pass provider to RuleEngine, call updateSustainOverrides in start().
- Call site (e.g. Settings or a dedicated CEP-settings path) — after saving overrides, call `updateSustainOverrides()`.

No changes to `cep_rules.json`, RuleCompiler, or CepProvider (other than ensuring CepEngine is built with the new RuleEngine signature). No editing of JSON on disk at runtime.

---

**End of audit and plan.** Implement only after approval.
