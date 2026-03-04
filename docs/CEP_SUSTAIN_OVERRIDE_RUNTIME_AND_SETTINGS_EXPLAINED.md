# CEP Sustain Override — Runtime Path and Settings Mapping (Explain Only)

This document explains how dynamic `sustained_for_sec` overrides work for the four rules that have `"allow_sustain_override": true`, and how to drive those overrides from the two existing Settings inputs (resting-heart-rate threshold length and exertion threshold length). No code changes or diffs—explanation only.

---

## A) Trace the runtime path (end-to-end)

### 1. Where overrides are stored in DataRepository

- **Location:** `DataRepository` (same process as the rest of app preferences).
- **Backing store:** DataStore, single string preference key `cep_sustain_overrides_json` (constant `CEP_SUSTAINED_FOR_SEC_OVERRIDES_JSON`).
- **Format:** JSON object: keys = rule IDs (strings), values = sustain duration in **seconds** (numbers). Example: `{"hr_high_threshold_resting_alert": 30, "exertion_warning_hr_zone4_exercise": 120}`. Only entries that are actual overrides need to be present (sparse map).
- **Read API:** `getCepSustainOverrides: Flow<Map<String, Long>>` — maps the raw preference through `parseCepSustainOverrides(json)`, which returns `emptyMap()` when the key is missing or JSON is invalid, and clamps each value to `coerceAtLeast(0L)`.
- **Write API:** `suspend fun storeCepSustainOverrides(map: Map<String, Long>)` — replaces the stored JSON with the serialized map (values sanitized to ≥ 0 before write). DataRepository does **not** filter by overrideable rule IDs; it stores whatever map the caller provides.

So at runtime, “the overrides” are whatever map is currently in that DataStore key, then later filtered by the engine.

---

### 2. How CepEngine.updateSustainOverrides() loads them

- **Who calls it:** (1) `CepEngine.start()` via `scope.launch { updateSustainOverrides() }`, and (2) after a Settings save, the UI/call-site must call `CepProvider.getOrCreateEngine(context).updateSustainOverrides()` (e.g. from a coroutine).
- **What it does (conceptually):**
  1. `loaded = dataRepository.getCepSustainOverrides().first()` — one-shot read of the current map from DataRepository (so from DataStore).
  2. `overrideableIds = config.rules.filter { it.allowSustainOverride }.map { it.id }.toSet()` — the **gate** is applied here at load time: only rule IDs that have `allowSustainOverride == true` in the compiled config are considered. With your current JSON, that set is exactly the four IDs: `hr_high_threshold_resting_alert`, `hr_low_threshold_resting_alert`, `exertion_warning_hr_zone4_exercise`, `exertion_alert_hr_zone5_exercise`.
  3. `sustainOverrides = loaded.filterKeys { it in overrideableIds }.mapValues { (_, v) -> v.coerceAtLeast(0L) }` — keep only entries whose key is in that set; clamp values to ≥ 0. Any key in storage that is **not** in `overrideableIds` (e.g. a typo or an old rule id) is dropped and never used.
  4. Assign result to `@Volatile private var sustainOverrides` so the next tick sees it.

So the **first place the gate is applied** is in `CepEngine.updateSustainOverrides()`: only overrideable rule IDs from the config ever make it into `sustainOverrides`. The engine never uses stored overrides for rules that are not marked `allowSustainOverride` in `cep_rules.json`.

---

### 3. How RuleEngine.processTick() computes effectiveSustainSec

- **Invocation:** Each CEP tick, `CepEngine`’s tick loop calls `ruleEngine.processTick(now)` (with default `now = System.currentTimeMillis()`).
- **Snapshot:** At the start of `processTick`, the engine does `overrides = sustainOverrideProvider()`. The provider is the lambda `{ sustainOverrides }` passed from `CepEngine`, so `overrides` is the current in-memory map (already filtered to overrideable rule IDs and clamped). One snapshot per tick keeps behavior consistent for the whole tick.
- **Per rule:** For each rule `r` in `config.rules`:
  - **Gate check (second place):**  
    `eff = if (r.allowSustainOverride) { overrides[r.id]?.coerceAtLeast(0L) ?: base } else { base }`  
    where `base = r.sustainedForSec` (from JSON).
  - **Semantics:**
    - If `r.allowSustainOverride` is **false** → `eff = base` always. Stored overrides are ignored for this rule (and they were already filtered out of `sustainOverrides` for non-overrideable IDs, but even if one slipped through, the gate here forces use of JSON value).
    - If `r.allowSustainOverride` is **true** → use override when present: `overrides[r.id]?.coerceAtLeast(0L) ?: base`; if there is no override for this rule id, fall back to `base`.
  - Then the engine calls `compiledTimers[r.id]?.tick(cond, now, eff)`.

So **effectiveSustainSec** is always in seconds (1 Hz tick ⇒ `activeFor` counts seconds). For the four rules you marked overrideable, `eff` is either the value from the override map (when present) or the rule’s `sustained_for_sec` from JSON.

---

### 4. How TriggerTimer.tick(isTrue, now, effectiveSustainSec) uses it to mature an alert

- **Class:** `TriggerTimer` (one instance per rule). It holds only `holdDownSec` (cooldown) and mutable state: `activeFor` (consecutive ticks the condition has been true), `lastFireAt` (last fire time for cooldown). It does **not** store sustain; sustain is supplied each tick.
- **Each tick:**  
  `tick(isTrue, now, effectiveSustainSec)`:
  - If `isTrue` then `activeFor++`, else `activeFor = 0`.
  - `mature = activeFor >= effectiveSustainSec` — so the **passed-in** `effectiveSustainSec` (in seconds) is the threshold for maturity.
  - Cooldown and fire logic unchanged: `fire = mature && cooldown`, and if fire then `lastFireAt = now`.
- So for the four rules, the value coming from Settings (via DataRepository → updateSustainOverrides → sustainOverrides → provider → eff) is exactly what decides “how many seconds true” are required before the rule can fire. Timer state (`activeFor`, `lastFireAt`) is **not** reset when overrides change; only the threshold used for the maturity comparison changes on the next tick.

End-to-end chain: **DataStore (JSON) → DataRepository.getCepSustainOverrides → CepEngine.updateSustainOverrides (filter to overrideable IDs) → sustainOverrides → RuleEngine.processTick (snapshot + gate per rule → eff) → TriggerTimer.tick(..., eff) → maturity.**

---

## B) How Settings should map to sustain overrides for these four rules

You have two existing Settings “families” that already update CEP thresholds (via DataRepository + FeatureStore, like `updateHrProfile()`):

1. **Resting heart rate (threshold length)** — in code: `restingHrAlertBpmThresholdLength` (and related resting-HR BPM thresholds). This is the “resting heart rate threshold” side (time dimension: how long resting HR must be beyond threshold before alerting).
2. **Exertion (threshold and length)** — in code: `exertionThresholdWarning`, `exertionThresholdAlert`, `exertionThresholdLength`. This is the “exertion threshold” side (level and time).

The four overrideable rules split by semantics:

- **Resting-HR–related:**  
  - `hr_high_threshold_resting_alert` — high resting HR.  
  - `hr_low_threshold_resting_alert` — low resting HR / brady (resting context).  
  Both are about **resting** heart rate, so their **sustain** (time-to-trigger) should be driven by the same “how long” setting that already affects resting-HR behavior: **resting heart rate threshold length** (e.g. `restingHrAlertBpmThresholdLength`). That value is currently in minutes in the UI; for CEP sustain we need seconds, so convert (e.g. `minutes * 60L`) when building the override map.

- **Exertion-related:**  
  - `exertion_warning_hr_zone4_exercise` — zone 4 during exercise.  
  - `exertion_alert_hr_zone5_exercise` — zone 5 during exercise.  
  Both are about **exercise / exertion** zones, so their **sustain** should be driven by the exertion time setting: **exertion threshold length** (e.g. `exertionThresholdLength`), which is already in seconds. Use it directly (or the same value for both zone-4 and zone-5, or split if you later add separate logic).

**Recommended mapping (engineering approach):**

- **restingHeartrateThreshold (length)** → sustain for:
  - `hr_high_threshold_resting_alert`
  - `hr_low_threshold_resting_alert`  
  Use the same sustain value for both (e.g. `restingHrAlertBpmThresholdLength` converted to seconds). So one setting value → two rule IDs in the map.

- **exertionThreshold (length)** → sustain for:
  - `exertion_warning_hr_zone4_exercise`
  - `exertion_alert_hr_zone5_exercise`  
  Use the same sustain value for both (e.g. `exertionThresholdLength` in seconds). So one setting value → two rule IDs in the map.

**Producing Map&lt;ruleId, sustainSec&gt;:**

- **Where to compute:** In the **Settings layer** (the same place that already has the current values of `restingHrAlertBpmThresholdLength` and `exertionThresholdLength` when the user saves). That code already calls `dataRepository.storeRestingHrAlertBpmThresholdLength(...)` and `dataRepository.storeExertionThresholdLength(...)` and then `updateHrProfile()`. So it has the two numeric values; it can build a map and call `storeCepSustainOverrides(map)`.
- **Logic (pseudo-code):**
  - `restingSec = restingHrAlertBpmThresholdLength * 60L`  // minutes → seconds (if that’s the unit in the setting).
  - `exertionSec = exertionThresholdLength.toLong()`       // already seconds.
  - `map = mapOf(
      "hr_high_threshold_resting_alert" to restingSec,
      "hr_low_threshold_resting_alert" to restingSec,
      "exertion_warning_hr_zone4_exercise" to exertionSec,
      "exertion_alert_hr_zone5_exercise" to exertionSec
    )`
  - Then `dataRepository.storeCepSustainOverrides(map)` and `getOrCreateEngine(context).updateSustainOverrides()`.

- **Why not DataRepository or CepEngine:** DataRepository should stay generic (store/retrieve a map by key); it doesn’t know about rule IDs or “resting vs exertion.” CepEngine only consumes the map and filters by overrideable IDs; it shouldn’t invent map entries from other settings. So the **mapping from two Settings to four rule IDs** belongs in the Settings/UI layer that already has those two values at save time.

---

## C) Where exactly to “write” the sustain override map

**After a Settings save you must:**

1. Call `dataRepository.storeCepSustainOverrides(map)` (with the map built from the two Settings as above).
2. Call `CepProvider.getOrCreateEngine(context).updateSustainOverrides()` so the engine’s in-memory `sustainOverrides` is refreshed. Because `updateSustainOverrides()` is `suspend`, this must run inside a coroutine (e.g. `lifecycleScope.launch { ... }` or the same scope used for other DataRepository writes in that screen).

**Exact call site:**

- The **existing Settings save handler** that already persists resting-HR and exertion settings and then calls `updateHrProfile()` is in **`SettingsActivity`**, in the same block that:
  - Detects changes like `restingHeartRateThresholdLengthChanged`, `exertionThresholdLengthChanged`, etc.
  - Calls `dataRepository.storeRestingHrAlertBpmThresholdLength(restingHrAlertBpmThresholdLength)`, `dataRepository.storeExertionThresholdLength(exertionThresholdLength)`, and the other store methods.
  - Calls `CepProvider.getOrCreateEngine(context).updateHrProfile()` after some of those stores (e.g. after resting high/low threshold or threshold length changes).

That block is the right place to extend: **right after** you persist the two Settings that drive sustain (e.g. after `storeRestingHrAlertBpmThresholdLength` and `storeExertionThresholdLength`), or in a single block that runs when “any of the settings that affect CEP thresholds or sustain” have changed:

1. Build the four-entry map from `restingHrAlertBpmThresholdLength` and `exertionThresholdLength` (with unit conversion for resting if needed).
2. Call `dataRepository.storeCepSustainOverrides(map)` (suspend).
3. Call `CepProvider.getOrCreateEngine(context).updateSustainOverrides()` (suspend).

Both calls should run in the same coroutine context as the other DataRepository suspend calls in that save path (e.g. the same `launch` or `withContext` block that already performs the other stores and then `updateHrProfile()`). So conceptually: **extend the existing Settings save block in SettingsActivity** that handles `restingHeartRateThresholdLengthChanged` and `exertionThresholdLengthChanged` (and possibly the other resting/exertion-related changes) by (1) computing the sustain map from the two length values, (2) storing it, and (3) calling `updateSustainOverrides()` in that same flow. There is no separate ViewModel or repository method that “handles” sustain today; the same handler that writes resting HR and exertion settings is the one that should also write the sustain override map and then tell the engine to reload it.

---

## D) Verify behavior expectations

**1. Override exists for these rules → takes effect next tick**

- After you call `storeCepSustainOverrides(map)` and then `updateSustainOverrides()`, the engine assigns the filtered map to `sustainOverrides`. On the **next** tick, `RuleEngine.processTick()` does `overrides = sustainOverrideProvider()` → gets that map. For each of the four rules, `r.allowSustainOverride` is true, so `eff = overrides[r.id]?.coerceAtLeast(0L) ?: base` uses the new value. `TriggerTimer.tick(..., eff)` then uses that for `mature = activeFor >= effectiveSustainSec`. So the new sustain is in effect on the very next CEP tick; no engine rebuild, no app restart.

**2. Override removed → fallback to JSON sustain value**

- If you remove an entry from the stored map (or clear the whole map), the next `updateSustainOverrides()` loads the new map; for that rule id, `overrides[r.id]` is null. So `eff = overrides[r.id]?.coerceAtLeast(0L) ?: base` becomes `base`, i.e. the rule’s `sustained_for_sec` from JSON. Behavior reverts to the static value.

**3. Other rules (not overrideable) are unaffected even if present in stored overrides**

- **At load time:** `CepEngine.updateSustainOverrides()` keeps only keys in `overrideableIds` (`config.rules.filter { it.allowSustainOverride }.map { it.id }.toSet() }`). So any other rule id in the stored map is dropped and never enters `sustainOverrides`.
- **At tick time:** For a rule with `allowSustainOverride == false`, `RuleEngine` always uses `eff = base` (JSON value). So even if the stored map somehow contained that id (e.g. before filtering was added), the gate in `processTick` would still force use of the JSON sustain. So non-overrideable rules are unaffected.

**4. Changing sustain mid-accumulation (timer state not reset)**

- `TriggerTimer` does not store sustain; it only receives `effectiveSustainSec` each tick. So when the override map is updated, `activeFor` and `lastFireAt` are **not** reset. On the next tick, the same `activeFor` is compared to the **new** `effectiveSustainSec`. So:
  - If the new sustain is **lower** than before, the rule may fire immediately (e.g. was 60 s, now 30 s, and `activeFor` is already 40).
  - If the new sustain is **higher**, the rule simply needs more consecutive true ticks from the current `activeFor` (e.g. was 10 s, now 60 s, and `activeFor` is 15 → not mature until `activeFor` reaches 60). No reset; accumulation continues.

This matches the design goal: override changes apply immediately for the next maturity check, without resetting timer state.
