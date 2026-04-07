# CEP Engine — Ongoing Code Changes (Phases 1–4)

Scope: Kotlin source files only. No simulation service changes, no `cep_rules.json` changes.
Every entry describes exactly what was changed in the file, what existed before, and why
the change was made.

---

## Phase 1 — Wall-clock timing semantics

### `TriggerTimer.kt`

**What changed.**
The entire sustain-tracking mechanism was replaced. The old implementation held an `Int`
field (`activeFor`) that incremented by 1 each time the rule-eval loop called `tick()` while
the condition was true. The maturity check was `activeFor >= sustainedForSec * tickHz`. That
coupling to `tickHz` meant the timer ran at the speed of the eval loop: at 2 Hz it counted
twice as fast and reported 180 s of elapsed time after only 90 s of real wall-clock time.

The `activeFor` field was removed entirely and replaced with:

```kotlin
private var conditionTrueStartMs: Long? = null
```

When the condition first becomes true, `conditionTrueStartMs` is set to `now`
(`System.currentTimeMillis()`). It is set back to `null` the moment the condition becomes
false, and `warnFiredThisEpisode` is also reset at that point. Elapsed seconds are derived
on every `tick()` call as:

```kotlin
val elapsedSec = conditionTrueStartMs?.let { (now - it) / 1000L } ?: 0L
val mature = elapsedSec >= effectiveSustainSec
```

The `holdDownSec` window — which prevents re-fire after an alert clears — was already
wall-clock based (`lastFireAt` millisecond stamp) and did not change. The `cooldown` check
remains:

```kotlin
val cooldown = (now - lastFireAt) >= holdDownSec * 1000
```

The `sustainWarnSec` path (pre-warn threshold) uses the same `elapsedSec` value, so it
is also rate-independent:

```kotlin
if (warnSec != null && warnSec in 1 until effectiveSustainSec
        && elapsedSec >= warnSec && !warnFiredThisEpisode) {
    warnFiredThisEpisode = true
    warnFired = true
}
```

**Objective.**
Make every time-based threshold in `cep_rules.json` (`sustained_for_sec`,
`sustained_for_sec_warn`, `hold_down_sec`) behave identically at any eval rate. A rule
declared as `"sustained_for_sec": 180` must fire after 180 real seconds regardless of
whether the eval loop runs at 1 Hz, 2 Hz, 4 Hz, or 10 Hz. This was the root regression in
WEAR-441: at elevated tick rates the engine fired alerts significantly earlier than their
defined thresholds.

---

### `RuleEngine.kt`

**What changed.**
`TriggerTimer.tick()` now receives `now` (a `Long` epoch-ms value) explicitly on every call
rather than deriving it internally. `RuleEngine.processTick()` is the only caller and passes
`now = System.currentTimeMillis()` once per loop iteration, ensuring all timers in a single
eval cycle share a consistent clock snapshot:

```kotlin
suspend fun processTick(now: Long = System.currentTimeMillis()) {
    ...
    val result = compiledTimers[r.id]?.tick(cond, now, effMain, sustainWarnSec)
        ?: TickResult(false, false)
```

The `tick()` signature also gained the `effectiveSustainSec` and `sustainWarnSec` parameters
so that per-call sustain overrides (loaded from `DataRepository` and held in
`sustainOverridesMain` / `sustainOverridesWarn`) can modify the effective threshold without
mutating the timer object itself:

```kotlin
val effMain = if (r.allowSustainOverride)
    overridesMain[r.id]?.coerceAtLeast(0L) ?: base
else base
```

**Objective.**
Decouple the timer from the ability to know its own threshold — the threshold is a
per-call argument, not stored state. This enables runtime sustain overrides (user-adjustable
alert sensitivity) without restarting the engine or recreating timers.

---

### `CepEngine.kt` — `evt.ts` forwarded to `WindowStore`

**What changed.**
The bus ingest coroutine was changed from passing `System.currentTimeMillis()` as the
timestamp to passing `evt.ts` — the timestamp field carried on the event itself:

```kotlin
// Phase 1: pass evt.ts so WindowStore timestamps reflect sensor measurement time
scope.launch {
    CepBus.events.collect { evt -> windows.ingest(evt, featureStore, evt.ts) }
}
```

Previously every ring-buffer `add()` call was stamped with the time the engine *processed*
the event. `evt.ts` carries the time the *sensor measured* the value, which can differ
significantly when events are batched or backdated (as in Phase 4 stale-timestamp tests).

**Objective.**
Ensure that `FeatureStore.ts[key]` and all `TimedDoubleRing` timestamp entries reflect
sensor measurement time rather than engine processing time. This is the prerequisite for
Phase 2 (time-based windows must span real measurement time, not processing time) and
Phase 4 (freshness guards read `ts[key]` to determine data age — if that timestamp was
always `now`, freshness would always be zero and guards would never trigger).

---

### `CepEngine.kt` — rule eval loop driven by `ruleEvalIntervalMs`

**What changed.**
The rule-eval coroutine no longer uses a hard-coded tick delay. It reads
`config.ruleEvalIntervalMs` — a value parsed from `cep_rules.json` —
and applies a minimum floor of 1 ms:

```kotlin
scope.launch {
    val tickMs = config.ruleEvalIntervalMs.toLong().coerceAtLeast(1L)
    while (isActive) {
        featureStore.beginTick()
        featureStore.logDerivedTick("CEP")
        ruleEngine.processTick()
        checkAndClearAlerts()
        delay(tickMs)
    }
}
```

**Objective.**
Decouple the rate at which rules are evaluated from the rate at which ring-buffer windows
are ticked. `window_tick_ms` controls ring resolution; `rule_eval_interval_ms` controls
alert latency. Setting them independently (Phase 5 goal) allows, for example, 1-second ring
resolution while evaluating rules every 500 ms for lower alert latency — without changing
any Kotlin code, only the JSON.

---

### `RuleCompiler.kt` — `rule_eval_interval_ms` and `max_age_sec` parsing

**What changed.**
Two new JSON keys are now parsed from the `engine` and `features` blocks respectively.

In the `engine` block, `rule_eval_interval_ms` is read and falls back to `window_tick_ms`
when absent, preserving backward compatibility:

```kotlin
val ruleEvalIntervalMs = engine?.optInt("rule_eval_interval_ms", tickMs) ?: tickMs
...
CepConfig(tickHz, features, rules, ruleEvalIntervalMs)
```

In each feature definition, `max_age_sec` is parsed as an optional `Long?` on `Window`,
`MultiWindow`, and `SlopeMulti` feature types:

```kotlin
maxAgeSec = if (o.has("max_age_sec") && !o.isNull("max_age_sec"))
    o.getLong("max_age_sec") else null
```

**Objective.**
Make engine timing and feature freshness fully data-driven from JSON. No Kotlin rebuild is
needed to change tick resolution, eval rate, or the maximum age a feature source is
considered valid. The `max_age_sec` field enables `WindowStore` to skip computing a window
feature if its source ring's most-recent timestamp is older than the declared tolerance —
relevant when a sensor has gone silent and a stale window average should not be emitted.

---

### `CepConfig.kt` — `ruleEvalIntervalMs` field

**What changed.**
A new field was added to the `CepConfig` data class:

```kotlin
data class CepConfig(
    val tickHz: Int,
    val features: List<FeatureDef>,
    val rules: List<RuleDef>,
    val ruleEvalIntervalMs: Int = 1000   // Phase 1
)
```

The default value of 1000 preserves the original 1-Hz behaviour for any caller that
constructs `CepConfig` without specifying the field.

`FeatureDef` subtypes each gained an `updateIntervalMs: Long` field (Phase 3, compiled in
the same class) with a default of 1000 ms.

**Objective.**
Provide a typed, compile-checked home for the eval interval rather than passing it as a
loose `Int` through call chains. The default value ensures the change is backward-compatible:
existing code that constructs `CepConfig` directly (e.g. in tests) continues to produce
1-Hz eval behaviour without modification.

---

## Phase 2 — Time-based windows and slopes

### `Rings.kt` — `TimedDoubleRing` and `TimedArrayRing`

**What changed.**
Two new ring-buffer classes were added alongside the existing `DoubleRing` and `ArrayRing`.
The legacy classes are unchanged and still used internally where timestamp awareness is not
needed (e.g. pre-Phase-2 callers).

`TimedDoubleRing` stores a parallel `LongArray times` alongside its `DoubleArray values`,
recording the `evt.ts` of each ingested sample. Three new time-based access methods were
added:

```kotlin
// Returns all values whose timestamp falls within the last windowMs milliseconds.
fun tailByTime(windowMs: Long, nowMs: Long): List<Double>

// Same but returns (tsMs, value) pairs — used by slope compute for timestamp-based x-axis.
fun tailByTimePairs(windowMs: Long, nowMs: Long): List<Pair<Long, Double>>

// Returns the value of the most-recent sample at-or-before (nowMs - horizonMs).
// Used for Delta: delta = current - value_at(now - horizon).
fun getByTime(horizonMs: Long, nowMs: Long): Double?
```

All three walk the ring backwards (newest-first) and stop at the first sample older than the
cutoff, which is correct because samples are ingested in non-decreasing timestamp order.

`TimedArrayRing` provides the same timestamp extension for multi-channel samples
(`List<Double>` per sample), used for skin-temperature peripheral channels. It adds:

```kotlin
fun tailByTimePairs(windowMs: Long, nowMs: Long): List<Pair<Long, List<Double>>>
```

**Objective.**
Enable every window feature (`Window`, `MultiWindow`, `SlopeMulti`, `Delta`) to span a
fixed real-time horizon regardless of how many samples arrived in that interval. Without
timestamps in the ring, selecting "the last 60 seconds" required counting back `60 * tickHz`
slots — at 4 Hz that was 240 samples for 60 s at 1 Hz but only 60 slots for 60 s at 4 Hz,
making windows appear 4× narrower at the faster rate. The time-based variants fix this so
a 60-second window always contains exactly the last 60 seconds of sensor data.

---

### `RingConfig.kt` — `maxSensorHz`

**What changed.**
A `maxSensorHz: Int` field was added to `RingConfig` with a default of 4:

```kotlin
data class RingConfig(
    val maxSecondsNumeric: Int = 3600,
    val maxSecondsArray: Int = 3600,
    val maxSensorHz: Int = 4   // Phase 2
)
```

Ring capacities are sized as `maxSecondsNumeric * maxSensorHz` so that `TimedDoubleRing`
instances never wrap (overwrite unread entries) within the configured time horizon even
when sensors deliver at the maximum expected rate.

**Objective.**
Prevent `TimedDoubleRing` from evicting data within its configured horizon at elevated
sensor rates. A ring sized for 1 Hz would exhaust its capacity 4× faster at 4 Hz, causing
`tailByTime` to return incomplete windows. Accounting for `maxSensorHz` in the capacity
formula ensures window accuracy is maintained at any rate up to the configured maximum.

---

### `MathOps.kt` — `lsqSlopeXY`

**What changed.**
A new function was added alongside the existing `lsqSlope(series: DoubleArray)`:

```kotlin
fun lsqSlopeXY(xs: DoubleArray, ys: DoubleArray): Double?
```

`lsqSlope` assumes uniform sample spacing and uses integer sample indices as its x-axis
(`x = 0, 1, 2, ...`). The returned slope is therefore in units of `value / sample`, not
`value / second`. At variable tick rates that unit is meaningless for threshold comparisons.

`lsqSlopeXY` accepts arbitrary `(x, y)` pairs. `WindowStore.computeSlopeMulti` calls it
with the sample timestamps converted to seconds from the first sample as the x-axis, making
the returned slope `°C/s` or `bpm/s` independently of how many samples arrived per second.

Full OLS implementation:

```kotlin
val denom = n * sumXX - sumX * sumX
if (denom == 0.0) return null
return (n * sumXY - sumX * sumY) / denom
```

**Objective.**
Produce physically meaningful, rate-independent slope values for temperature and heart-rate
slope features. Without this, `slope_c_per_s_20s` at 4 Hz would use 80 samples spanning
20 s but compute the slope as if those samples were evenly spaced in integer steps, yielding
a coefficient 4× smaller than the actual °C/s rate. Rules that threshold on `slope_c_per_s_20s`
would fail to fire during cold-stress or heat-stress scenarios at elevated tick rates.

---

### `WindowStore.kt` — time-based compute methods

**What changed.**
The four feature-compute methods (`computeWindow`, `computeMultiWindow`, `computeSlopeMulti`,
`computeDelta`) were rewritten to use `TimedDoubleRing` / `TimedArrayRing` and the new
time-based selection methods.

**`computeWindow` and `computeMultiWindow`** now call `tailByTime(windowSec * 1000L, tsMs)`
instead of `tail(windowSec * tickHz)`. The result is the same set of operations (min, max,
avg, sum) applied to a time-bounded sample list rather than a count-bounded one.

**`computeSlopeMulti`** calls `tailByTimePairs(windowSec * 1000L, tsMs)` to get
`(timestamp, value)` pairs, then builds `xs` (seconds from first sample) and `ys` (values)
arrays and passes them to `MathOps.lsqSlopeXY`:

```kotlin
val pairs = ring.tailByTimePairs(def.windowSec * 1000L, tsMs)
val t0 = pairs.first().first
val xs = DoubleArray(pairs.size) { i -> (pairs[i].first - t0) / 1000.0 }
val ys = DoubleArray(pairs.size) { i -> pairs[i].second }
val slope = MathOps.lsqSlopeXY(xs, ys) ?: return
```

**`computeDelta`** calls `getByTime(horizonSec * 1000L, tsMs)` to look up the sample
closest to `now - horizonSec` rather than indexing back `horizonSec * tickHz` slots:

```kotlin
val back = ring.getByTime(def.horizonSec * 1000L, tsMs) ?: return
fs.putDerived(def.id, now - back, tsMs)
```

**Objective.**
Eliminate both failure modes of count-based windowing at variable rates: windows appearing
narrower at high tick rates (the `tailByTime` fix) and slopes computed over non-uniform
time intervals producing incorrect `°C/s` gradients (the `lsqSlopeXY` fix). After these
changes, any feature that computes over a time horizon is rate-independent — the 60-second
HRV average and the 20-second skin-temp slope produce the same value whether the simulation
runs at 1 Hz or 4 Hz.

---

## Phase 3 — Per-feature compute cadence decoupling

### `CepEngine.kt` — per-feature coroutines

**What changed.**
The `computeFeaturesSafely()` method, which ran all feature computations synchronously
inside the rule-eval loop, was removed. In its place, `start()` launches one independent
coroutine per feature definition:

```kotlin
for (def in config.features) {
    scope.launch {
        while (isActive) {
            try {
                when (def) {
                    is FeatureDef.Window      -> windows.computeWindow(def, featureStore)
                    is FeatureDef.MultiWindow -> windows.computeMultiWindow(def, featureStore)
                    is FeatureDef.SlopeMulti  -> windows.computeSlopeMulti(def, featureStore)
                    is FeatureDef.Delta       -> windows.computeDelta(def, featureStore)
                    is FeatureDef.Derived     -> DerivedEval.eval(def, featureStore)
                }
            } catch (ex: Throwable) {
                Log.e("CEP", "Feature compute failed for ${def.id} — isolated, engine continues", ex)
            }
            delay(def.updateIntervalMs)
        }
    }
}
```

Each coroutine sleeps for `def.updateIntervalMs` between cycles. The rule-eval loop
continues to run independently at `ruleEvalIntervalMs` and always reads the last
cached value from `FeatureStore` — it does not wait for any feature to recompute.

The `try/catch(Throwable)` around each feature compute means an exception in one feature
(e.g. NaN arithmetic overflow, null dereference in a derived expression) is caught, logged
with the feature ID, and the coroutine resumes at the next `delay`. No other feature
coroutine or the rule-eval loop is affected.

**Objective.**
Two goals. First, prevent slow features from stalling fast rule evaluation. A skin-temp
minimum-over-1-minute feature that recomputes every 5 s previously blocked every rule from
evaluating during its computation window. With independent coroutines, the HR emergency rule
evaluates at 1 s regardless of the skin-temp feature cadence. Second, isolate feature
exceptions: a bug or bad sensor input in one feature derivation cannot crash or stall the
engine for unrelated biometric rules.

---

### `RuleCompiler.kt` — `update_interval_ms` parsing

**What changed.**
Each feature definition in the `features` array now has its `update_interval_ms` key read
and stored in `FeatureDef.updateIntervalMs`:

```kotlin
val updateIntervalMs = if (o.has("update_interval_ms") && !o.isNull("update_interval_ms"))
    o.getLong("update_interval_ms") else 1000L
```

This value is passed to every `FeatureDef` subtype constructor. When the key is absent the
default is 1000 ms, preserving legacy 1-Hz behaviour for any feature that has not yet been
annotated in the JSON.

**Objective.**
Allow each feature's compute cadence to be configured independently in `cep_rules.json`
without a Kotlin rebuild. Time-insensitive features (e.g. a 1-minute minimum) can declare
`"update_interval_ms": 5000` and recompute every 5 s; time-sensitive features (e.g. HR
delta) can stay at 500 ms or 1000 ms. The rule-eval loop reads the latest cached value in
all cases.

---

## Phase 4 — Native-rate ingestion and freshness

### `CepBus.kt` — buffer capacity 256 → 512

**What changed.**
The `extraBufferCapacity` parameter on the `MutableSharedFlow` was increased from 256 to
512. The `DROP_OLDEST` overflow policy was retained:

```kotlin
// Phase 4: buffer raised 256 → 512 to handle native-rate sensor ingestion.
val events = MutableSharedFlow<CepEvent<out CepPayload>>(
    replay = 0,
    extraBufferCapacity = 512,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)
```

**Objective.**
At native sensor rates (~5–10 Hz per sensor, ~16 topics) the bus can receive over
100 events per second in steady state, and up to ~3 200 events in a single burst tick
(20× multiplier × 16 topics × several repeat calls). The original 256-slot buffer would
overflow in under 2.5 s of 10-Hz operation, triggering `DROP_OLDEST` and silently
discarding the exact events that transition a `TriggerTimer` from inactive to active.
512 provides approximately 5 s of steady-state headroom before `DROP_OLDEST` is invoked
as a last-resort safety valve. The policy itself is kept rather than switching to
backpressure, because backpressuring the sensor service threads would stall the
Android sensor framework.

---

### `FeatureStore.kt` — `doubleIfFresh()`

**What changed.**
A new method was added that returns a feature's `Double` value only if it exists AND its
stored timestamp is within `maxAgeSec` seconds of `nowMs`:

```kotlin
fun doubleIfFresh(
    key: String,
    maxAgeSec: Double,
    nowMs: Long = System.currentTimeMillis()
): Double? {
    val age = ageSeconds(key, nowMs) ?: return null  // key missing → null
    if (age > maxAgeSec) return null                  // stale → null
    return double(key)
}
```

The supporting `ageSeconds(key, nowMs)` method computes `(nowMs - ts[k]) / 1000.0` using
the timestamp written alongside every `put()` / `putDerived()` call. It returns `null` if
the key has never been written.

**Objective.**
Provide the primitive that `JsonLogic` freshness operators need to implement per-condition
data-age tolerances. Without it, every `gte_key` / `lte_key` comparison operated on
whatever value was last stored, even if that value arrived five minutes ago during a
signal gap. `doubleIfFresh` makes staleness explicit: returning `null` for an absent or
outdated value causes the calling `JsonLogic` operator to short-circuit to `false`, blocking
the rule condition cleanly rather than acting on stale biometric data.

---

### `FeatureStore.kt` — NaN guard in `put()` and `putDerived()`

**What changed.**
Both write paths now check for `Double.NaN` and `Float.NaN` before storing the value. A
matching `W/CEP` warning is emitted and the method returns without modifying the stored
value or its timestamp:

```kotlin
fun put(id: String, value: Any?, nowMs: Long = ...) {
    if (value == null) return
    if (value is Double && value.isNaN()) {
        Log.w("CEP", "[FeatureStore] NaN rejected for key=$id — retaining last valid value")
        return
    }
    if (value is Float && value.isNaN()) {
        Log.w("CEP", "[FeatureStore] NaN rejected for key=$id — retaining last valid value")
        return
    }
    val k = namespacedId(id)
    kv[k] = value
    ts[k] = nowMs
}
```

`putDerived` carries a distinct log tag `"NaN rejected (derived)"` to identify whether
the NaN entered from the raw sensor path or the derived-feature computation path.

**Objective.**
JVM floating-point arithmetic with NaN operands is entirely silent — it does not throw and
produces another NaN. A single NaN HR value would propagate through the entire derived
feature chain (`karvonen_ratio`, `hrr_percent`, `hr.delta_60s`, etc.), silently storing NaN
for every downstream feature that uses HR as an input. Those NaN values would then reach
`JsonLogic` operators: comparisons like `NaN >= 120` evaluate to `false` in JVM, which
happens to be the safe direction for most alert conditions, but this is coincidental and
cannot be relied upon — the engine would be operating on corrupted state with no
observable signal. The guard ensures:

1. The last valid sensor value is retained in the store, so rules continue evaluating
   against real data rather than silently using NaN-poisoned features.
2. The `W/CEP` log line makes injection events observable in logcat without needing to
   decode silent state corruption or set breakpoints.
3. The `putDerived` guard provides a second layer: even if `DerivedEval` computes a
   derived feature from an incoming event payload value before the raw `put` guard
   intercepts the NaN write, the derived write is independently blocked. The P3-T3 log
   run confirmed this: `heart_rate` was blocked at the raw level, then `features.hr.delta_60s`
   was blocked at the derived level in the same tick.

---

### `JsonLogic.kt` — `lte_key` and `gte_key` null-safe semantics

**What changed.**
Both operators now short-circuit to `return false` when `fs.double(key)` returns `null`
(key absent from the feature store), rather than falling through to the `optDouble` default:

```kotlin
node.has("lte_key") -> {
    // Phase 4: null feature (missing or stale) → false, not +infinity.
    val x = fs.double(a.getString(0)) ?: return false
    val y = a.optDouble(1, Double.POSITIVE_INFINITY)
    x <= y
}

node.has("gte_key") -> {
    // Phase 4: null feature (missing or stale) → false, not -infinity.
    val x = fs.double(a.getString(0)) ?: return false
    val y = a.optDouble(1, Double.NEGATIVE_INFINITY)
    x >= y
}
```

Previously, `fs.double(key)` returning `null` caused the expression to evaluate as
`null <= +∞` (for `lte_key`) or `null >= -∞` (for `gte_key`), both of which resolved to
`true` — causing rule conditions to pass when the referenced feature had never been ingested.

**Objective.**
Prevent false-positive alerts at engine startup and during signal gaps. Before the first
sensor events are delivered, the feature store is entirely empty. Any rule whose `when`
condition includes `lte_key` or `gte_key` on a not-yet-ingested feature should evaluate to
`false` and hold the rule's `TriggerTimer` in the unstarted state. The previous behaviour
silently started timers on absent data; the fix ensures timers only advance when the engine
has real observed values to act on. This is a safety-first default: delaying an alert by
one eval cycle is far less dangerous than firing it on the absence of a reading.

---

### `JsonLogic.kt` — `gte_key_fresh` and `lte_key_fresh` operators

**What changed.**
Two new operator branches were added to the `when` block, positioned immediately after
their non-fresh counterparts:

```kotlin
node.has("lte_key_fresh") -> {
    // Phase 4: like lte_key but enforces max_age_sec freshness guard.
    // Syntax: ["featureKey", threshold, maxAgeSec]
    // Returns false if feature is missing OR older than maxAgeSec.
    val a = node.getJSONArray("lte_key_fresh")
    val x = fs.doubleIfFresh(a.getString(0), a.getDouble(2)) ?: return false
    val y = a.optDouble(1, Double.POSITIVE_INFINITY)
    x <= y
}

node.has("gte_key_fresh") -> {
    // Phase 4: like gte_key but enforces max_age_sec freshness guard.
    // Syntax: ["featureKey", threshold, maxAgeSec]
    // Returns false if feature is missing OR older than maxAgeSec.
    val a = node.getJSONArray("gte_key_fresh")
    val x = fs.doubleIfFresh(a.getString(0), a.getDouble(2)) ?: return false
    val y = a.optDouble(1, Double.NEGATIVE_INFINITY)
    x >= y
}
```

Both operators read three array elements from the JSON condition: feature key, numeric
threshold, and maximum age in seconds. The third element is passed directly to
`FeatureStore.doubleIfFresh()`. If that method returns `null` (feature absent or timestamp
older than `maxAgeSec`), the operator short-circuits to `return false` before the threshold
comparison is evaluated.

**Objective.**
Enable individual rules in `cep_rules.json` to declare a per-condition data-age tolerance
using only a JSON change, with no new Kotlin required. For example, a rule can express
`"gte_key_fresh": ["features.hr.bpm", 120, 5]` to mean "heart rate is ≥ 120 bpm AND this
reading is no more than 5 seconds old." If the sensor has been silent for 6 seconds, the
condition evaluates to `false` and the `TriggerTimer` does not advance — the alert is
suppressed until fresh data arrives. The `maxAgeSec` value is per-operator and per-rule,
not global: different rules or different conditions within the same rule can tolerate
different staleness windows for the same feature key. `FeatureStore.doubleIfFresh` is the
only production call site for these operators.

---

### `AlertRegistry.kt` — `clearAll()`

**What changed.**
A new public method was added that calls `active.clear()` on the underlying
`ConcurrentHashMap<String, ActiveAlert>`:

```kotlin
/** Clear all active alerts (e.g., simulation isolation or production profile reset). */
fun clearAll() {
    active.clear()
}
```

The existing API provided single-rule removal (`clear(ruleId: String)`), acknowledgement
by TAK UID (`clearByUid(uid: String)`), and individual entry lookup (`get`, `allActive`).
There was no way to atomically remove all active alerts in a single operation.

**Objective.**
Two production use cases. First, when a user profile is reset — a different operator logs
in, device pairing changes, or year-of-birth is updated — any active alerts from the
previous session carry stale context (UID, exit conditions, sustain timestamps) that no
longer applies to the new profile. Iterating `allActive()` and calling `clear()` per
entry is racey on a `ConcurrentHashMap` under concurrent `tryActivate` calls; `clearAll()`
provides a single atomic clear. Second, when the CEP engine is restarted while the
process remains alive (e.g. on a `onDestroy` / restart cycle), any alerts that were active
at shutdown would otherwise persist in the singleton registry and immediately appear as
active to the first `SimulationObserver` or production observer of the new session.
`ConcurrentHashMap.clear()` is an O(n) operation but is internally atomic with respect to
concurrent reads and writes on individual buckets.

---

## Open items (not yet implemented)

| Item | File | Notes |
|---|---|---|
| `DerivedEval` freshness | `DerivedEval.kt` | `eval()` calls `fs.double()` for feature inputs, not `fs.doubleIfFresh()`. Derived features can be computed from stale raw inputs without triggering the freshness guard. Requires adding `maxAgeSec` to `FeatureDef.Derived` and passing it into each `eval()` call. |
| `gte_key_fresh` / `lte_key_fresh` in rules | `cep_rules.json` | Operators exist in `JsonLogic.kt` but no rule currently uses them. Freshness suppression is not observable from any ASSERT line until at least one rule is migrated. |
| `FeatureStore.debugSnapshot()` | `FeatureStore.kt` | No-arg full-store snapshot for test assertions (design doc E2). Required before any test can assert derived feature *values* at a specific point in time rather than just alert fire times. |
| `TriggerTimer` elapsed accessor | `TriggerTimer.kt` | `conditionTrueStartMs` is private. Cannot externally assert "timer has been accumulating for N seconds" mid-scenario (design doc E3). |
| Phase 5 eval rate | `cep_rules.json` | `rule_eval_interval_ms` is wired end-to-end but still set to 1000 ms. Changing it to 500 ms is the only step needed to activate Phase 5. |
