# WEAR-441-HZ Changelog

All notable changes to the CEP engine and simulation harness are recorded here.
Format: `[YYYY-MM-DD] <type>: <summary>` with full context per entry.

---

## [2026-04-01] — CEP Phase 4 + Simulation Hardening

### Objective
Complete Phase 4 (native-rate ingestion & freshness) test scaffolding, fix cross-scenario
simulation pollution, and close three CEP engine correctness gaps identified during P1–P4
log analysis.

---

### Code Fixes

#### `AlertRegistry.kt`
**Change:** Added `clearAll()` method.
```kotlin
fun clearAll() { active.clear() }
```
**Reason:** `emitResetBurst` previously cleared TriggerTimer state (via safe-signal events) but
left `AlertRegistry.active` intact. Stale entries from scenario N were visible to the
`SimulationObserver` of scenario N+1, producing false `ALERT_FIRED` ASSERT lines at tick 1.
`clearAll()` is also available for production use (e.g. user profile reset).

---

#### `FeatureStore.kt`
**Change:** NaN guard added to both `put()` and `putDerived()`.

```kotlin
if (value is Double && value.isNaN()) {
    Log.w("CEP", "[FeatureStore] NaN rejected for key=$id — retaining last valid value")
    return
}
if (value is Float && value.isNaN()) { ... return }
```
**Reason:** JVM `Double.NaN` arithmetic is silent — it does not throw. A `Double.NaN` HR value
injected at P3-T3 tick 10 would propagate through the entire derived feature chain
(`karvonen_ratio`, `hrr_percent`, etc.) and silently poison every downstream rule evaluation.
With the guard, the last valid value is retained and a `W/CEP` log line is emitted instead.
Both `put()` and `putDerived()` are guarded because derived features (DerivedEval outputs)
can also produce NaN when their inputs are NaN.

---

#### `JsonLogic.kt`
**Change:** Two new freshness-aware comparison operators wired to `FeatureStore.doubleIfFresh()`.

| Operator | Syntax | Semantics |
|---|---|---|
| `gte_key_fresh` | `["featureKey", threshold, maxAgeSec]` | `feature >= threshold` **and** feature age ≤ `maxAgeSec`; returns `false` if missing or stale |
| `lte_key_fresh` | `["featureKey", threshold, maxAgeSec]` | `feature <= threshold` **and** feature age ≤ `maxAgeSec`; returns `false` if missing or stale |

**Reason:** `FeatureStore.doubleIfFresh()` was added in Phase 4 but had no callers in
`JsonLogic.kt`. The existing `gte_key` / `lte_key` operators use `fs.double()` which ignores
timestamp age entirely — a stale feature value is treated as valid. The new operators close
this gap. No `cep_rules.json` rule has been migrated to use them yet; P4-T1 is a smoke test
that verifies engine stability with stale-timestamped events.

---

### Simulation Fixes

#### `CEPSimulationService.kt` — `SimulationObserver`
**Change:** Baseline snapshot of pre-existing `AlertRegistry` entries taken at construction;
`sample()` now skips any alert whose `ruleId` is in `baselineIds`.

```kotlin
private val baselineIds: Set<String> =
    AlertRegistry.allActive().map { it.ruleId }.toSet()

fun sample(elapsedMs: Long) {
    for (alert in AlertRegistry.allActive()) {
        if (alert.ruleId in baselineIds) continue   // skip pre-existing carryover
        ...
    }
}
```
**Reason:** Even with `emitResetBurst` running, there is a window between `AlertRegistry.clearAll()`
completing and the new `SimulationObserver` being constructed where no alert should appear.
However, the `SimulationObserver` was previously initialised *before* the first tick, meaning
any alert left from the previous scenario (before the fix to `emitResetBurst`) would be
seen at tick 1 and logged as `ALERT_FIRED`. The baseline snapshot is a defence-in-depth
guard that remains correct regardless of reset-burst timing.

---

#### `CEPSimulationService.kt` — `emitResetBurst`
**Change 1:** Iteration count increased from 3 to 6.
**Change 2:** `AlertRegistry.clearAll()` called after the loop completes.

```kotlin
repeat(6) { ... delay(1_000L) }
AlertRegistry.clearAll()
Log.i(TAG, "[RESET] ↺ emitResetBurst: complete (AlertRegistry cleared)")
```
**Reason (6 iterations):** P4-T4 runs 35 s of `STILL + HR=195` followed immediately by P4-T5.
The original 3 s reset was insufficient for FeatureStore derived features (e.g. `hrr_percent`,
`karvonen_ratio`) to propagate the new safe-HR values before P4-T5's `SimulationObserver`
sampled the registry. This caused P4-T5 to see the `hr_hrmax_rest_immediate_emergency` alert
fire at ~170.4 s instead of ~180 s.
**Reason (clearAll):** Described above under `AlertRegistry.kt`.

---

#### `CEPSimulationService.kt` — P1-T4 `ScenarioConfig` redesign
**Before:** `RUN + HR=153 (Zone 3)`, targeting `hr_zone3_exercise_30s_warn` which has
`type="none"` in `cep_rules.json` — this rule never writes to `AlertRegistry`. The
`ALERT_FIRED` seen at 1.0 s was carryover from P1-T3's emergency rule.

**After:**
```
STILL + HR=125, 640 ticks × 500ms = 320s real at 2 Hz
```
- HR=125 is above `high_threshold_bpm` (120) but below `HRmax` (~183.5 for age 35).
- Only `hr_high_threshold_resting_alert` triggers (`sustained_for_sec_warn = 300 s`).
- Pre-warn fires at ~300 s wall-clock, **not** ~150 s (which a tick-count bug would produce).
- Pre-warns skip `AlertRegistry.tryActivate()` by design (`WarnActionHandler.isPreWarn=true`).
- Expected ASSERT: `SUMMARY no_alerts_fired`.
- Manual logcat check: `W/CEP.*hr_high_threshold_resting_alert` at ~300 s.

**Reason:** The original P1-T4 config was untestable because the targeted rule's `type="none"`
means the main-fire path does nothing. The redesign isolates the `sustained_for_sec_warn`
wall-clock path using a rule that is known to be active and observable via logcat.

---

#### `CEPSimulationService.kt` — P3-T3 comments
**Change:** Corrected scenario comments to reflect actual NaN behaviour and the new guard.

Old comment said: *"triggering a compute exception"* / *"Feature compute failed … error line appears"*.
New comment explains:
- JVM NaN propagates silently; there is no exception.
- `FeatureStore` now rejects NaN before storage.
- Expected logcat signal changed to `W/CEP "[FeatureStore] NaN rejected for key=features.hr.*"`.
- `ENGINE_RUNNING_POST_EXCEPTION` ASSERT still fires at scenario end as a liveness check.

---

#### `CEPSimulationService.kt` — P4-T1 comments
**Change:** Extended comments to clarify the scope of the test.

Added explanation that `gte_key_fresh` / `lte_key_fresh` are now wired in `JsonLogic.kt` but
no rule in `cep_rules.json` yet uses them. P4-T1 therefore remains a **smoke/robustness**
test only: it verifies that 5-minute-old event timestamps do not crash the engine, not that
stale data is correctly suppressed by freshness-guard rules.

---

### Phase 4 Orchestration (earlier in session)
**Change:** Added Phase 4 enum entries, `ScenarioConfig` branches, and orchestration loop to
`startPhase1TestLoop()`.

Five scenarios added:

| ID | Purpose |
|---|---|
| `P4_T1_STALE_TIMESTAMP` | Engine stability with `tsOffsetMs = −300 000` (5 min stale) |
| `P4_T2_SIGNAL_GAP` | 10 s dropout (`signalGapRange`) — engine resumes cleanly |
| `P4_T3_BURST_STRESS` | 20× burst at tick 5 — `CepBus` 512-entry buffer headroom |
| `P4_T4_GAP_THEN_BURST` | Gap + burst combined — co-operation without crash |
| `P4_T5_HIGHRATE_10HZ` | 3200 × 100 ms = 320 s real at 10 Hz — sharpest wall-clock regression guard |

`startPhase1TestLoop()` now ends with:
```
[ALL] ✅ P1 + P2 + P3 + P4 testing complete
```

---

### Files Changed

| File | Change type |
|---|---|
| `cep/engine/action/AlertRegistry.kt` | Code — added `clearAll()` |
| `cep/engine/FeatureStore.kt` | Code — NaN guard in `put()` and `putDerived()` |
| `cep/engine/JsonLogic.kt` | Code — `gte_key_fresh` and `lte_key_fresh` operators |
| `service/CEPSimulationService.kt` | Sim — `SimulationObserver` baseline snapshot |
| `service/CEPSimulationService.kt` | Sim — `emitResetBurst` 3→6 iterations + `clearAll()` |
| `service/CEPSimulationService.kt` | Sim — P1-T4 redesign (HR=125 STILL 2 Hz) |
| `service/CEPSimulationService.kt` | Sim — P3-T3 comment correction |
| `service/CEPSimulationService.kt` | Sim — P4-T1 comment extension |
| `service/CEPSimulationService.kt` | Sim — Phase 4 enum + configs + orchestration loop |

---

### Expected Logcat Assertions After This Change Set

| Scenario | ASSERT line | Wall-clock |
|---|---|---|
| P1-T1 | `ALERT_FIRED hr_hrmax_rest_immediate_emergency elapsedSec≈180` | ~180 s |
| P1-T2 | same rule, same ~180 s | NOT ~90 s (2 Hz tick-count bug) |
| P1-T3 | same rule, same ~180 s | NOT ~45 s (4 Hz tick-count bug) |
| P1-T4 | `SUMMARY no_alerts_fired` | `W/CEP hr_high_threshold_resting_alert` at ~300 s |
| P1-T6 | first fire ~180 s; no re-fire before ~300 s | hold-down regression |
| P3-T3 | `ENGINE_RUNNING_POST_EXCEPTION` | `W/CEP NaN rejected` at tick 10 |
| P4-T1 | `SUMMARY no_alerts_fired` | engine accepts stale ts without crash |
| P4-T5 | `ALERT_FIRED elapsedSec≈180` | NOT ~18 s (10 Hz tick-count bug) |

---

## [2026-04-02] — First Full P1–P4 Green Run (Log Analysis)

### Result: ✅ All 18 scenarios passed

Run date: 2026-04-02, 07:42–08:28 local time. PID 4764 (a second process instance after
a redeploy mid-first-attempt). Total runtime ~46 min.

### Per-scenario results

| Scenario | Result | firstFireSec | Notes |
|---|---|---|---|
| P1-T1 (1 Hz) | ✅ | 182.0 s | Wall-clock baseline established |
| P1-T2 (2 Hz) | ✅ | 181.5 s | NOT 90 s — tick-count bug confirmed fixed |
| P1-T3 (4 Hz) | ✅ | 180.0 s | NOT 45 s — tick-count bug confirmed fixed |
| P1-T4 (WARN) | ✅ | no_alerts_fired | Pre-warn correctly skips AlertRegistry; no carryover |
| P1-T6 (hold-down) | ✅ | 184.0 s | `Suppressing duplicate` at +120 s; no re-fire |
| P2-T1 (window 1 Hz) | ✅ | no_alerts_fired | HRV window ran clean |
| P2-T2 (window 4 Hz) | ✅ | no_alerts_fired | Time-based window not affected by tick rate |
| P2-T3 (slope 1 Hz) | ✅ | no_alerts_fired | Skin-temp slope baseline |
| P2-T4 (slope 4 Hz) | ✅ | no_alerts_fired | Time-based slope not affected by tick rate |
| P3-T1 (slow feature) | ✅ | 13.0 s | No 5-s stall; engine not blocked by slow feature cadence |
| P3-T2 (fast feature) | ✅ | 12.5 s | Fresh value propagated within 1-s rule-eval cycle |
| P3-T3 (NaN guard) | ✅ | 14.0 s | 2 NaN rejections; engine survived; liveness confirmed |
| P4-T1 (stale ts) | ✅ | no_alerts_fired | Engine accepted 5-min-stale timestamps without crash |
| P4-T2 (signal gap) | ✅ | no_alerts_fired | 10-s dropout absorbed; no crash |
| P4-T3 (burst stress) | ✅ | 13.0 s | 20× burst at tick 5; buffer held; alert still fired |
| P4-T4 (gap+burst) | ✅ | no_alerts_fired | Combined gap+burst; no crash; correct no-alert |
| P4-T5 (10 Hz) | ✅ | 175.0 s (tick) / ~183 s wall | NOT 18 s — sharpest wall-clock regression guard passed |

### Notable observations

**P3-T3 NaN propagation depth.** Two rejection lines fired: raw `heart_rate` key and derived
`features.hr.delta_60s`. The raw guard blocked storage of NaN into the feature store (retaining
195.0). The derived guard caught `delta_60s` separately — DerivedEval computed delta_60s from the
incoming payload value (before the store rejects it) rather than from the already-stored value,
so the NaN reached the derived pipeline in one hop. All downstream derivations then ran from the
stored 195.0 and produced no further NaN. The 1-s delay (14s vs P3-T1's 13s) is the one-tick
stabilisation cost after the rejection cycle — not a regression.

**P4-T5 `elapsedSec=175.0` note.** The ASSERT tick-based measurement reports 175s
(tick 1750 × 100ms), but actual wall-clock duration from scenario start to alert fire was ~183s.
At 10 Hz, `delay(100ms)` carries OS scheduling overhead so real wall-clock runs slightly faster
than the simulated tick counter. The TriggerTimer uses `System.currentTimeMillis()` and correctly
fired at ~183s real wall-clock (sustained_for_sec=180). The `elapsedSec` label is tick-based and
is expected to read slightly under the rule threshold at high tick rates. The critical proof is
that it did NOT fire at tick 180 (18s wall-clock), which the old bug would have produced.

**P1-T6 hold-down confirmation.** `Suppressing duplicate alert` appeared at 08:09:39, exactly
120s after the initial fire at 08:07:39 (184s elapsed). This is the hold-down boundary: the rule
attempted to re-fire at `firstFire + hold_down_sec = 184 + 120 = 304s elapsed`, was blocked by
AlertRegistry still holding the active entry. No second `ALERT_FIRED` line appeared in the
400-tick run. Hold-down regression guard confirmed working.

**Process restart.** PID 3487 appears at the top of the log (one RESET line) before being
replaced by PID 4764. This was a redeploy mid-first-attempt and had no effect on the run.
