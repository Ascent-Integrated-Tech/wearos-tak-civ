# Sample-Count Window CEP Extension

**Date:** 2026-04-17
**Status:** Implemented — Phase 6
**Scope:** Generic sample-count rolling windows across the full CEP pipeline; first consumer is `PressureService`.

---

## 1. Why This Existed Before CEP

`PressureService` historically ran its own ad-hoc detection logic completely outside the CEP:

```
SensorManager ──> onSensorChanged() ──> DataRepository.pressureHistory (List<PressureReading>, capped at 20)
                                    ──> isDunkDetected(history)          // index arithmetic
                                    ──> isShallowDunkDetected(history)   // index arithmetic
                                    ──> handleSubmergeDetection(history) // baseline + duration timer
                                    ──> generateLowHighPressureAlerts()  // takeLast(5).average()
                                    ──> DataRepository.addPhysioAndAutoGenAlertRequest()
```

Every one of these detections was **sample-count based**, not time-based. `CEP` never saw the data. The consequence was that pressure alerts could not be corroborated with other sensors, did not benefit from CEP hold-down / sustained-for / exit-monitor logic, and had no simulation harness.

The design goal for Phase 6 was to port all of this into the CEP without hard-coding pressure-specific logic into the pipeline.

---

## 2. Architecture Overview

The full CEP pipeline after Phase 6:

```
PressureService.onSensorChanged()
        │
        └──[CepBus.tryEmit(CepEvent<PressurePayload>)]
                │
                ▼
        WindowStore.ingest()
          ├── Writes hpa → num["pressure"] (TimedDoubleRing)
          └── Writes hpa, pressure.accuracy → FeatureStore
                │
                ▼
        Per-feature compute coroutines (CepEngine.start())
          ├── computeSampleWindow()  →  features.pressure.mean_n5,  mean_n20
          ├── computeSampleDelta()   →  features.pressure.delta_n5
          ├── computeSampleSlope()   →  features.pressure.slope_n20
          └── DerivedEval.eval()     →  delta_rapid_rise, delta_shallow_rise,
                                        above_high_threshold, below_low_threshold,
                                        submerge_rise
                │
                ▼
        RuleEngine.processTick()   (every rule_eval_interval_ms = 500 ms)
          └── Evaluates each rule's `when` condition against FeatureStore
                │
                ▼
        TriggerTimer (per rule)
          └── Maturation: sustained_for_sec must elapse continuously
                │
                ▼
        CepActionDispatcher → LogActionHandler
          └── AlertRegistry.tryActivate()  [isTak=true → visible to simulation polling]
```

---

## 3. Changes by Component

### 3.1 `cep/util/Rings.kt` — `TimedDoubleRing`

**Problem:** `size` was `private var`, blocking `WindowStore` from guard-checking how many samples are in the ring.

**Fix:**
```kotlin
// Before
private var size = 0

// After
var size = 0
    private set
```

**New ring primitives used by sample-count features:**

| Method | Behaviour |
|---|---|
| `tailByCount(n)` | Returns the `n` most recent values as `List<Double>` |
| `tailByCountPairs(n)` | Returns `List<Pair<Long, Double>>` with timestamps — used by `SampleSlope` for regression |
| `getByCount(k)` | Returns the value `k` positions back from newest (0 = newest); null if fewer than `k+1` samples |
| `lastTsOrNull()` | Returns the timestamp of the most recent sample, or null if ring is empty |

---

### 3.2 `cep/engine/CepConfig.kt` — Three New `FeatureDef` Arms

```kotlin
// Sample-count aggregate (avg, min, max, rms, median, stddev)
data class SampleWindow(
    override val id: String,
    val source: String,
    val op: String,           // "avg" | "min" | "max" | "rms" | "median" | "stddev"
    val sampleCount: Int,     // how many samples to aggregate
    val minSamples: Int = 1,  // minimum required; suppressed if ring has fewer
    val maxAgeSec: Long? = null,
    override val updateIntervalMs: Long = 1000L
) : FeatureDef

// (newest sample) − (sample horizonSamples positions ago)
data class SampleDelta(
    override val id: String,
    val source: String,
    val horizonSamples: Int,  // needs horizonSamples+1 entries in ring; null → suppressed
    val maxAgeSec: Long? = null,
    override val updateIntervalMs: Long = 1000L
) : FeatureDef

// Least-squares slope over sampleCount recent samples (units/second or units/minute)
data class SampleSlope(
    override val id: String,
    val source: String,
    val sampleCount: Int,
    val unitsPerSec: String = "per_second",   // "per_second" | "per_minute"
    val maxAgeSec: Long? = null,
    override val updateIntervalMs: Long = 1000L
) : FeatureDef
```

---

### 3.3 `cep/engine/WindowStore.kt` — Three New Compute Methods

#### `computeSampleWindow(def, fs)`

1. Looks up `num[def.source]` ring — returns early if absent.
2. Runs freshness guard: if `(now − ring.lastTsOrNull()) / 1000 > maxAgeSec`, suppresses.
3. Calls `ring.tailByCount(def.sampleCount)` → `window: List<Double>`.
4. If `window.size < def.minSamples`, suppresses.
5. Applies `def.op` (avg / min / max / rms / median / stddev) → `v: Double?`.
6. Calls `fs.putDerived(def.id, v, tsMs)`.

#### `computeSampleDelta(def, fs)`

1. Freshness guard (same as above).
2. `now  = ring.getByCount(0)`  — newest sample.
3. `back = ring.getByCount(def.horizonSamples)` — sample N positions ago.
4. Either value null → suppresses (not enough history).
5. `delta = now − back` → `fs.putDerived(def.id, delta, tsMs)`.

#### `computeSampleSlope(def, fs)`

1. Freshness guard.
2. `pairs = ring.tailByCountPairs(def.sampleCount)` — `List<Pair<Long, Double>>`.
3. If `pairs.size < 2` or timestamp span `< MIN_SLOPE_SPAN_MS (10 ms)` → suppresses (guard against ÷0).
4. Least-squares linear regression over real sensor timestamps (x-axis in seconds).
5. If `def.unitsPerSec == "per_minute"` multiplies slope × 60.
6. `fs.putDerived(def.id, slope, tsMs)`.

**Additional helper added for tests:**
```kotlin
fun clearRing(topic: String) { num.remove(topic) }
```

---

### 3.4 `cep/engine/FeatureStore.kt` — Boolean Coercion

**Problem:** `DerivedEval.evalBoolean()` stores derived boolean results as Kotlin `Boolean`. The `gte_key` operator calls `fs.double()`, which previously only handled `Number`. `Boolean` values were not `Number` on the JVM, so `double()` returned `null` and every boolean-gated rule always evaluated false.

**Fix — added `is Boolean` arm in two places:**

```kotlin
fun double(key: String): Double? = when (val v = kv[resolve(key)]) {
    is Number  -> v.toDouble()
    is Boolean -> if (v) 1.0 else 0.0   // ← NEW: derived bool → numeric for gte_key/lte_key
    else       -> null
}

fun latestAsDouble(id: String): Double? = when (val v = kv[id]) {
    is Number  -> v.toDouble()
    is Boolean -> if (v) 1.0 else 0.0   // ← NEW: same coercion for exit-condition checks
    else       -> null
}
```

`fs.bool()` is unchanged — it still returns `Boolean` for `{ "ref": "...", "is": true }` rules. Both representations coexist in the store without conflict.

---

### 3.5 `cep/engine/CepEngine.kt` — Pressure Profile + Enable Flags

#### `updatePressureProfile()` — extended

Now loads six values from `DataRepository` instead of two:

```kotlin
// Thresholds (existing)
featureStore.put("thresholds.pressure.high", highHpa.toDouble())
featureStore.put("thresholds.pressure.low",  lowHpa.toDouble())

// Enable flags (NEW)
featureStore.put("settings.pressure.immersion_alerts_enabled", if (immersionOn) 1.0 else 0.0)
featureStore.put("settings.pressure.low_atm_alert_enabled",    if (lowAtmOn)    1.0 else 0.0)
featureStore.put("settings.pressure.high_atm_alert_enabled",   if (highAtmOn)   1.0 else 0.0)
```

Rules gate on these with `{ "gte_key": ["settings.pressure.immersion_alerts_enabled", 1.0] }`. When the user disables an alert category in Settings, the flag writes `0.0` and the rule's `all` condition becomes false immediately.

#### Test helpers

```kotlin
// Synchronous threshold injection — bypasses async DataRepository for simulation tests
fun injectPressureThresholdsForTest(lowHpa: Double, highHpa: Double)

// Synchronous flag injection — all P6 tests call with all=true so gate never suppresses assertions
fun injectPressureAlertFlagsForTest(immersion: Boolean = true, lowAtm: Boolean = true, highAtm: Boolean = true)

// Clears ring + all derived feature keys between scenarios
// Does NOT clear settings.pressure.* so injected flags survive the reset
fun clearPressureStateForTest()

// Snapshot for FAIL diagnostics
fun pressureFeatureSnapshot(): Map<String, Any?>
```

---

### 3.6 `service/PressureService.kt` — Production Data Path Enabled

The `CepBus.emit()` block was commented out before Phase 6. It is now active:

```kotlin
// Previously commented out — now enabled:
val cepEvt = CepEvent(
    topic    = Topics.PRESSURE,
    payload  = PressurePayload(hpa = reading.pressure, accuracy = accuracyVal,
                               sampleIndex = sampleIndex++, sourceId = "baro_0"),
    ts       = reading.timestamp
)
if (!CepBus.tryEmit(cepEvt)) {
    serviceScope.launch { CepBus.emit(cepEvt) }
}
```

`tryEmit` is non-blocking; the `launch` fallback handles the rare case where the shared-flow replay buffer is full. Both the legacy `DataRepository.pressureHistory` path (used by the Settings UI) and the new CEP path now run on every sensor callback.

---

## 4. CEP Rules JSON — Feature and Rule Schema

### 4.1 New JSON Feature Types

#### `sample_window`
```json
{
  "id": "features.pressure.mean_n5",
  "type": "sample_window",
  "source": "pressure",
  "op": "avg",
  "sample_count": 5,
  "min_samples": 5,
  "max_age_sec": 30,
  "update_interval_ms": 500
}
```

| Field | Type | Description |
|---|---|---|
| `type` | string | `"sample_window"` |
| `source` | string | CepBus topic / ring key (e.g. `"pressure"`) |
| `op` | string | `avg` \| `min` \| `max` \| `rms` \| `median` \| `stddev` |
| `sample_count` | int | Number of most-recent samples to aggregate |
| `min_samples` | int | Minimum ring entries required; feature suppressed if fewer |
| `max_age_sec` | int? | Freshness guard on newest sample; feature suppressed if stale |
| `update_interval_ms` | int | Compute cadence |

#### `sample_delta`
```json
{
  "id": "features.pressure.delta_n5",
  "type": "sample_delta",
  "source": "pressure",
  "horizon_samples": 5,
  "max_age_sec": 30,
  "update_interval_ms": 500
}
```

| Field | Type | Description |
|---|---|---|
| `horizon_samples` | int | Lookback distance; needs `horizon_samples + 1` ring entries |

Computes: `ring[newest] − ring[newest − horizon_samples]`. Suppressed if fewer than `horizon_samples + 1` entries exist.

#### `sample_slope`
```json
{
  "id": "features.pressure.slope_n20",
  "type": "sample_slope",
  "source": "pressure",
  "sample_count": 20,
  "units_per_sec": "per_second",
  "max_age_sec": 120,
  "update_interval_ms": 1000
}
```

| Field | Type | Description |
|---|---|---|
| `sample_count` | int | Number of samples in regression |
| `units_per_sec` | string | `"per_second"` or `"per_minute"` |

Computed via least-squares linear regression using real sensor timestamps as the x-axis. Suppressed if fewer than 2 samples or timestamp span < 10 ms.

---

### 4.2 Enable Flag Gating Pattern

All pressure rules use `all` to combine the user-preference gate with the detection condition:

```json
{
  "id": "pressure_dunk_detected",
  "when": {
    "all": [
      { "gte_key": ["settings.pressure.immersion_alerts_enabled", 1.0] },
      { "gte_key": ["features.pressure.delta_rapid_rise", 1.0] }
    ]
  },
  ...
}
```

The three flag keys and their governing rules:

| FeatureStore key | Rules gated |
|---|---|
| `settings.pressure.immersion_alerts_enabled` | `pressure_dunk_detected`, `pressure_shallow_dunk`, `pressure_submerge_detected` |
| `settings.pressure.low_atm_alert_enabled` | `pressure_low_atm` |
| `settings.pressure.high_atm_alert_enabled` | `pressure_high_atm` |

---

## 5. End-to-End Worked Example: Dunk Detection

This traces a single dunk event through every stage from the hardware sensor to the fired alert.

### Scenario

The watch is dunked in water. The barometer rises from ~1013 hPa (ambient) to ~1025 hPa across five consecutive sensor readings. The user has immersion alerts enabled in Settings.

---

### Stage 1 — Sensor callback

`PressureService.sensorEventListener.onSensorChanged()` fires at `SENSOR_DELAY_NORMAL` (~5 Hz):

```kotlin
val reading = PressureReading(pressure = 1025.0f, timestamp = now)
dataRepository.storeLatestPressure(reading)   // legacy UI path

val cepEvt = CepEvent(
    topic   = Topics.PRESSURE,   // = "pressure"
    payload = PressurePayload(hpa = 1025.0f, accuracy = 3, sampleIndex = N, sourceId = "baro_0"),
    ts      = now
)
CepBus.tryEmit(cepEvt)
```

---

### Stage 2 — `WindowStore.ingest()`

The `CepEngine` coroutine collecting `CepBus.events` receives the event and calls `windows.ingest(evt, featureStore, evt.ts)`:

```kotlin
is PressurePayload -> {
    val ring = num.getOrPut("pressure") { TimedDoubleRing(numCap()) }
    ring.add(1025.0, tsMs)                  // appended to ring, head advances
    fs.put("pressure", 1025.0, tsMs)        // latest raw value in FeatureStore
    fs.put("pressure.accuracy", 3.0, tsMs)
}
```

After 6 cumulative readings the ring looks like (oldest → newest):
```
[1013.1, 1014.5, 1018.2, 1021.7, 1023.8, 1025.0]   (ring.size = 6)
```

---

### Stage 3 — `computeSampleDelta()` fires for `features.pressure.delta_n5`

Feature definition in `cep_rules.json`:
```json
{ "id": "features.pressure.delta_n5", "type": "sample_delta", "source": "pressure",
  "horizon_samples": 5, "max_age_sec": 30, "update_interval_ms": 500 }
```

Compute coroutine runs every 500 ms:
```
now  = ring.getByCount(0)  = 1025.0   (newest)
back = ring.getByCount(5)  = 1013.1   (5 positions older)
delta = 1025.0 − 1013.1 = 11.9 hPa
→ fs.putDerived("features.pressure.delta_n5", 11.9, tsMs)
```

---

### Stage 4 — `DerivedEval` for `features.pressure.delta_rapid_rise`

Feature definition:
```json
{ "id": "features.pressure.delta_rapid_rise", "type": "derived",
  "expr": { "gt": [ { "key": "features.pressure.delta_n5" }, 5.0 ] } }
```

`DerivedEval.eval()` calls `fs.double("features.pressure.delta_n5")`:
- `kv["features.pressure.delta_n5"]` = `11.9` (Number)
- `11.9 > 5.0` → `true`
- `fs.putDerived("features.pressure.delta_rapid_rise", true, tsMs)`

---

### Stage 5 — `RuleEngine.processTick()` evaluates `pressure_dunk_detected`

Rule definition:
```json
{
  "id": "pressure_dunk_detected",
  "when": {
    "all": [
      { "gte_key": ["settings.pressure.immersion_alerts_enabled", 1.0] },
      { "gte_key": ["features.pressure.delta_rapid_rise", 1.0] }
    ]
  },
  "sustained_for_sec": 2,
  "hold_down_sec": 5
}
```

`RuleCompiler` has compiled `when` into a `JsonLogicEvaluator` that calls:

1. `fs.double("settings.pressure.immersion_alerts_enabled")`:
   - `updatePressureProfile()` wrote `1.0` when the engine started (user has it enabled)
   - `1.0 >= 1.0` → **true**

2. `fs.double("features.pressure.delta_rapid_rise")`:
   - `kv[...]` = `true` (Boolean, stored by DerivedEval)
   - `is Boolean -> if (v) 1.0 else 0.0` → **1.0**
   - `1.0 >= 1.0` → **true**

`all` → condition = **true**

---

### Stage 6 — `TriggerTimer` — `sustained_for_sec: 2`

`RuleEngine` calls `compiledTimers["pressure_dunk_detected"].advance(conditionTrue = true, now)`.

- Tick 1 (t=0 s): timer starts, `elapsedSec = 0.0` — below threshold
- Tick 2 (t=0.5 s): `elapsedSec = 0.5` — below threshold
- Tick 3 (t=1.0 s): `elapsedSec = 1.0` — below threshold
- Tick 4 (t=1.5 s): `elapsedSec = 1.5` — below threshold
- Tick 5 (t=2.0 s): `elapsedSec = 2.0` ≥ `sustained_for_sec = 2` → **fires**

Returns `RuleResult(mainFired = true)`.

---

### Stage 7 — `CepActionDispatcher` → `LogActionHandler`

Dispatcher routes `action.type = "log"` to `LogActionHandler.handle(ctx)`.

```kotlin
if (ctx.action.isTak) {           // true
    val uid = UUID.randomUUID()
    dataRepository.addPhysioAndAutoGenAlertRequest(uid, "CAT_IMMERSION_WARN", ...)
    AlertRegistry.tryActivate(Alert(ruleId = "pressure_dunk_detected", uid = uid, ...))
}
```

`AlertRegistry` now contains the alert. The `hold_down_sec: 5` timer starts — the rule will not re-fire for 5 seconds even if the condition stays true.

---

### Stage 8 — Simulation polling / UI

`CEPSimulationService` polls `AlertRegistry.allActive().any { it.ruleId == "pressure_dunk_detected" }` and records `fireTime`. The simulation asserts:
```
elapsed in 2_000L..5_000L ms   (sustained=2s, plus feature compute latency)
```

On a real device, the alert is visible in the physio alert list and dispatched to connected TAK servers because `isTak = true`.

---

## 6. All Five Pressure Features and Rules — Quick Reference

### Features (from `cep_rules.json`)

| Feature id | Type | Description |
|---|---|---|
| `features.pressure.mean_n5` | `sample_window` | avg of last 5 samples; min_samples=5 |
| `features.pressure.mean_n20` | `sample_window` | avg of last 20 samples; pre-rise rolling baseline for submerge |
| `features.pressure.delta_n5` | `sample_delta` | newest − sample-5-ago; needs ≥6 ring entries |
| `features.pressure.slope_n20` | `sample_slope` | least-squares slope over 20 samples (hPa/s) |
| `features.pressure.delta_rapid_rise` | `derived` | `delta_n5 > 5.0` → bool |
| `features.pressure.delta_shallow_rise` | `derived` | `2.0 ≤ delta_n5 ≤ 4.0` → bool |
| `features.pressure.above_high_threshold` | `derived` | `mean_n5 > thresholds.pressure.high` → bool |
| `features.pressure.below_low_threshold` | `derived` | `mean_n5 < thresholds.pressure.low` → bool |
| `features.pressure.submerge_rise` | `derived` | `mean_n5 − mean_n20` (approx baseline delta) |

### Rules (from `cep_rules.json`)

| Rule id | Trigger feature | Sustained | Hold-down | Enable flag |
|---|---|---|---|---|
| `pressure_dunk_detected` | `delta_rapid_rise ≥ 1.0` | 2 s | 5 s | `immersion_alerts_enabled` |
| `pressure_shallow_dunk` | `delta_shallow_rise ≥ 1.0` | 1 s | 3 s | `immersion_alerts_enabled` |
| `pressure_submerge_detected` | `submerge_rise ≥ 5.0` | 5 s | 30 s | `immersion_alerts_enabled` |
| `pressure_low_atm` | `below_low_threshold ≥ 1.0` | 10 s | 60 s | `low_atm_alert_enabled` |
| `pressure_high_atm` | `above_high_threshold ≥ 1.0` | 10 s | 60 s | `high_atm_alert_enabled` |

---

## 7. Alignment vs Legacy `PressureService` Logic

| Behaviour | `PressureService` | CEP | Notes |
|---|---|---|---|
| Dunk threshold | `delta > 5.0 hPa` (index arithmetic) | `delta_n5 > 5.0` | ✅ Identical |
| Dunk cooldown | 5 000 ms | `hold_down_sec: 5` | ✅ Identical |
| Shallow dunk range | `2.0..4.0 hPa` | `>= 2.0 AND <= 4.0` | ✅ Identical |
| Shallow cooldown | 3 000 ms | `hold_down_sec: 3` | ✅ Identical |
| Atm pressure window | `takeLast(5).average()` | `mean_n5 (sample_count=5)` | ✅ Identical |
| Min samples — dunk/shallow | 10 entries required | 6 entries required | ⚠ CEP fires sooner; PressureService had extra guard |
| Atm pressure sustained | Fires immediately | 10 s sustained required | ✅ CEP intentionally stricter to avoid transient spikes |
| Submerge baseline | Frozen snapshot before rise | `mean_n5 − mean_n20` rolling diff | ⚠ Approximation — see Phase 7 note below |
| User enable flags | Explicit boolean checks | `gte_key` on `settings.pressure.*` | ✅ Semantically equivalent |
| Live sensor path | Only DataRepository | DataRepository **and** CepBus | ✅ Both active post-Phase 6 |

---

## 8. Phase 7 Known Limitation — Submerge Detection

The original `handleSubmergeDetection()` uses a **frozen baseline**: it captures `mean(history[size-10..size-5])` once when the pressure is stable, then continuously checks `latest > baseline + 5 hPa` for 60 consecutive seconds. The baseline does not update during the submersion.

CEP's `submerge_rise = mean_n5 − mean_n20` is a rolling approximation. Once the device has been submerged long enough for `mean_n20` to incorporate 20 elevated-pressure samples, `mean_n20 ≈ mean_n5` and `submerge_rise` returns to ~0. At 500 ms/sample this takes approximately 10 seconds, which is why the rule uses `sustained_for_sec: 5` (achievable within the window before mean_n20 catches up) rather than the original 60 seconds.

**Phase 7 resolution:** Add a `sample_window_offset` feature type that computes a window over samples `[N+offset .. N+offset+count]` (a ring slice that does not include the most recent N samples). This would allow:

```json
{
  "id": "features.pressure.baseline_n5_offset5",
  "type": "sample_window_offset",
  "source": "pressure",
  "op": "avg",
  "sample_count": 5,
  "offset_samples": 5
}
```

Combined with a `sustained_for_sec: 60` rule, this would exactly replicate the frozen-baseline 60-second confirmation.
