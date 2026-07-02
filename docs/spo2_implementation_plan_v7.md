# SpO₂ Alerting — Consolidated Implementation Plan v7 (includes v7.1 deltas)

**Target branch:** `WEAR-441`
**Authoritative requirements:** `AP-SPO2 (Pulse oximetry) Alerting Strategy V1.0`
**Predecessor design:** `docs/WEAR-410_SPO2_ALERTING_PLAN_V0.6.md` (ported to 441 primitives)
**Posture:** implementation plan — every change is file-anchored, function-named, and tied to code that exists in 441 today.
**Non-goals:** no refactors outside the SpO₂ path; no engine changes; do not touch HR/HRV/cold/pressure feature paths.

**Document status:** this is now the single maintained SpO₂ implementation plan. The separate `docs/spo2_plan_review_and_v7_1_deltas.md` file is retained as historical review trace only; ongoing planning and status updates should happen here.

---

## 1. Implementation Overview (Short)

### 1.0 Implementation Status (updated 2026-04-25, production-hardening follow-up)

- [x] PR-1 payload/topic skeletons landed (`Spo2Payload`, `PpgPayload`, `Spo2TierEventPayload`, `Topics`, `TopicRegistry`, `Spo2TierEventFifo`)
- [x] PR-2 baseline store extension landed at the storage level (`ProfileStore` has dual-family keys, risk profile, EMA updates)
- [x] PR-3 sequencer landed (`Spo2Sequencer` exists and drives tiering/clear/reset paths)
- [x] PR-4 engine wiring landed in minimum viable form (`Spo2TierEventFifo`, direct `handleTierEvent`, `updateSpo2Profile`)
- [x] PR-5 `SkinTempService` bindings landed in minimum viable form (sequencer lifecycle, invalid/reset hooks, freshness gate, cadence constants)
- [x] PR-6 ruleset update landed (`spo2_drop_alert` removed, WARN/ALERT/EMERGENCY/CLEAR rules added, baseline-minus-4 derived feature added)
- [x] `activity.confidence` now exists on `ActivityPayload`; the runtime also ingests `activity.confidence` during SpO2 acquisition
- [x] PR-7 follow-through is now functionally complete for live runtime/debug plumbing:
  - `SkinTempService` writes live SpO2 to `DataRepository.latestSpo2`
  - sequencer tier callbacks write live events to `DataRepository.latestSpo2TierEvent`
  - `DataRepository.latestSpo2DebugState` now surfaces active baseline, family, altitude bucket, provisional, ring size, and last invalid reason
  - `PhysioAlertsActivity` debug card now renders the SpO2 debug state read-only
- [~] PR-8 follow-through is substantially expanded:
  - `CEPSimulationService` Phase 7 now executes reviewed finish scenarios for high-risk escalation, emergency corroboration, mixed-window WARN abstain, low-baseline WARN, long-outage clear, and dual-path transport safety
  - baseline-update abstain scenario for non-rest/non-walk states now runs in Phase 7 and enforces no baseline movement for `RUN`/`VEHICLE`/`UNKNOWN`
  - scenario assertions now emit deterministic per-scenario structures with explicit `expectedRuleIds`, `observedRuleIds`, expected/observed counts, missing IDs, unexpected IDs, and pass/fail
  - per-scenario assertion failures are now surfaced as structured `assertionFailures` and are included in pass/fail
  - a single aggregated artifact/report (`phase7_spo2_summary.json`) is emitted per Phase-7 run, and notification state now explicitly marks PASS vs FAIL for fast triage
  - checks are still service/harness-driven (not JUnit-enforced), so full test-suite automation remains the final deferred gap
- [x] Phase-7 execution logic now has a service-independent runner (`Spo2Phase7Runner`) and `CEPSimulationService` delegates to it for the reviewed scenario set
- [x] Reviewed baseline-abstain scenario is preserved as `SPO2_BASELINE_ABSTAIN_NON_REST_NON_WALK` with explicit baseline count/value invariants
- [x] Dead SpO2 tier FIFO transport path was removed from runtime code (`Spo2TierEventFifo` deleted, `CepEngine` field/write path removed)
- [x] Android instrumentation regression gate exists (`Spo2Phase7RunnerInstrumentedTest`) for reviewed SpO2 scenarios (execution still requires connected device/emulator)
- [~] Remaining work is now mostly verification hardening and documentation cleanup; implementation deltas are largely landed
- [x] `./gradlew assembleDebug` passed on branch `WEAR-441` during the implementation pass noted in this doc
 - [~] Current pass has implemented: Alert disjunction (`baseline-4 OR high-risk`) with provisional bootstrap handling, Warn upper-curve guard (removed hard 92–94 majority gate), paused-PPG sustain accounting fix, and live altitude bucket plumbing from pressure-derived estimate

### 1.0.1 Consolidated Audit Findings And Remaining Scope

The repo now has a real SpO2 implementation, and several high-priority v7.1 deltas have already landed. Remaining work is therefore the gap between the current implementation and the full reviewed target behavior, not a greenfield implementation.

Implemented from the reviewed deltas:

- Alert predicate now includes the PDF/v7.1 disjunction: sustained `<90` plus `(baseline-4 OR high-risk)` with provisional bootstrap fallback.
- Warn predicate now uses the upper-curve guard (`not yet <90`) instead of the earlier hard 92–94 majority gate.
- PPG-red sustain accounting now snapshots sustain at PPG pause time so paused acquisition time is not credited as valid sustain.
- Live altitude bucket plumbing is present and passed into the sequencer rather than hardcoding `altitudeBucket = 0`.
- Repository live-path plumbing is present for `latestSpo2` and `latestSpo2TierEvent`.
- Active-baseline mirror keys are now published alongside the legacy baseline mirror.
- `spo2_clear_any` hold-down is now 60 s.
- Phase-7 SpO2 simulation execution and pass/fail assertions now exist for the original six scenarios.

Still remaining:

- Full JUnit/test-suite enforcement for the reviewed Phase-7 scenarios is not yet landed; assertions are strong but runtime/harness-based.
- Connected-device execution of the new instrumentation regression gate is still pending in this environment.
- A fresh runtime-produced `phase7_spo2_summary.json` from an executed full Phase-7 service run was not captured in this pass.
- On-device validation of the SpO2 debug panel and full reset-state behavior is still required before merge.
- Wider CEP regression run (P1–P6 + all SpO2 scenarios under runtime conditions) is still recommended and not fully executed in this pass.

### 1.0.2 Definition Of "Done" For The Remaining SpO2 Work

SpO2 should not be considered complete until all of the following are true:

- live runtime logic matches the PDF-aligned v7.1 predicates for Warn / Alert / Emergency
- PPG-red validity accounting is honest across the paused-PGG acquisition window
- altitude-aware and family-aware baseline selection are working, not stubbed
- repository/UI/debug visibility is wired for latest value, latest tier event, and baseline state
- simulation coverage includes the reviewed SpO2 scenarios and asserts their expected outcomes
- this document is updated to mark completed tasks and remove stale "planned" wording for already-landed work

### 1.1 New components (five)

| Component | Location | Kind |
|---|---|---|
| `Spo2Sequencer` | `app/src/main/java/com/aitech/weartak_civ/service/Spo2Sequencer.kt` | plain Kotlin class, owned by `SkinTempService` |
| `Spo2TierEventPayload` | `app/src/main/java/com/aitech/weartak_civ/cep/model/payloads/Spo2TierEventPayload.kt` | `CepPayload` |
| `Spo2ValidReading` | nested in `Spo2Sequencer.kt` | immutable data class |
| `Spo2TierEventFifo` | `app/src/main/java/com/aitech/weartak_civ/cep/engine/Spo2TierEventFifo.kt` | bounded queue |
| `Spo2BaselineStore` | nested static in `ProfileStore.kt` OR new `app/.../cep/engine/Spo2BaselineStore.kt` | SharedPreferences wrapper |

### 1.2 Reused components (unchanged)

`SkinTempService` (acquisition), `CepBus` (transport), `FeatureStore` (incl. `doubleIfFresh`), `RuleEngine` + `TriggerTimer` (wall-clock sustain), `CepActionDispatcher` + `LogActionHandler` (dispatch, TAK, SMS, AlertRegistry), `ProfileStore` (extended, not replaced), `Topics` + `TopicRegistry` (one new entry).

### 1.3 Data flow

```
SkinTempService.spo2StrictListener
     status==2 ──► Spo2Sequencer.onValid(pct,ts,motionOk,ppgRedSustainedMs,statusOk)
     status==-6 ─► Spo2Sequencer.onInvalid(ts, reason=TIMEOUT)
     onError   ──► Spo2Sequencer.onInvalid(ts, reason=ERROR)
onIsDeviceWornEvent(false) ─► Spo2Sequencer.reset(reason=OFF_WRIST)

Spo2Sequencer
  └─► (a) calls CepEngine.handleTierEvent(Spo2TierEventPayload(tier, state, pct, ts))
      (b) writes mirror booleans to FeatureStore via CepEngine.ingest()
          spo2.tier_event_warn_edge / alert_edge / emergency_edge / *_clear
      (c) writes spo2.pct / spo2.status_ok / spo2.ppg_red_sustained via CepEngine.ingest()
      (d) emits a bus copy for observers / replay only

CepEngine.busCollector (existing Phase 3 subscriber)
  └─► windows.ingest(evt)  — existing path; SPO2_TIER_EVENT topic is not windowed
       Spo2TierEventFifo.offer(payload)  — observer/replay queue only

CepEngine.processTick (existing, no changes)
  └─► RuleEngine.processTick() reads spo2.tier_event_*_edge booleans via JsonLogic

cep_rules.json (three new rules, one deprecated stub)
  └─► LogActionHandler → AlertRegistry → DataRepository.addPhysioAndAutoGenAlertRequest(...)
     → TAK (isTak=true, priority 1/2), SMS on Emergency only (sendSms=true)
     → Vibration (5x, already present in LogActionHandler)
```

### 1.4 Five-line summary

(i) `SkinTempService` keeps acquisition identical, tunes three constants, gains a freshness gate on the 1 Hz emit loop, and instantiates `Spo2Sequencer`. (ii) `Spo2Sequencer` owns the consecutive-valid sample ring, tier predicates, and edge/clear emission. (iii) `ProfileStore` gains dual baseline families per altitude bucket plus a selected live baseline mirror for rules. (iv) `cep_rules.json` replaces the stub `spo2_drop_alert` with three activation-only tier-edge rules. (v) `CEPSimulationService` gets six new scenarios.

---

## 2. Exact Code Changes (File-Level Plan)

### 2.1 File: `app/src/main/java/com/aitech/weartak_civ/cep/model/payloads/Spo2Payload.kt`

- **Change type:** modify (extend fields; preserve primary ctor order for existing callers via default parameters)
- **Purpose:** carry sensor-health context alongside pct so the sequencer can validate without re-reading internal `SkinTempService` state.
- **Detailed changes:**
  - Keep `pct: Double` at position 0 and `ts: Long` at position 1 (binary-compatible with current call sites).
  - Add named optional fields (defaults provided so existing emitters compile):
    - `statusOk: Boolean = true` — Samsung `status == 2` observed.
    - `motionOk: Boolean = true` — derived at emission time from `lastActivityConfidence` + STILL.
    - `ppgRedSustainedMs: Long = 0L` — how long PPG-red amplitude has been ≥ threshold at this ts.
    - `ppgAmplitude: Float? = null` — snapshot for diagnostics.
    - `ppgQuality: Float? = null` — snapshot for diagnostics.
- **Dependencies:** `CepPayload` interface; `SkinTempService` will populate extended fields at emission time.
- **Out-of-scope:** do not add a `valid: Boolean` field — validity is a sequencer-owned decision, not a payload field.

### 2.2 File: `app/src/main/java/com/aitech/weartak_civ/cep/model/payloads/PpgPayload.kt`

- **Change type:** modify (extend)
- **Purpose:** surface a running "PPG-red sustained for ≥X ms" signal so the sequencer need not re-derive it, and so downstream features can consume it.
- **Detailed changes:**
  - Add optional field `ppgRedSustainedMs: Long = 0L` at end of primary ctor.
  - Add optional field `ts: Long = System.currentTimeMillis()` if not already present (verify against Spo2Payload idiom).
- **Dependencies:** `SkinTempService` updates its continuous-PPG emitter to compute `ppgRedSustainedMs` locally.

### 2.3 File: `app/src/main/java/com/aitech/weartak_civ/cep/model/payloads/Spo2TierEventPayload.kt`

- **Change type:** create
- **Purpose:** sequencer output; consumed by `CepEngine` to push mirror booleans into `FeatureStore` and feed `Spo2TierEventFifo`.
- **Structure:**
  - `enum class Spo2Tier { NONE, WARN, ALERT, EMERGENCY }`
  - `enum class Spo2EdgeState { ENTER, HELD, CLEAR }`
  - `data class Spo2TierEventPayload(val tier: Spo2Tier, val state: Spo2EdgeState, val pct: Double, val ts: Long, val reason: String? = null) : CepPayload`
- **Dependencies:** `CepPayload`.

### 2.4 File: `app/src/main/java/com/aitech/weartak_civ/cep/model/Topics.kt`

- **Change type:** modify
- **Detailed changes:**
  - Add `const val SPO2_TIER_EVENT = "spo2.tier_event"`.
- **Dependencies:** `TopicRegistry.kt` (see 2.5).

### 2.5 File: `app/src/main/java/com/aitech/weartak_civ/cep/model/TopicRegistry.kt`

- **Change type:** modify
- **Detailed changes:** register `Topics.SPO2_TIER_EVENT` in whatever table TopicRegistry maintains (verify exact shape at implementation time; based on repo idiom this is a `mapOf(topic to KClass<Payload>)` lookup).
- **Uncertainty flag:** if TopicRegistry is purely declarative and not referenced by the bus collector, no registration is required beyond Topics.kt. Confirm in PR-1.

### 2.6 File: `app/src/main/java/com/aitech/weartak_civ/cep/engine/Spo2TierEventFifo.kt`

- **Change type:** create
- **Purpose:** give the rule-eval loop a deterministic staging queue for tier events and diagnostics after the direct handler path has already updated `FeatureStore`.
- **Structure:**
  ```
  class Spo2TierEventFifo(private val capacity: Int = 32) {
      private val q = ArrayDeque<Spo2TierEventPayload>()   // guarded by this
      @Synchronized fun offer(e: Spo2TierEventPayload): Boolean
      @Synchronized fun drain(): List<Spo2TierEventPayload>
      @Synchronized fun peekLatest(): Spo2TierEventPayload?
      @Synchronized fun size(): Int
  }
  ```
- **Behaviour:** `offer` drops the oldest when full and logs `CEP_SPO2 FIFO_DROP old=<oldTs>`. `drain` returns a snapshot and clears. This is observability plumbing, not the authoritative transport path.
- **Dependencies:** `Spo2TierEventPayload`.

### 2.7 File: `app/src/main/java/com/aitech/weartak_civ/cep/engine/ProfileStore.kt`

- **Change type:** extend
- **Purpose:** persist SpO₂ baseline families by altitude bucket and surface the currently selected baseline mirror for rules; persist user risk profile flag.
- **Fields to add (SharedPreferences keys in existing "cep_profile"):**
  - `spo2_resting_baseline_pct_b0..b3: Float` (default 97.0f)
  - `spo2_walk_baseline_pct_b0..b3: Float` (default 97.0f)
  - `spo2_resting_baseline_sample_count_b0..b3: Int` (default 0)
  - `spo2_walk_baseline_sample_count_b0..b3: Int` (default 0)
  - `spo2_resting_baseline_provisional_b0..b3: Boolean` (default true)
  - `spo2_walk_baseline_provisional_b0..b3: Boolean` (default true)
  - `spo2_baseline_altitude_bucket: Int` (default 0)
  - `spo2_baseline_family: String` (default "resting"; values: "resting" | "walk")
  - `user_risk_profile: String` (default "default"; values: "default" | "trauma" | "high_risk")
- **Functions to add:**
  - `spo2Baseline(family: String, altitudeBucket: Int): Double`
  - `spo2BaselineProvisional(family: String, altitudeBucket: Int): Boolean`
  - `spo2BaselineAltitudeBucket(): Int`
  - `spo2BaselineFamily(): String`
  - `updateSpo2BaselinesEma(validPct: Double, nowMs: Long, altitudeBucket: Int, activityState: String, motionOk: Boolean): Boolean` — updates the matching family and returns `true` if the selected live mirror should be refreshed.
  - `resetSpo2Baselines()` — called on a long (>72 h) gap to re-anchor both families.
  - `userRiskProfile(): String`
  - `setUserRiskProfile(profile: String)` — validated against allowed set.
- **Dependencies:** none.
- **Uncertainty flag:** confirm no existing consumer of `"cep_profile"` prefs collides with the new keys.

### 2.8 File: `app/src/main/java/com/aitech/weartak_civ/service/Spo2Sequencer.kt`

- **Change type:** create
- **Purpose:** convert per-reading SpO₂ into tier edges, consecutive-valid predicate, hysteresis.
- **Public API:**
  ```
  class Spo2Sequencer(
      private val profile: ProfileStore,
      private val publish: (Spo2TierEventPayload) -> Unit,   // bound to CepEngine.handleTierEvent
      private val ingest: (path: String, value: Double, tsMs: Long) -> Unit,  // CepEngine.ingest wrapper
      private val config: Spo2SequencerConfig = Spo2SequencerConfig()
  ) {
      fun onValid(pct: Double, ts: Long, activityState: String, motionOk: Boolean, ppgRedSustainedMs: Long, statusOk: Boolean)
      fun onInvalid(ts: Long, reason: InvalidReason)
      fun reset(reason: ResetReason)     // off-wrist, service stop, manual
      fun snapshot(): Spo2SequencerSnapshot  // for debug
  }
  ```
- **Internal state (all guarded by a single `Mutex`):**
  - `ring: ArrayDeque<Spo2ValidReading>` (size ≤ `config.ringCap`, default 8)
  - `currentTier: Spo2Tier = NONE`
  - `lastEdgeTs: Long = 0L`
  - `lastHeldTs: Long = 0L`
  - `lastValidTs: Long = 0L`
  - `clearCandidateSince: Long? = null`
  - `invalidRunCount: Int = 0`
- **Full algorithm:** see §4.
- **Dependencies:** `ProfileStore`, `Spo2TierEventPayload`, `CepEngine.handleTierEvent` (via `publish` callback).

### 2.9 File: `app/src/main/java/com/aitech/weartak_civ/service/SkinTempService.kt`

- **Change type:** modify (targeted bindings; do not rearrange existing logic)
- **Detailed changes:**

  (a) Constants (companion or inline):
  - `SPO2_MIN_GAP_MS`: `60 * 60_000L` → `30_000L`.
  - `SPO2_BACKOFF_AFTER_TIMEOUT_MS`: `30 * 60_000L` → `5 * 60_000L`.
  - Add `SPO2_PPG_RED_SUSTAIN_REQUIRED_MS = 30_000L`.
  - Add `SPO2_EMIT_FRESHNESS_MS = 35_000L` (tiny margin over the 30 s sample cadence).

  (b) Fields:
  - `lastSpo2EmittedTs: Long = 0L`.
  - `ppgRedFirstContinuouslyOkSinceMs: Long = 0L` — updated by the continuous PPG callback when amplitude+quality cross threshold; reset to 0 when they drop.
  - `lateinit var spo2Sequencer: Spo2Sequencer` — instantiated in `onCreate` after `profileStore`.

  (c) Hooks:
  - In `onCreate` (after DI/DataRepository is available), instantiate `Spo2Sequencer` with:
    - `publish = { payload -> cepEngine.handleTierEvent(payload); scope.launch { if (!CepBus.tryEmit(CepEvent(Topics.SPO2_TIER_EVENT, payload, payload.ts))) CepBus.emit(...) } }`
    - `ingest = { path, value, ts -> cepEngine.ingest(path, value, ts) }`
  - In `spo2StrictListener.onDataReceived`, on `status == 2`:
    - Compute `motionOk = isStill && lastActivityConfidence >= ACTIVITY_STILL_CONFIDENCE_MIN`.
    - Compute `activityState` from the existing activity payload (`STILL`, `WALKING`, `LIGHT_ACTIVITY`, etc.) and pass it through to the sequencer.
    - Extend `ActivityPayload` with `confidence: Int` and publish it into `FeatureStore` as `activity.confidence` so the Alert rule has a real feature instead of a private service field.
    - Compute `ppgRedSustainedMs = if (ppgRedFirstContinuouslyOkSinceMs == 0L) 0L else (ts - ppgRedFirstContinuouslyOkSinceMs)`.
    - Call `spo2Sequencer.onValid(lastSpo2Pct!!, ts = ts, activityState, motionOk, ppgRedSustainedMs, statusOk = true)` **before** `stopSpo2Session("completed")` so the sequencer state is advanced inside the optical-contention window.
  - In `spo2StrictListener.onDataReceived`, on `status == -6`:
    - Call `spo2Sequencer.onInvalid(ts, reason = InvalidReason.TIMEOUT)` before `stopSpo2Session(...)`.
  - In `spo2StrictListener.onError`:
    - Call `spo2Sequencer.onInvalid(ts, reason = InvalidReason.ERROR)`.
  - In `onIsDeviceWornEvent(false)` branch:
    - Call `spo2Sequencer.reset(reason = ResetReason.OFF_WRIST)` and clear `ppgRedFirstContinuouslyOkSinceMs = 0L`.
  - After every successful `onValid(...)` call, call `cepEngine.updateSpo2Profile()` in the same acquisition turn so the FeatureStore mirror stays in sync with the live selected baseline.

  (d) `emitLatestCep()` freshness gate:
  - Before emitting the SpO₂ branch, require `lastSpo2Ts > lastSpo2EmittedTs && (now - lastSpo2Ts) <= SPO2_EMIT_FRESHNESS_MS`. On emit, set `lastSpo2EmittedTs = lastSpo2Ts`.
  - This kills the [89,89,89,89] spoof on the 1 Hz loop.

  (e) PPG-red continuous accounting:
  - Where the continuous PPG callback currently updates amplitude/quality, if both exceed the thresholds (amplitude ≥ PPG_AMP_MIN and quality ≥ PPG_QUALITY_MIN — verify exact thresholds currently in use at 441) and `ppgRedFirstContinuouslyOkSinceMs == 0L`, set it to the sample's ts; if either drops below, reset to 0. Emit `PpgPayload(..., ppgRedSustainedMs = now - ppgRedFirstContinuouslyOkSinceMs)`.

- **Dependencies:** `Spo2Sequencer`, `CepEngine.ingest`, `ProfileStore`, `CepBus`, existing Samsung SDK plumbing.

### 2.10 File: `app/src/main/java/com/aitech/weartak_civ/cep/engine/CepEngine.kt`

- **Change type:** modify (small additive)
- **Purpose:** route `Spo2TierEventPayload` into `Spo2TierEventFifo` and mirror edge booleans to `FeatureStore`; expose baseline/altitude to `FeatureStore` for the baseline−4% rule.
- **Detailed changes:**
  - Add field `val spo2TierFifo = Spo2TierEventFifo()`.
  - In the existing bus-subscription coroutine (`scope.launch { CepBus.events.collect { evt -> windows.ingest(...) } }`), add a branch that enqueues `Topics.SPO2_TIER_EVENT` into `spo2TierFifo` for observability/replay only. The authoritative FeatureStore write path is the direct callback from `Spo2Sequencer.publish` into `handleTierEvent(...)`; the bus copy must never be treated as the source of truth.
  - Add private `fun handleTierEvent(p: Spo2TierEventPayload)`:
    - For each tier in {WARN, ALERT, EMERGENCY}: clear the three edge booleans and three clear booleans (`spo2.tier_event_<t>_edge = false`, `spo2.tier_event_<t>_clear = false`).
    - If `p.state == ENTER`: set `spo2.tier_event_<p.tier>_edge = true` and `spo2.pct = p.pct` both at `p.ts`. Schedule a follow-up `scope.launch { delay(config.ruleEvalIntervalMs + 250); featureStore.put("spo2.tier_event_<tier>_edge", false) }` so the edge is a one-rule-eval pulse with a GC-safe margin.
    - If `p.state == CLEAR`: set `spo2.tier_event_<p.tier>_clear = true`, `spo2.tier_event_<p.tier>_edge = false`, and call `AlertRegistry.clear(ruleIdFor(p.tier))`. Schedule the same pulse decay on the clear key.
    - If `p.state == HELD`: update `spo2.pct` only (no edges).
  - Add a new `fun updateSpo2Profile()` symmetric to `updateHrProfile()`/`updatePressureProfile()`:
    - Reads `ProfileStore.spo2BaselineFamily()` and `spo2BaselineAltitudeBucket()` to select the current mirror, then writes:
      - `profile.spo2_baseline_pct`
      - `profile.spo2_baseline_altitude_bucket`
      - `profile.spo2_baseline_family`
      - `profile.spo2_baseline_provisional` (1.0 / 0.0 for the selected family)
      - `thresholds.spo2.warn_floor = profile.spo2_baseline_pct - 4.0` (UI mirror of the live Warn delta)
      - `thresholds.spo2.alert_pct = 90.0`
      - `thresholds.spo2.emergency_pct = 88.0`
      - `settings.spo2.consecutive_required = N` (from config; default 4)
      - `settings.spo2.emergency_consecutive_required = 2`
  - Call `updateSpo2Profile()` from `start()` and after any baseline-family refresh in `SkinTempService`.
- **Dependencies:** `Spo2TierEventPayload`, `Spo2TierEventFifo`, `AlertRegistry`, existing `FeatureStore`.

### 2.11 File: `app/src/main/java/com/aitech/weartak_civ/cep/engine/FeatureStore.kt`

- **Change type:** none (no source modifications)
- **Purpose:** `FeatureStore` already has `doubleIfFresh`, NaN guards, and null-safe reads — all of which the plan relies on. **No FeatureStore edits are required.** Recorded here explicitly so no one is tempted to add redundant helpers.

### 2.12 File: `app/src/main/res/raw/cep_rules.json`

- **Change type:** modify
- **Detailed changes:**
  1. **Delete** the existing `spo2_drop_alert` stub. The new rule ids are distinct, and rollback is a git revert away.
  2. Add three new rules in the `rules` array (exact JSON in §5).
  3. Add a small number of derived features to match the rule-JSON shape:
     - `features.spo2.last_pct_fresh` — passes `spo2.pct` through with a 30 s freshness derived-expression (or use `*_fresh` operator inline; see §5 note).
     - `features.spo2.baseline_minus_4` — `profile.spo2_baseline_pct − 4`.
- **Dependencies:** none.

### 2.13 File: `app/src/main/java/com/aitech/weartak_civ/service/simulation/CEPSimulationService.kt` (path approximate — confirm at PR time)

- **Change type:** modify
- **Detailed changes:**
  - Extend `ScenarioConfig` with:
    - `spo2Pattern: ((Int) -> Double?)? = null`
    - `baselinePct: Double = 97.0`
    - `highRiskContext: Boolean = false`
    - `ppgRedGood: Boolean = true`
    - `stillWithConfidence: Boolean = true`
    - `onWrist: Boolean = true`
  - Add six `Scenario` enum values: `SPO2_CLEAN_DESCENT`, `SPO2_STALE_READINGS`, `SPO2_OFF_WRIST_MID_DESCENT`, `SPO2_MOTION_SUPPRESSION`, `SPO2_STATUS_NOT_OK`, `SPO2_ALTITUDE_BUCKET_CHANGE`.
  - For each, provide the expected fire sequence as a list of `ExpectedEvent(ruleId, atSec, severity)` that the test harness asserts.
- **Dependencies:** existing simulation harness.

### 2.14 File: `app/src/main/java/com/aitech/weartak_civ/cep/engine/action/AlertRegistry.kt`

- **Change type:** verify public surface
- **Detailed changes:** confirm that `clear(ruleId: String)` is publicly callable from `CepEngine` (per v0.6 it is already). If visibility is `internal`, widen. No other modifications.

### 2.15 File: `app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt`

- **Change type:** modify (minor, optional for Phase 1)
- **Detailed changes:** add
  - `val latestSpo2: StateFlow<Double?>` (feeds UI)
  - `val latestSpo2TierEvent: StateFlow<Spo2TierEventPayload?>`
  - `suspend fun setUserRiskProfile(p: String)` that persists via `ProfileStore` and emits a `CepEngine.updateSpo2Profile()` refresh.
- **Dependencies:** `ProfileStore`, `CepEngine`.

---

## 3. Data Model Specification

### 3.1 `Spo2Payload` (final form on WEAR-441)

| Field | Type | Default | Source | Notes |
|---|---|---|---|---|
| `pct` | `Double` | — | `ValueKey.SpO2Set.SPO2` on Samsung SDK | clamp to [70,100] at emission; out-of-range treated as invalid at sequencer |
| `ts` | `Long` | `System.currentTimeMillis()` | Samsung data-point timestamp when available; wall-clock otherwise | |
| `statusOk` | `Boolean` | `true` | `status == 2` | |
| `motionOk` | `Boolean` | `true` | `isStill && lastActivityConfidence ≥ ACTIVITY_STILL_CONFIDENCE_MIN` | computed in `SkinTempService` at emission |
| `ppgRedSustainedMs` | `Long` | `0L` | from `ppgRedFirstContinuouslyOkSinceMs` | |
| `ppgAmplitude` | `Float?` | `null` | `lastPpgAmp` | diagnostics only |
| `ppgQuality` | `Float?` | `null` | `lastPpgQuality` | diagnostics only |

Binary-compat note: `pct` + `ts` remain at positions 0,1 so existing `Spo2Payload(pct, ts)` call sites compile unchanged.

### 3.2 `PpgPayload` (extended)

| Field | Type | Default | Source |
|---|---|---|---|
| `amplitude` | `Float` | — | existing |
| `quality` | `Float?` | `null` | existing |
| `wavelength` | `String?` | `null` | existing |
| `ppgRedSustainedMs` | `Long` | `0L` | `SkinTempService.ppgRedFirstContinuouslyOkSinceMs` |
| `ts` | `Long` | `System.currentTimeMillis()` | sample ts |

### 3.3 `Spo2TierEventPayload`

```
enum class Spo2Tier { NONE, WARN, ALERT, EMERGENCY }
enum class Spo2EdgeState { ENTER, HELD, CLEAR }

data class Spo2TierEventPayload(
    val tier: Spo2Tier,
    val state: Spo2EdgeState,
    val pct: Double,
    val ts: Long,
    val reason: String? = null    // e.g. "CLEAR_HYSTERESIS", "OFF_WRIST", "STALE"
) : CepPayload
```

### 3.4 `Spo2ValidReading` (internal to sequencer)

```
data class Spo2ValidReading(
    val pct: Double,
    val ts: Long
)
```

### 3.5 `Spo2SequencerConfig`

```
data class Spo2SequencerConfig(
    val ringCap: Int = 8,
    val consecutiveRequired: Int = 4,
    val emergencyConsecutiveRequired: Int = 2,
    val maxGapMs: Long = 60_000L,                // PDF "≤60 s apart"
    val warnDeltaFromBaseline: Double = 4.0,
    val alertPct: Double = 90.0,
    val emergencyPct: Double = 88.0,
    val freshnessMaxAgeMs: Long = 30_000L,
    val heldIntervalMs: Long = 30_000L,
    val clearHysteresisPct: Double = 1.0,
    val clearSustainMs: Long = 60_000L,
    val ppgRedSustainRequiredMs: Long = 30_000L,
    val pctClampMin: Double = 70.0,
    val pctClampMax: Double = 100.0,
    val traumaWarnDeltaFromBaseline: Double = 3.0,
    val traumaAlertPct: Double = 92.0,
    val traumaEmergencyPct: Double = 89.0,
    val traumaSustainDivisor: Int = 2
)
```

### 3.6 Derived features (where each is computed)

| Feature key | Computation | Where | Why |
|---|---|---|---|
| `spo2.pct` | mirrored from sequencer on each ENTER/HELD | `CepEngine.handleTierEvent` | makes `lte_key_fresh` usable in rules |
| `spo2.status_ok` | 1.0/0.0 from `SkinTempService` at emission | `CepEngine.ingest` from SkinTempService | rule-level health gate |
| `spo2.ppg_red_sustained_ms` | raw long from `SkinTempService` | `CepEngine.ingest` | for observability + optional rule use |
| `spo2.tier_event_warn_edge` | Boolean pulse | `CepEngine.handleTierEvent` on ENTER | primary WARN rule trigger |
| `spo2.tier_event_alert_edge` | same | same | ALERT trigger |
| `spo2.tier_event_emergency_edge` | same | same | EMERGENCY trigger |
| `spo2.tier_event_warn_clear` / `_alert_clear` / `_emergency_clear` | Boolean pulse | `CepEngine.handleTierEvent` on CLEAR | optional clear-side rules |
| `profile.spo2_baseline_pct` | selected live mirror | `CepEngine.updateSpo2Profile` | baseline comparator for rules |
| `profile.spo2_baseline_family` | selected family name | `CepEngine.updateSpo2Profile` | lets rules and debug UI know which comparator is active |
| `profile.spo2_baseline_provisional` | 1.0/0.0 | same | gate Warn while the selected family is warming up |
| `features.spo2.baseline_minus_4` | `profile.spo2_baseline_pct - 4` | derived JSON feature | direct rule comparison |
| `features.spo2.consecutive_valid` | **not used**; kept optional — the sequencer holds the truth | — | PDF forbids time-averaged substitutes; consecutive count is a sequencer invariant, not a FeatureStore number |
| `features.spo2.baseline_86400s`, `features.spo2.recent_120s`, `features.spo2.delta_pct` | keep as dashboard numerics only | existing JSON | **do not reference in new tier rules** |

**Explicit non-derived items (per PDF "NOT time-averaging"):** `features.spo2.recent_120s` and `features.spo2.delta_pct` remain in `cep_rules.json` for diagnostic telemetry **but the three new tier rules must not reference them.** The tier rules reference only `spo2.tier_event_*_edge`, `spo2.pct` (via `*_fresh`), `offbody.on_wrist`, `activity.state`, and `sleep.is_sleeping`.

---

### 3.7 **CRITICAL — Dual baseline families required by PDF §3**

**What v7 says (§2.7, §6.1, §6.3):** one baseline field `spo2_baseline_pct` and one EMA update path.

**What the PDF requires (§3):**
> For each user and altitude band:
> - **Resting_baseline_SpO2** — very low motion, HR near resting, valid SpO₂.
> - **Light_activity_baseline_SpO2** — steady walking / light work, HR ~20–40% above rest, valid SpO₂.
> - Rolling 7-day window; recompute daily; keep the baselines separate by altitude band.

The plan should keep the existing selected mirror `profile.spo2_baseline_pct` for rule simplicity, but the underlying store must track both families so the selected mirror can point at the right comparator for the current motion context.

**v7.1 fix:**

- Keep `profile.spo2_baseline_pct` as the currently selected live mirror for rules.
- Add `spo2_resting_baseline_*` and `spo2_walk_baseline_*` keys per altitude bucket in `ProfileStore`.
- Select the live mirror by activity context:
```kotlin
val baselineFamily = when (activityState) {
    "STILL" -> "resting"
    "WALKING", "LIGHT_ACTIVITY" -> "walk"
    else -> "resting"
}
val baseline = profile.spo2Baseline(baselineFamily, altitudeBucket)
val provisional = profile.spo2BaselineProvisional(baselineFamily, altitudeBucket)
```
- Keep the internal bootstrap as simple-mean-then-EMA if desired, but document it as an implementation detail for the rolling 7-day median approximation, not the external contract.

### 3.8 **IMPORTANT — Warn needs the upper-curve 92–94% guard without suppressing lower-baseline cases**

**What v7 says (§4.3 line 464):**
```kotlin
val warnPred = lastN.size == N && lastN.all { it <= warnTarget } && !provisional
```

**What the PDF §4.1 says:**
> Trigger Warn if:
> - `N >= 4` (about 2 minutes of valid readings).
> - For all readings in buffer: `SpO2 <= Baseline - 4%`.
> - And most readings (e.g. `>= 3/4`) are in the 92-94% range.

The plan needs both ideas: the baseline-drop predicate and the upper-curve guard. Do not hard-floor the warning threshold in a way that suppresses valid lower-baseline Warns.

**v7.1 fix — keep the baseline-drop predicate, add a soft upper-curve guard when it is applicable:**

```kotlin
val warnBandLo = 92.0
val warnBandHi = 94.0
val majorityThreshold = (N * 3 + 3) / 4   // ceil(3*N/4)
val warnBandApplies = warnTarget >= warnBandLo
val warnBandOk = !warnBandApplies || lastN.count { it in warnBandLo..warnBandHi } >= majorityThreshold

val warnPred = lastN.size == N
    && lastN.all { it <= warnTarget }
    && warnBandOk
    && !provisional
```

This preserves the PDF’s intent for normal baselines while avoiding false negatives when a user’s personal baseline sits lower or altitude shifts the mirror down.

### 3.9 **CRITICAL — Emergency is corroboration-based, not consecutive-based (PDF §4.3)**

**What v7 says (§4.3 line 462, §5.3 rule):**
```kotlin
val emergencyPred = lastEN.size == eN && lastEN.all { it < effectiveEmergencyPct() }
```
i.e. `2` consecutive valid readings `<88%`.

**What the PDF §4.3 says:**
> Trigger Emergency if ANY:
> - A valid reading `<88%`, plus either:
>   - Another valid `<90%` within ~2 min, or
>   - High-risk context (major trauma, suspected hypobaric O2 failure, extreme altitude mission).

This is a corroboration rule, not a consecutive-low rule.

**v7.1 fix — rewrite the Emergency predicate as a corroboration window:**

```kotlin
val emergencyWindowMs = 120_000L
val highRiskFlag = userRiskProfile() != "default"   // future context flags can be ORed in here

val veryLow = ring.withIndex().filter { it.value.pct < effectiveEmergencyPct() }
val corroboratedByTime = veryLow.any { low ->
    ring.any { sample ->
        sample.pct < effectiveAlertPct() &&
        sample.ts != low.value.ts &&
        kotlin.math.abs(sample.ts - low.value.ts) <= emergencyWindowMs
    }
}

val emergencyPred = veryLow.isNotEmpty() && (highRiskFlag || corroboratedByTime)
```

For phase 1, `userRiskProfile() != "default"` is enough to unlock the high-risk path; the trauma/extreme-altitude context booleans can be wired later without changing the predicate shape.

**Rule-side change (§5.3):** the Emergency rule JSON still fires on `spo2.tier_event_emergency_edge`; no extra predicates are required there. Do not add a still/confidence gate at the rule level for Emergency.

## 4. Sequencer Design (Critical)

### 4.1 Contract

- **Input APIs:** `onValid(pct, ts, activityState, motionOk, ppgRedSustainedMs, statusOk)`, `onInvalid(ts, reason)`, `reset(reason)`.
- **Outputs:** side-effect calls to `publish(Spo2TierEventPayload)` and `ingest(path, value, ts)`.
- **Thread-safety:** all three entry points acquire the same `Mutex` (`kotlinx.coroutines.sync.Mutex`) via `runBlocking`-free path — use `withLock` on a `coroutineScope` that `SkinTempService` provides, or guard with a `java.util.concurrent.locks.ReentrantLock` if called from non-coroutine SDK callbacks. Prefer `ReentrantLock` because Samsung callbacks are non-suspending.

### 4.2 Internal state (full)

```
ring: ArrayDeque<Spo2ValidReading>          // up to config.ringCap = 8
currentTier: Spo2Tier = NONE
lastEdgeTs: Long = 0
lastHeldEmitTs: Long = 0
lastValidTs: Long = 0
clearCandidateSince: Long? = null
invalidRunCount: Int = 0
lock = ReentrantLock()
```

### 4.3 Algorithm — `onValid(pct, ts, activityState, motionOk, ppgRedSustainedMs, statusOk)`

```
lock.lock()
try {
    // 1. Clamp and defensively reject out-of-range values.
    if (pct.isNaN() || pct < config.pctClampMin || pct > config.pctClampMax) {
        invalidRunCount += 1
        ingest("spo2.status_ok", 0.0, ts)
        return
    }

    // 2. Full validity gate (PDF §§3.1–3.3).
    val valid = statusOk
        && motionOk
        && ppgRedSustainedMs >= config.ppgRedSustainRequiredMs
    if (!valid) {
        ingest("spo2.status_ok", if (statusOk) 1.0 else 0.0, ts)
        invalidRunCount += 1
        maybeEmitClearOnInvalidity(ts, reason = "INVALID_GATE")
        return
    }

    // 3. Gap test: if prior valid reading is older than config.maxGapMs, reset the ring.
    if (lastValidTs != 0L && (ts - lastValidTs) > config.maxGapMs) {
        ring.clear()
        // do NOT clear currentTier here — tier changes only on predicate evaluation below
    }

    // 4. Append to ring; trim to cap.
    ring.addLast(Spo2ValidReading(pct, ts))
    while (ring.size > config.ringCap) ring.removeFirst()
    lastValidTs = ts
    invalidRunCount = 0

    // 5. Publish raw reading for rule-side freshness checks and dashboards.
    ingest("spo2.pct", pct, ts)
    ingest("spo2.status_ok", 1.0, ts)
    ingest("spo2.ppg_red_sustained_ms", ppgRedSustainedMs.toDouble(), ts)

    // 6. Update baseline if this reading qualifies (see §6).
    val selectedFamily = when (activityState) {
        "WALKING", "LIGHT_ACTIVITY" -> "walk"
        else -> "resting"
    }
    profile.updateSpo2BaselinesEma(pct, ts, altitudeBucket = currentAltitudeBucket(), activityState = activityState, motionOk = motionOk)

    // 7. Evaluate tier predicates on the ring.
    val baseline = profile.spo2Baseline(selectedFamily, currentAltitudeBucket())
    val provisional = profile.spo2BaselineProvisional(selectedFamily, currentAltitudeBucket())
    val warnTarget = baseline - effectiveWarnDelta()

    val N = if (userRiskProfile() == "trauma") max(2, config.consecutiveRequired / config.traumaSustainDivisor) else config.consecutiveRequired
    val lastN = ring.takeLast(N).map { it.pct }

    val warnBandLo = 92.0
    val warnBandHi = 94.0
    val majorityThreshold = (N * 3 + 3) / 4
    val warnBandApplies = warnTarget >= warnBandLo
    val warnBandOk = !warnBandApplies || lastN.count { it in warnBandLo..warnBandHi } >= majorityThreshold

    val highRiskFlag = userRiskProfile() != "default"
    val veryLow = ring.withIndex().filter { it.value.pct < effectiveEmergencyPct() }
    val corroboratedByTime = veryLow.any { low ->
        ring.any { sample ->
            sample.pct < effectiveAlertPct() &&
            sample.ts != low.value.ts &&
            kotlin.math.abs(sample.ts - low.value.ts) <= 120_000L
        }
    }

    val alertPred = lastN.size == N && lastN.all { it < effectiveAlertPct() }
    val warnPred  = lastN.size == N && lastN.all { it <= warnTarget } && warnBandOk && !provisional
    val emergencyPred = veryLow.isNotEmpty() && (highRiskFlag || corroboratedByTime)

    // Priority escalation: Emergency > Alert > Warn > None
    val targetTier = when {
        emergencyPred -> Spo2Tier.EMERGENCY
        alertPred     -> Spo2Tier.ALERT
        warnPred      -> Spo2Tier.WARN
        else          -> Spo2Tier.NONE
    }

    // 8. Tier transitions.
    if (targetTier != currentTier) {
        if (targetTier == Spo2Tier.NONE) {
            // Step 10 below owns CLEAR.
            beginClearCandidate(ts)
        } else {
            // Fire ENTER edge (even on bumps, e.g. WARN → ALERT, since each rule is activation-only).
            currentTier = targetTier
            lastEdgeTs = ts
            clearCandidateSince = null
            publish(Spo2TierEventPayload(targetTier, Spo2EdgeState.ENTER, pct, ts))
        }
    } else if (targetTier != Spo2Tier.NONE) {
        // 9. Emit HELD every heldIntervalMs while in tier (observability).
        if (ts - lastHeldEmitTs >= config.heldIntervalMs) {
            lastHeldEmitTs = ts
            publish(Spo2TierEventPayload(targetTier, Spo2EdgeState.HELD, pct, ts))
        }
    }

    // 10. Hysteresis clear: if pct is above (tierThreshold + clearHysteresisPct) for clearSustainMs,
    //     emit CLEAR. For WARN the threshold is warnTarget; for ALERT it's alertPct; for EMERGENCY it's emergencyPct.
    if (currentTier != Spo2Tier.NONE) {
        val clearThreshold = when (currentTier) {
            Spo2Tier.WARN      -> warnTarget + config.clearHysteresisPct
            Spo2Tier.ALERT     -> config.alertPct + config.clearHysteresisPct
            Spo2Tier.EMERGENCY -> config.emergencyPct + config.clearHysteresisPct
            Spo2Tier.NONE      -> Double.POSITIVE_INFINITY
        }
        if (pct >= clearThreshold) {
            if (clearCandidateSince == null) clearCandidateSince = ts
            else if (ts - clearCandidateSince!! >= config.clearSustainMs) {
                publish(Spo2TierEventPayload(currentTier, Spo2EdgeState.CLEAR, pct, ts, reason = "HYSTERESIS"))
                currentTier = Spo2Tier.NONE
                clearCandidateSince = null
            }
        } else {
            clearCandidateSince = null
        }
    }
} finally { lock.unlock() }
```

### 4.4 Algorithm — `onInvalid(ts, reason)`

```
lock.lock()
try {
    invalidRunCount += 1
    ingest("spo2.status_ok", 0.0, ts)

    // If we've missed > maxGapMs of valid readings, ring coherence is broken.
    if (lastValidTs != 0L && (ts - lastValidTs) > config.maxGapMs) {
        ring.clear()
        // Do NOT auto-clear tier here — tier clear only via hysteresis (4.3 step 10)
        // or reset(OFF_WRIST) / reset(LONG_SENSOR_OUTAGE) below.
    }

    // Long sensor outage policy: >5 min without a valid reading → force CLEAR if in tier.
    if (currentTier != Spo2Tier.NONE && lastValidTs != 0L && (ts - lastValidTs) > 5 * 60_000L) {
        publish(Spo2TierEventPayload(currentTier, Spo2EdgeState.CLEAR, Double.NaN, ts,
                reason = "LONG_SENSOR_OUTAGE"))
        currentTier = Spo2Tier.NONE
        clearCandidateSince = null
    }
} finally { lock.unlock() }
```

### 4.5 Algorithm — `reset(reason)`

```
lock.lock()
try {
    val now = System.currentTimeMillis()
    if (currentTier != Spo2Tier.NONE) {
        publish(Spo2TierEventPayload(currentTier, Spo2EdgeState.CLEAR, Double.NaN, now,
                reason = reason.name))
    }
    ring.clear()
    currentTier = Spo2Tier.NONE
    lastEdgeTs = 0
    lastHeldEmitTs = 0
    lastValidTs = 0
    clearCandidateSince = null
    invalidRunCount = 0
    ingest("spo2.status_ok", 0.0, now)
} finally { lock.unlock() }
```

### 4.6 Helper predicates

```
effectiveWarnDelta(): Double   = if (risk=="trauma") cfg.traumaWarnDeltaFromBaseline else cfg.warnDeltaFromBaseline
effectiveAlertPct(): Double    = if (risk=="trauma") cfg.traumaAlertPct     else cfg.alertPct
effectiveEmergencyPct(): Double= if (risk=="trauma") cfg.traumaEmergencyPct else cfg.emergencyPct
```

### 4.7 Edge-detection summary

- ENTER: emitted the instant the tier predicate becomes true on step 7 of `onValid`.
- HELD: emitted every `heldIntervalMs` while in tier (observability only; no rule consumes HELD).
- CLEAR: emitted either (a) on 60 s sustained recovery above `threshold + 1%`, (b) on 5 min sensor outage, (c) on `reset(OFF_WRIST | SERVICE_STOP | USER_MANUAL)`.

### 4.8 Failure handling matrix (inside sequencer)

| Event | Action |
|---|---|
| `pct` NaN / out-of-range | increment invalid count, no ring push, `status_ok=0` |
| `statusOk==false` | invalid gate, `status_ok=0`, increment invalid count |
| `motionOk==false` | invalid gate; Warn is still suppressed because the ring does not advance |
| `ppgRedSustainedMs < 30_000` | invalid gate |
| Gap to last valid > 60 s | ring cleared; predicates recomputed on next valid |
| Gap to last valid > 5 min while in-tier | force CLEAR |
| `reset(OFF_WRIST)` | force CLEAR + full state wipe |
| Sensor error | invalid count increment; no tier change unless long-outage rule fires |

---

## 5. CEP Rule Definitions

All three rules are **activation-only** and use the existing `type=log` action handler, which already handles TAK/AlertRegistry/vibration. The `monitor`/exit path is left minimal because tier clearing is authoritatively owned by the sequencer (via `AlertRegistry.clear(ruleId)` called from `CepEngine.handleTierEvent` on CLEAR). A monitor is included as belt-and-braces so misplaced state still exits via `CepEngine.checkAndClearAlerts()`.

**Rule IDs (stable, do not rename):**
- `spo2_warn_baseline_drop`
- `spo2_alert_sustained_hypoxemia`
- `spo2_emergency_severe_hypoxemia`
  - `spo2_drop_alert` is removed from the active ruleset

### 5.1 Warn rule

```json
{
  "id": "spo2_warn_baseline_drop",
  "description": "SpO2 WARN: N consecutive valid readings ≤ baseline-4% with an upper-curve 92-94% guard. On-wrist required; baseline must not be provisional.",
  "when": {
    "all": [
      { "gte_key": ["spo2.tier_event_warn_edge", 0.5] },
      { "gte_key": ["offbody.on_wrist", 0.5] },
      { "lte_key": ["profile.spo2_baseline_provisional", 0.5] },
      { "lte_key_fresh": ["spo2.pct", 100.0, 30] }
    ]
  },
  "sustained_for_sec": 0,
  "hold_down_sec": 300,
  "action": {
    "type": "log",
    "severity": "WARN",
    "message": "SpO2 WARN: {spo2.pct}% ≤ baseline-4% (baseline={profile.spo2_baseline_pct}%) for ≥N consecutive valid readings.",
    "tags": ["spo2", "warn", "baseline_drop"],
    "category": "CAT_SPO2_WARN",
    "isTak": true,
    "isLocal": true,
    "priority_from": "severity",
    "sendSms": false,
    "timeout": 120,
    "monitor": [
      { "feature": "spo2.pct", "exit_above_key": "features.spo2.baseline_minus_4", "hysteresis": 1.0 }
    ],
    "exit_sustain_sec": 60
  }
}
```

**Field-by-field explanation:**
- `gte_key["spo2.tier_event_warn_edge", 0.5]` — edge boolean (stored as 1.0/0.0). ≥0.5 is the Boolean-true convention already used for `offbody.on_wrist`.
- `gte_key["offbody.on_wrist", 0.5]` — standard on-wrist gate from the existing HR rules idiom.
- `lte_key["profile.spo2_baseline_provisional", 0.5]` — suppresses Warn while baseline is still bootstrapping (<20 qualifying readings).
- `lte_key_fresh["spo2.pct", 100.0, 30]` — returns null (fails) if `spo2.pct` is older than 30 s. The 100 upper literal is a no-op comparator whose purpose is to force the freshness check; **implementation note:** if the 441 operator signature is `lte_key_fresh[key, literal, maxAgeSec]`, this is idiomatic. If different, a dedicated `fresh` operator should be preferred; verify at PR time. If unavailable, derive `features.spo2.last_pct_fresh` as a `Derived` feature with `maxAgeSec: 30` and reference it as `{"ref": "features.spo2.last_pct_fresh", "is": true}`.
- `sustained_for_sec: 0` — the sustain has already happened inside the sequencer (consecutive-valid predicate); this is activation-only.
- `hold_down_sec: 300` — five-minute cooldown so repeat ENTERs on borderline fluctuations don't spam.
- `category: CAT_SPO2_WARN` — new category; confirm no collision with existing categories.
- `isTak: true` — goes through TAK via `LogActionHandler`'s TAK branch.
- `sendSms: false` — Warn does not SMS (open question §13; product decision).
- `monitor` — belt-and-braces exit; sequencer-owned CLEAR is primary.

### 5.2 Alert rule

```json
{
  "id": "spo2_alert_sustained_hypoxemia",
  "description": "SpO2 ALERT: N consecutive valid readings <90%. Requires STILL+confidence and on-wrist.",
  "when": {
    "all": [
      { "gte_key": ["spo2.tier_event_alert_edge", 0.5] },
      { "gte_key": ["offbody.on_wrist", 0.5] },
      { "eq_key": ["activity.state", "STILL"] },
      { "gte_key": ["activity.confidence", 50.0] },
      { "lte_key_fresh": ["spo2.pct", 90.0, 30] }
    ]
  },
  "sustained_for_sec": 0,
  "hold_down_sec": 300,
  "action": {
    "type": "log",
    "severity": "ALERT",
    "message": "SpO2 ALERT: sustained <90% ({spo2.pct}%) for ≥N consecutive valid readings.",
    "tags": ["spo2", "alert", "hypoxemia"],
    "category": "CAT_SPO2_ALERT",
    "isTak": true,
    "isLocal": true,
    "priority_from": "severity",
    "sendSms": false,
    "timeout": 180,
    "monitor": [
      { "feature": "spo2.pct", "exit_above": 91.0, "hysteresis": 1.0 }
    ],
    "exit_sustain_sec": 60
  }
}
```

**Key differences vs Warn:**
- `eq_key["activity.state", "STILL"] + gte_key["activity.confidence", 50]` — PDF requires corroboration for Alert; Emergency is corroborated inside the sequencer and does not need a still gate here.
- `lte_key_fresh["spo2.pct", 90.0, 30]` — reinforces the <90% invariant at rule time with freshness.
- `exit_above: 91.0` — hysteresis threshold; matches sequencer's `alertPct + clearHysteresisPct`.

### 5.3 Emergency rule

```json
{
  "id": "spo2_emergency_severe_hypoxemia",
  "description": "SpO2 EMERGENCY: corroborated severe hypoxemia (<88% plus <90% within 2 min or high-risk context). Requires on-wrist; motion gate relaxed.",
  "when": {
    "all": [
      { "gte_key": ["spo2.tier_event_emergency_edge", 0.5] },
      { "gte_key": ["offbody.on_wrist", 0.5] },
      { "lte_key_fresh": ["spo2.pct", 88.0, 30] }
    ]
  },
  "sustained_for_sec": 0,
  "hold_down_sec": 300,
  "action": {
    "type": "log",
    "severity": "EMERGENCY",
    "message": "SpO2 EMERGENCY: severe hypoxemia ({spo2.pct}%) — dispatch now.",
    "tags": ["spo2", "emergency", "severe_hypoxemia"],
    "category": "CAT_SPO2_EMERGENCY",
    "isTak": true,
    "isLocal": true,
    "priority_from": "severity",
    "sendSms": true,
    "timeout": 300,
    "monitor": [
      { "feature": "spo2.pct", "exit_above": 89.0, "hysteresis": 1.0 }
    ],
    "exit_sustain_sec": 120
  }
}
```

**Key differences:**
- Motion gate omitted at rule level (the sequencer's invalid gate already discards motion-corrupted samples, and Emergency is corroboration-based inside the sequencer).
- `sendSms: true` — only Emergency SMS.
- `exit_sustain_sec: 120` — slower auto-clear; deliberate, to keep ops in the loop.

### 5.4 Optional clear rules (operator visibility)

These are passive log-only rules (not TAK, no SMS) that fire on the clear pulses so the operator timeline shows recoveries:

```json
{
  "id": "spo2_clear_any",
  "when": {
    "any": [
      { "gte_key": ["spo2.tier_event_warn_clear", 0.5] },
      { "gte_key": ["spo2.tier_event_alert_clear", 0.5] },
      { "gte_key": ["spo2.tier_event_emergency_clear", 0.5] }
    ]
  },
  "sustained_for_sec": 0,
  "hold_down_sec": 30,
  "action": {
    "type": "log",
    "severity": "INFO",
    "message": "SpO2 tier cleared (pct={spo2.pct}%).",
    "tags": ["spo2", "clear"],
    "isTak": false,
    "isLocal": true
  }
}
```

### 5.5 Removal of stub

Delete the existing `spo2_drop_alert` stub from `cep_rules.json`. The three new rules have distinct ids, so rollback remains a git revert rather than a special tombstone rule.

---

## 6. Baseline System

### 6.1 Storage (ProfileStore, SharedPreferences `"cep_profile"`)

| Key | Type | Default | Notes |
|---|---|---|---|
| `spo2_resting_baseline_pct_b0..b3` | Float | 97.0f | resting family per altitude bucket |
| `spo2_walk_baseline_pct_b0..b3` | Float | 97.0f | walk family per altitude bucket |
| `spo2_resting_baseline_sample_count_b0..b3` | Int | 0 | bootstrap count for resting family |
| `spo2_walk_baseline_sample_count_b0..b3` | Int | 0 | bootstrap count for walk family |
| `spo2_resting_baseline_provisional_b0..b3` | Bool | true | resting family warming state |
| `spo2_walk_baseline_provisional_b0..b3` | Bool | true | walk family warming state |
| `spo2_resting_baseline_updated_ts_b0..b3` | Long | 0L | last resting-family qualifying write per bucket |
| `spo2_walk_baseline_updated_ts_b0..b3` | Long | 0L | last walk-family qualifying write per bucket |
| `spo2_baseline_updated_ts` | Long | 0L | last selected-mirror refresh |
| `spo2_baseline_altitude_bucket` | Int | 0 | current altitude bucket mirror |
| `spo2_baseline_family` | String | "resting" | selected family mirror (`resting` or `walk`) |
| `user_risk_profile` | String | "default" | "default" \| "trauma" \| "high_risk" |

### 6.2 Update eligibility

Each qualifying reading updates exactly one family. The selected family is driven by the current activity state; the other family is left untouched.

A reading qualifies for baseline update **iff** all of:
- `statusOk == true`
- `motionOk == true` (STILL + conf ≥ 50)
- `onWrist == true` (read from FeatureStore `offbody.on_wrist ≥ 0.5`)
- `sleep.is_sleeping != true` (exclude sleep readings; avoid drifting baseline toward sleep-apnea users)
- `ppgRedSustainedMs ≥ 30_000`
- `pct` in `[85.0, 100.0]` (excludes sensor glitches at either end)
- `activityState` selects one family:
  - `STILL` -> `resting`
  - `WALKING` or `LIGHT_ACTIVITY` -> `walk`
  - anything else -> `resting`
- Altitude bucket is part of the key, so bucket changes naturally switch the mirror instead of contaminating an existing bucket.

### 6.3 EMA formula

For the bootstrap phase (`sample_count < 20`):
```
new_baseline = ((old_baseline * sample_count) + pct) / (sample_count + 1)
sample_count += 1
if (sample_count >= 20) provisional = false
```

For steady state (`sample_count >= 20`):
```
alpha = 2.0 / (N + 1)       // N = 288 (equiv. to ~24 h cap at 5 min cadence)
new_baseline = alpha * pct + (1 - alpha) * old_baseline
```

### 6.4 Cold-start behaviour

- Default selected mirror is 97% until the selected family accumulates 20 qualifying readings.
- `provisional = true` suppresses the Warn rule by virtue of `lte_key["profile.spo2_baseline_provisional", 0.5]` failing.
- Alert (<90%) and Emergency (<88%) are independent of baseline and fire regardless of provisional state.

### 6.5 Altitude handling

- Pressure-derived altitude can come from existing `features.pressure.*` or a new `AltitudePayload` (open question §13).
- On altitude-bucket change, switch the selected mirror to that bucket's family keys. Each bucket maintains its own counts and provisional flag, so a new bucket starts cold without corrupting the previous bucket's history.
- **Implementation note:** if altitude is not yet wired, Phase 1 of the implementation hard-codes bucket = 0 and defers bucket-aware selection to Phase 2. This is safe because Alert/Emergency are altitude-independent; only Warn shifts with altitude.

### 6.6 Re-anchor on long gap

On a gap > 72 h with no qualifying readings: reset both families in the current altitude bucket to `sample_count=0` and `provisional=true`, while leaving the last stored baseline values in place. On the next 20 qualifying readings, fresh seeds begin for whichever family is active.

---

## 7. Sensor Coordination Logic

### 7.1 PPG continuous + SpO₂ on-demand interaction (already correct in `SkinTempService`)

The existing logic is preserved verbatim:
- `pausePpgContinuousForSpo2()` is called immediately before `spo2Tracker.setEventListener(spo2StrictListener)` and the 30 s hard-timeout handler (`spo2TimeoutRunnable`).
- On `status == 2` OR `status == -6` OR `onError`, the service calls `stopSpo2Session(reason)` → `unsetEventListener` → `resumePpgContinuousAfterSpo2`.
- `MeasurementState` state machine transitions: `IDLE → SPO2_RUNNING → PPG_RUNNING → IDLE`.

**New:** all three exit paths above **also** notify the sequencer (`onValid` / `onInvalid(TIMEOUT)` / `onInvalid(ERROR)`) *before* the `stopSpo2Session` call so the sequencer sees the sample within the optical window and downstream emit happens before PPG is resumed.

### 7.2 Contention handling

Unchanged from 378/441. The plan does not introduce a coordinator class. The single service owns the optical stack.

### 7.3 SpO₂ request cadence (SkinTempService changes)

| Constant | Current | New | Rationale |
|---|---|---|---|
| `SPO2_TIMEOUT_MS` | 30_000L | 30_000L (unchanged) | PDF-defined measurement window |
| `SPO2_MIN_GAP_MS` | `60 * 60_000L` (1 h) | `30_000L` (30 s) | satisfies "≤60 s between readings" with margin |
| `SPO2_BACKOFF_AFTER_TIMEOUT_MS` | `30 * 60_000L` (30 min) | `5 * 60_000L` (5 min) | single timeout should not silence for half an hour |

### 7.4 Trigger conditions (unchanged from existing `startSpo2ThenPpgCycle`)

- `deviceWorn == true`
- `isStill && lastActivityConfidence >= ACTIVITY_STILL_CONFIDENCE_MIN`
- `(nowMs - lastSpo2AttemptElapsedMs) >= baseReadyAt`

### 7.5 Suppression conditions

- `deviceWorn == false` → full service teardown (existing behaviour) + `sequencer.reset(OFF_WRIST)`.
- `!isStill || confidence < threshold` → skip; do not advance state.
- In backoff window → skip.

### 7.6 Retry logic

- On `onError`: state returns to IDLE; no immediate retry; natural next attempt after `SPO2_MIN_GAP_MS`.
- On three consecutive timeouts within 15 minutes: trigger `SPO2_BACKOFF_AFTER_TIMEOUT_MS`. **Addition:** after 3 back-to-back backoffs (i.e. 15 minutes of nothing), log `[SPO2] optical_contention_suspected` and notify UI. (Open question §13 on whether to surface a user-facing banner.)

---

## 8. Timing Model

All timings are wall-clock. 441's `TriggerTimer` uses `conditionTrueStartMs` so sustain is rate-independent.

| Knob | Value | Owner | Rationale |
|---|---|---|---|
| `rule_eval_interval_ms` | 500 | `cep_rules.json` → `engine.rule_eval_interval_ms` | tight enough to catch edge pulses, loose enough to not burn CPU |
| `window_tick_ms` | 1000 | `cep_rules.json` → `engine.window_tick_ms` | unchanged from current 441 shape; SpO₂ does not need sub-second windows |
| `SPO2_EMIT_FRESHNESS_MS` | 35_000 | `SkinTempService` | 30 s sample cadence + 5 s margin |
| `Spo2SequencerConfig.freshnessMaxAgeMs` | 30_000 | sequencer | matches PDF |
| `Spo2SequencerConfig.maxGapMs` | 60_000 | sequencer | PDF "≤60 s between readings" |
| `Spo2SequencerConfig.consecutiveRequired` | 4 | sequencer / `settings.spo2.consecutive_required` | default N for WARN / ALERT |
| `Spo2SequencerConfig.emergencyConsecutiveRequired` | 2 | sequencer / `settings.spo2.emergency_consecutive_required` | reduced N allowed for EMERGENCY per PDF |
| `Spo2SequencerConfig.heldIntervalMs` | 30_000 | sequencer | HELD observability beat |
| `Spo2SequencerConfig.clearHysteresisPct` | 1.0 | sequencer | 88 → clears at 89; 90 → clears at 91; baseline-4 → clears at (baseline-3) |
| `Spo2SequencerConfig.clearSustainMs` | 60_000 | sequencer | one full sample-cycle of recovery |
| `Spo2SequencerConfig.ppgRedSustainRequiredMs` | 30_000 | sequencer | PDF 30 s PPG-red gate |
| `LogActionHandler` throttle — WARN | 5_000 | `CepActionDispatcher` | existing default; SpO₂ WARN inherits |
| `LogActionHandler` throttle — ALERT | 10_000 | existing default | |
| `LogActionHandler` throttle — EMERGENCY | 15_000 | existing default | deliberately short to avoid suppressing legitimate Emergency re-entries |
| Rule `hold_down_sec` — WARN/ALERT | 300 | rule JSON | 5-min cooldown on same-tier re-entry |
| Rule `hold_down_sec` — EMERGENCY | 300 | rule JSON | same; operator-facing is the 15 s dispatcher throttle for immediacy |
| Rule `sustained_for_sec` — all three | 0 | rule JSON | sustain is inside the sequencer; rule is activation-only |
| Long sensor outage → forced CLEAR | 300_000 | sequencer | 5 min without any valid reading |
| Baseline re-anchor | 72 × 3_600_000 | ProfileStore | 72 h gap resets sample count |
| Baseline altitude bucket stabilisation | 600_000 | ProfileStore | 10 min after bucket change before resuming updates |

### 8.1 Edge pulse decay

When `CepEngine.handleTierEvent` writes `spo2.tier_event_<tier>_edge = true`, it schedules a write of `= false` after `config.ruleEvalIntervalMs + 250` ms (750 ms). This gives the rule one eval cycle plus scheduler slack, then clears the boolean. Alternative: use a single-shot Boolean that `RuleEngine` auto-consumes — prefer the explicit decay for debuggability.

### 8.2 Dispatcher throttle interaction

Dispatcher throttle is per (ruleId, action type, severity, tags hash). Because Warn/Alert/Emergency are distinct ruleIds, tier escalation (Warn → Alert) fires both edges; the 5 s WARN throttle and 10 s ALERT throttle do not cross-suppress. Confirm by scenario §11.3 `SPO2_CLEAN_DESCENT`.

---

## 9. Failure Modes & Handling

| # | Failure mode | Detection | Mitigation | Expected behaviour |
|---|---|---|---|---|
| F1 | Stale SpO₂ value in `FeatureStore` | `lte_key_fresh` on rule side returns null | rule predicate fails silently | no fire; `CEP_SPO2 stale_pct` log |
| F2 | Missing PPG (red channel absent) | `ppgRedSustainedMs < 30_000` at sequencer | sample marked invalid at §4.3 step 2 | ring not advanced; tier unchanged |
| F3 | Motion artefact (walking mid-measurement) | `motionOk == false` at sequencer | sample marked invalid | ring not advanced; Warn also suppressed at rule level for its own reasons |
| F4 | Off-wrist | `onIsDeviceWornEvent(false)` in SkinTempService | `sequencer.reset(OFF_WRIST)`; service tears down trackers | force CLEAR if in tier; all six edge/clear booleans at 0 |
| F5 | Baseline not ready | `profile.spo2_baseline_provisional == 1.0` | Warn rule fails on `lte_key["profile.spo2_baseline_provisional", 0.5]` | Warn suppressed; Alert/Emergency still fire |
| F6 | Sensor returns 0 or NaN | `pct < 70 \|\| pct > 100 \|\| pct.isNaN()` at sequencer | invalid count increment; `status_ok = 0.0` | no ring push; sequencer does not emit; `CEP_SPO2 OOR_PCT pct=<v>` log |
| F7 | Samsung status ≠ 2 | `statusOk == false` payload | invalid gate | same as F2 |
| F8 | Long optical contention (3× timeouts) | `lastSpo2EndedAsTimeout == true` three times within 15 min | `SPO2_BACKOFF_AFTER_TIMEOUT_MS = 5 min`; suggest re-seating | no SpO₂ readings for 5 min; sequencer may emit LONG_SENSOR_OUTAGE CLEAR after 5 min; Warn/Alert/Emergency cannot fire |
| F9 | 1 Hz SpO₂ emit spoof | pre-fix symptom: [89,89,89,89] from one reading | `emitLatestCep` freshness gate `lastSpo2Ts > lastSpo2EmittedTs` | one `Spo2Payload` per acquisition; sequencer advances by exactly one step per reading |
| F10 | Altitude change mid-window | bucket mismatch in `ProfileStore.updateSpo2BaselinesEma` | switch mirror to the matching bucket family; do not contaminate other buckets | baseline family stays isolated per bucket; Warn suppresses only while that bucket family is provisional |
| F11 | `CepBus` overflow (DROP_OLDEST) | 512-slot buffer fills | `Spo2TierEventFifo` sidecar stages events after the direct handler path | tier events remain observable even if the bus drops unrelated traffic |
| F12 | Concurrent callbacks from Samsung SDK | sequencer entry from two threads | `ReentrantLock` around sequencer state | serialised; no ring corruption |
| F13 | NaN in `features.spo2.delta_pct` (cold start, baseline=0) | existing FeatureStore NaN guard | `FeatureStore.put` logs and retains last valid | tier rules do not reference delta_pct; stub `spo2_drop_alert` is deleted |
| F14 | Sleep-time suppression | `sleep.is_sleeping == true` in baseline eligibility gate | baseline update skipped; tier rules still evaluate | no baseline drift from sleep apnea users; Emergency still fires |
| F15 | High-risk user (trauma) gets default thresholds | `profile.user_risk_profile == "default"` when it should be "trauma" | operator must set via DataRepository | threshold-shift only applies when profile value is "trauma" or "high_risk" |
| F16 | Rule eval misses edge pulse | decay window < eval interval | pulse decay is `ruleEvalIntervalMs + 250` | edge visible for exactly 1 eval cycle with scheduler slack |
| F17 | Rapid tier-down (Alert → NONE skipping CLEAR) | sequencer reset during in-tier | `sequencer.reset()` publishes CLEAR first | AlertRegistry entry cleared via `AlertRegistry.clear(ruleId)` |
| F18 | SharedPreferences write contention | concurrent baseline update + user profile set | ProfileStore uses `edit().apply()` idiom already | last-writer-wins; no data loss expected |

---

## 10. Observability & Debugging

### 10.1 Log tags & messages

Use tag `CEP_SPO2` everywhere inside the sequencer and `CepEngine.handleTierEvent`. Existing tag `CEP` is used for general engine output; keep it for rule-eval diagnostics.

| Origin | Level | Tag | Message template |
|---|---|---|---|
| `Spo2Sequencer.onValid` (ring push) | D | CEP_SPO2 | `push pct=%.1f ts=%d ring=%d/lastValid=%d gap=%d` |
| `Spo2Sequencer.onValid` (invalid gate) | D | CEP_SPO2 | `invalid statusOk=%b motionOk=%b ppgSust=%d clampOk=%b` |
| `Spo2Sequencer.onValid` (tier enter) | I | CEP_SPO2 | `EDGE tier=%s pct=%.1f baseline=%.1f ring=[%s]` |
| `Spo2Sequencer.onValid` (tier held) | D | CEP_SPO2 | `HELD tier=%s pct=%.1f` |
| `Spo2Sequencer.onValid` (clear pending) | D | CEP_SPO2 | `clear_pending tier=%s pct=%.1f since=%d` |
| `Spo2Sequencer.onValid` (clear fire) | I | CEP_SPO2 | `CLEAR tier=%s pct=%.1f reason=%s` |
| `Spo2Sequencer.onInvalid` | D | CEP_SPO2 | `onInvalid reason=%s run=%d` |
| `Spo2Sequencer.reset` | I | CEP_SPO2 | `reset reason=%s wasTier=%s` |
| `SkinTempService.emitLatestCep` (freshness gate block) | V | CEP_SPO2 | `emit_blocked stale lastTs=%d emittedTs=%d` |
| `ProfileStore.updateSpo2BaselinesEma` | D | CEP_SPO2 | `baseline_ema pct=%.1f family=%s bucket=%d new=%.3f count=%d provisional=%b` |
| `ProfileStore.updateSpo2BaselinesEma` (skipped: non-qualifying) | D | CEP_SPO2 | `baseline_skip family=%s bucket=%d reason=%s` |
| `CepEngine.handleTierEvent` | I | CEP_SPO2 | `handle tier=%s state=%s pct=%.1f ts=%d` |
| `CepEngine.handleTierEvent` (edge decay) | V | CEP_SPO2 | `edge_decay key=%s` |
| `Spo2TierEventFifo.offer` (drop) | W | CEP_SPO2 | `FIFO_DROP oldTs=%d newTs=%d size=%d` |

### 10.2 Metrics (structured JSON lines to existing log-to-file path)

Use `writeToLogFileLocation(logFile, jsonLine)` (already present in `SkinTempService`). Emit one JSON line per minute with:

```
{"t":<now>,"spo2.valid_count_1m":<int>,"spo2.invalid_count_1m":<int>,
 "spo2.tier":"<NONE|WARN|ALERT|EMERGENCY>","spo2.ring_size":<int>,
 "spo2.baseline":<float>,"spo2.provisional":<bool>,"spo2.last_valid_ts":<ts>,
 "spo2.ppg_red_sustained_ms":<long>,"spo2.edges_1m":{"warn":<int>,"alert":<int>,"emergency":<int>},
 "spo2.clears_1m":{"warn":<int>,"alert":<int>,"emergency":<int>}}
```

### 10.3 Debug flags

Add to `CepConfig` (SharedPreferences-backed via DataRepository) — **optional**, default OFF:

- `debug.spo2.verbose_sequencer` → lifts most D-level logs to I.
- `debug.spo2.fake_baseline` → overrides `profile.spo2_baseline_pct` with a developer-supplied literal.
- `debug.spo2.disable_provisional_suppression` → forces `profile.spo2_baseline_provisional = 0.0` so Warn can fire before 20 readings (useful for 9.4 smoke tests).
- `debug.spo2.force_still` → forces `motionOk = true` in sequencer (for bench tests on still desk).

### 10.4 Tracing sensor → alert

Given a reported alert at time T, trace path:
1. `CEP_SPO2 handle tier=ALERT state=ENTER pct=89.3 ts=T` in `CepEngine.handleTierEvent` logs.
2. Preceding `CEP_SPO2 EDGE tier=ALERT pct=89.3 baseline=97.0 ring=[91,90,89,89]` in sequencer logs.
3. Preceding 4× `CEP_SPO2 push pct=... ts=... ring=X/lastValid=Y` push lines (one per valid acquisition).
4. For each push, preceding `[SPO2] Completed status=2 spo2=<pct>` line from `spo2StrictListener` in `SkinTempService` logs.
5. For each SpO₂ acquisition, preceding `[MEAS] Cycle start → SPO2_RUNNING` line.
6. Rule JSON dispatch path: `[CEP][ALERT][spo2_alert_sustained_hypoxemia] SpO2 ALERT: ...` from `LogActionHandler`.

A single `grep -nE "CEP_SPO2|\[SPO2\]|spo2_(warn|alert|emergency)_" logfile.txt` gives the full timeline.

### 10.5 In-app debug surface

Extend `PhysioAlertsActivity` or equivalent debug screen (see `DataRepository` changes §2.15) with a read-only panel:

- Current tier + since-when.
- Ring contents (last 8 pct + ts).
- Baseline + sample_count + provisional flag.
- Last invalid reason + count (last 5 min).
- `Spo2TierEventFifo.size()`.

This panel is invaluable for field debug and costs ~30 lines of Compose.

---

## 11. Testing Plan

### 11.1 Unit tests (JVM, JUnit)

Target module: `app/src/test/java/com/aitech/weartak_civ/`

#### 11.1.1 `Spo2SequencerTest`

| # | Scenario | Given | When | Then |
|---|---|---|---|---|
| S1 | Clean descent to Alert | baseline=97, provisional=false, activityState=STILL; 4× readings [91,90,89,89] each 30 s apart, motionOk, statusOk, ppgSust=30_000 | onValid each | exactly 1 ENTER(ALERT) after 4th reading; 0 ENTER(WARN) |
| S2 | Warn then Alert bump | baseline=97, provisional=false, activityState=STILL; readings [93,92,92,92] then [90,89,89,89] | onValid stream | ENTER(WARN) at reading 4; ENTER(ALERT) at reading 8 |
| S3 | Emergency from corroboration | baseline=97, activityState=STILL; readings [87,89] 30 s apart | onValid stream | ENTER(EMERGENCY) at reading 2 |
| S4 | Provisional suppresses Warn | baseline=97, provisional=true, activityState=STILL; readings as S1 but hovering at warn band | onValid stream | 0 ENTER(WARN); ENTER(ALERT) still fires if crossed |
| S5 | Motion kills sample | activityState=WALKING, motionOk=false throughout | onValid stream (would otherwise Alert) | 0 ENTERs; status_ok reads 0 |
| S6 | PPG-red missing | activityState=STILL, ppgSust=0 | onValid stream | 0 ENTERs |
| S7 | Gap reset | activityState=STILL; 4× ok readings, then 90 s gap, then 3 ok at Alert | onValid stream | no ENTER — gap clears ring; need another N to accumulate |
| S8 | OOR pct | pct=0 or 150 | onValid | no push; status_ok=0; invalid_count++ |
| S9 | Hysteresis clear | ENTER(ALERT), then 90 readings at 92 (above alertPct+1=91) for 60 s | onValid stream | CLEAR with reason=HYSTERESIS; tier back to NONE |
| S10 | Long outage clear | ENTER(ALERT), then 6 min of onInvalid | onInvalid stream | CLEAR with reason=LONG_SENSOR_OUTAGE |
| S11 | Off-wrist clear | ENTER(ALERT), then reset(OFF_WRIST) | reset | CLEAR with reason=OFF_WRIST; state fully reset |
| S12 | Trauma profile threshold shift | user_risk_profile=trauma, readings at 91, 91, 91, 91 | onValid stream | ENTER(ALERT) fires (because traumaAlertPct=92); default profile would not |
| S13 | Thread-safety | concurrent onValid from 2 threads | pytest-like stress | no ring corruption (size ≤ cap; no duplicate ENTER) |

#### 11.1.2 `ProfileStoreSpo2Test`

- P1 Bootstrap simple-mean: first 20 readings of 97 → baseline=97, provisional→false at count=20.
- P2 EMA steady state: baseline=97, pct=94 → new baseline between 94 and 97, within expected alpha range.
- P3 Altitude bucket skip: storedBucket=0, currentBucket=1 → update rejected.
- P4 Sleep exclusion: sleep.is_sleeping=true → update rejected.
- P5 OOR pct rejection: pct=82 → update rejected.
- P6 72 h re-anchor: gap>72 h → sample_count=0, provisional=true.

#### 11.1.3 `Spo2TierEventFifoTest`

- F1 offer up to capacity → all retained.
- F2 offer when full → oldest dropped; `drain` returns newest 32.
- F3 drain clears.
- F4 peekLatest returns most-recent without mutation.

### 11.2 Integration tests (Robolectric or emulator-backed)

Target: `app/src/androidTest/java/...`

- I1 `SkinTempService` emits one Spo2Payload per acquisition (freshness gate honoured). Inject fake SpO₂ via reflection into `lastSpo2Pct` and `lastSpo2Ts`; tick the 1 Hz emit loop twice; assert exactly one `CepBus` event.
- I2 `CepEngine.handleTierEvent` on ENTER → `FeatureStore["spo2.tier_event_alert_edge"]` is `true` for one rule-eval cycle, then `false`. Assert by polling FeatureStore at 100 ms cadence.
- I3 Full wire-up: fake sensor stream → Spo2Payload → sequencer → TierEventPayload → FeatureStore booleans → RuleEngine fires → `AlertRegistry` has active alert.
- I4 Freshness enforcement at rule level: write `spo2.pct=85` 60 s ago; rule `lte_key_fresh["spo2.pct", 88, 30]` returns false; rule does not fire.

### 11.3 Simulation tests (`CEPSimulationService`)

Six scenarios, each asserting exact fire sequence:

| Scenario | Pattern (pct by tick) | Expected fire sequence |
|---|---|---|
| `SPO2_CLEAN_DESCENT` | `activityState=STILL`; `t=0..5: 97; t=6..9: [95, 92, 90, 88]` at 30 s cadence with motionOk/onWrist/statusOk | at t=7 ENTER(WARN); at t=9 ENTER(ALERT); at t=... EMERGENCY once a <88 sample is corroborated by a <90 sample within 2 min |
| `SPO2_STALE_READINGS` | `activityState=STILL`; valid at t=0,1,2; t=3 missing (onInvalid); t=4 at 89; etc. | no ENTER(ALERT) because ring breaks at missing sample |
| `SPO2_OFF_WRIST_MID_DESCENT` | `activityState=STILL`; descent as above; at t=6 fire `IsDeviceWornEvent(false)` | CLEAR on whatever tier was active (should be NONE or WARN); then no further fires |
| `SPO2_MOTION_SUPPRESSION` | `activityState=WALKING`; descent as above but conf=80 throughout | 0 WARN, 0 ALERT, 0 EMERGENCY |
| `SPO2_STATUS_NOT_OK` | `activityState=STILL`; descent, but reading 3 has statusOk=false | ring does not advance on reading 3; recovery proceeds on reading 4+ |
| `SPO2_ALTITUDE_BUCKET_CHANGE` | `activityState=STILL`; stable 95 at bucket 0 for 5 readings; bucket changes to 2; new readings at 92 (normal for altitude) | no ENTER(WARN) during 10 min stabilisation window; baseline family switches to the new bucket |

Each scenario asserts:
- fire sequence exactly matches expected.
- `CustomEmergencyAlert` entries match category.
- `AlertRegistry` state after the scenario matches expected.
- No WARN for any reading spaced >60 s apart.

### 11.4 On-device smoke (Galaxy Watch 5 / Ultra)

- D1 8-h baseline: wear healthy, confirm `spo2_baseline_pct ∈ [96, 99]` and stable ±0.5.
- D2 Breath-hold (consented subject only): sustained breath-hold 25 s while STILL, on-wrist → expect ENTER(WARN) or ENTER(ALERT) depending on nadir; release → expect CLEAR within 60 s.
- D3 Motion suppression: same stimulus while walking → 0 fires.
- D4 Off-wrist: remove mid-stimulus → expect CLEAR immediately.
- D5 Battery regression: compare 24 h battery draw at `SPO2_MIN_GAP_MS=30s` vs prior 1 h — record delta.

### 11.5 Regression guardrails

- Re-run 441's 18 P1–P4 simulation scenarios (HR, HRV, skin-temp, pressure, cold). All must remain green; new SpO₂ rules must not cross-fire.
- Re-run P5 (500 ms rule eval) — no change expected.
- Add a specific regression scenario where `spo2_drop_alert` has been deleted — rule compiler must not error.

### 11.6 Pre-merge acceptance checklist

- [ ] All S1–S13 unit tests green.
- [ ] All P1–P6 baseline tests green.
- [ ] All F1–F4 FIFO tests green.
- [ ] All I1–I4 integration tests green.
- [ ] All six simulation scenarios green.
- [ ] 441 P1–P5 regression scenarios still green.
- [ ] `docs/CHANGELOG.md` updated with SpO₂ section.
- [ ] `ongoing_changes.md` updated with implementation-phase entries.
- [ ] PR description links to this v7 plan and the v0.6 predecessor.

---

## 12. Step-by-Step Execution Plan

Each step is a developer task. Ordering is intentional — every step leaves the tree compilable and testable. PR boundaries are suggested; actual splits may differ based on team preference.

### PR-1: Payload + topic skeletons

| Task | Files touched | Expected output | Verification |
|---|---|---|---|
| 12.1.1 Extend `Spo2Payload` with optional fields | `Spo2Payload.kt` | class compiles; existing call sites unchanged | `./gradlew :app:compileDebugKotlin` |
| 12.1.2 Extend `PpgPayload` with `ppgRedSustainedMs`, `ts` | `PpgPayload.kt` | compiles | same |
| 12.1.3 Create `Spo2TierEventPayload` + enums | `Spo2TierEventPayload.kt` (new) | class compiles | same |
| 12.1.4 Register `Topics.SPO2_TIER_EVENT` | `Topics.kt`, `TopicRegistry.kt` (if needed) | new constant visible | unit test that asserts presence |
| 12.1.5 Create `Spo2TierEventFifo` | `Spo2TierEventFifo.kt` (new) | class compiles with cap=32 default | F1–F4 unit tests green |

### PR-2: Baseline

| Task | Files touched | Expected output | Verification |
|---|---|---|---|
| 12.2.1 Add SpO₂ keys to `ProfileStore` | `ProfileStore.kt` | new fields + functions | P1–P6 unit tests green |
| 12.2.2 Implement `updateSpo2BaselinesEma` | `ProfileStore.kt` | EMA works, altitude-aware | P1, P2, P3 |
| 12.2.3 Implement `resetSpo2Baseline` for 72 h gap | `ProfileStore.kt` | works | P6 |
| 12.2.4 Add `userRiskProfile` getters/setters | `ProfileStore.kt` | default="default"; validated set | unit test |

### PR-3: Sequencer

| Task | Files touched | Expected output | Verification |
|---|---|---|---|
| 12.3.1 Create `Spo2Sequencer.kt` skeleton with public API | `Spo2Sequencer.kt` | class compiles | S1 test runs (fails as unimplemented) |
| 12.3.2 Implement `onValid` algorithm | same | S1–S4 pass | |
| 12.3.3 Implement hysteresis/clear | same | S9 passes | |
| 12.3.4 Implement `onInvalid` + long-outage | same | S10 passes | |
| 12.3.5 Implement `reset` | same | S11 passes | |
| 12.3.6 Implement trauma profile path | same | S12 passes | |
| 12.3.7 Implement OOR + thread-safety | same | S8, S13 pass | |

### PR-4: Engine wiring

| Task | Files touched | Expected output | Verification |
|---|---|---|---|
| 12.4.1 Add `Spo2TierEventFifo` field in `CepEngine` | `CepEngine.kt` | compiles | — |
| 12.4.2 Add `handleTierEvent` with edge-pulse decay | same | I2 passes | |
| 12.4.3 Hook `handleTierEvent` into existing bus collector | same | I3 passes | |
| 12.4.4 Add `updateSpo2Profile` + call from `start()` | same | FeatureStore carries profile + thresholds | runtime log check |
| 12.4.5 Confirm `AlertRegistry.clear` is callable | `AlertRegistry.kt` | visibility widened if needed | compiles |

### PR-5: SkinTempService bindings

| Task | Files touched | Expected output | Verification |
|---|---|---|---|
| 12.5.1 Tune `SPO2_MIN_GAP_MS`, `SPO2_BACKOFF_AFTER_TIMEOUT_MS` | `SkinTempService.kt` | new cadence | on-device log shows ~30 s sample cadence |
| 12.5.2 Add `ppgRedFirstContinuouslyOkSinceMs` tracking in continuous PPG callback | same | running ms available | log check |
| 12.5.3 Instantiate `Spo2Sequencer` in `onCreate` | same | sequencer alive | unit check via reflection |
| 12.5.4 Hook sequencer into `spo2StrictListener` status 2 / -6 / onError | same | sequencer gets called | I3 passes |
| 12.5.5 Hook `sequencer.reset(OFF_WRIST)` into `onIsDeviceWornEvent(false)` | same | reset called | device test D4 |
| 12.5.6 Add `emitLatestCep` freshness gate | same | one emission per acquisition | I1 passes |

### PR-6: Rule JSON

| Task | Files touched | Expected output | Verification |
|---|---|---|---|
| 12.6.1 Delete `spo2_drop_alert` | `cep_rules.json` | rule set still compiles | regression green |
| 12.6.2 Add `spo2_warn_baseline_drop` | same | rule compiles | sim scenario `SPO2_CLEAN_DESCENT` |
| 12.6.3 Add `spo2_alert_sustained_hypoxemia` | same | rule compiles | same |
| 12.6.4 Add `spo2_emergency_severe_hypoxemia` | same | rule compiles | same |
| 12.6.5 Add `spo2_clear_any` passive rule | same | fires on clear pulses | |
| 12.6.6 Add `features.spo2.baseline_minus_4` derived feature | same | feature present | |

### PR-7: DataRepository + UI

| Task | Files touched | Expected output | Verification |
|---|---|---|---|
| 12.7.1 Add `latestSpo2` and `latestSpo2TierEvent` StateFlows | `DataRepository.kt` | UI can observe | compose preview |
| 12.7.2 Add `setUserRiskProfile` | same | persists via ProfileStore; triggers `updateSpo2Profile` refresh | unit test |
| 12.7.3 Wire live service path to `storeLatestSpo2(...)` and `storeLatestSpo2TierEvent(...)` | `SkinTempService.kt`, `DataRepository.kt` | repository reflects current measurement and tier transitions | manual + log verification |
| 12.7.4 Debug panel (recommended, no longer optional) | `PhysioAlertsActivity.kt` or current debug surface | read-only current tier, ring size, baseline family/bucket, provisional flag, last invalid reason visible | manual |

### PR-8: Simulation scenarios

| Task | Files touched | Expected output | Verification |
|---|---|---|---|
| 12.8.1 Extend `ScenarioConfig` with `spo2Pattern`, `baselinePct`, `highRiskContext`, `ppgRedGood`, `stillWithConfidence`, `onWrist` | `CEPSimulationService.kt` | compiles | — |
| 12.8.2 Implement the original 6 scenarios with expected-fire assertions | same | all pass | sim harness |
| 12.8.3 Add v7.1 scenarios that cover trauma/high-risk, mixed baselines, outage, corroboration-based emergency, and bus/direct-path transport behavior | same | reviewed scenario matrix is represented in code | sim harness |
| 12.8.4 Emit structured pass/fail summaries for SpO2 scenarios, not just enum/config scaffolding | same | scenario failures are observable in logs and automation | sim harness |

### PR-9: Documentation + final regression

| Task | Files touched | Expected output |
|---|---|---|
| 12.9.1 Update `docs/CHANGELOG.md` with SpO₂ section | | |
| 12.9.2 Update `docs/ongoing_changes.md` | | |
| 12.9.3 Update this file (`docs/spo2_implementation_plan_v7.md`) to reflect what is actually complete versus still open after each implementation pass | doc stays trustworthy | |
| 12.9.4 Run full 441 regression pack (P1–P6 + all new SpO₂ scenarios) | all green | |

### 12.9A Remaining Work Required By v7.1 Audit

These items are now mandatory follow-up work. They came out of the code audit and `docs/spo2_plan_review_and_v7_1_deltas.md`, and should be treated as the active backlog for continuing implementation.

| Task | Files touched | Expected output | Verification |
|---|---|---|---|
| 12.9A.1 [done] Alert predicate now requires `(<90 for N)` plus `(baseline-4 OR high-risk)` with provisional bootstrap fallback retained | `Spo2Sequencer.kt` | alert logic aligned to PDF §4.2 / v7.1 §3.4 | local compile + follow-up targeted tests still required |
| 12.9A.2 [done] Warn predicate now uses upper-curve guard (`all >= 90`) and no longer hard-gates by 92–94 majority | `Spo2Sequencer.kt` | lower-baseline Warn suppression removed | local compile + follow-up targeted tests still required |
| 12.9A.3 [done] PPG-red sustain accounting now snapshots at pause and does not credit paused acquisition time | `SkinTempService.kt` | sequencer receives honest sustain evidence | local compile + device log validation still required |
| 12.9A.4 [done] Removed practical `altitudeBucket = 0` stub by wiring pressure-derived altitude bucket selection in service path | `Spo2Sequencer.kt`, `SkinTempService.kt` | altitude-aware baseline bucket is used for sequencer updates | targeted altitude scenario/test still required |
| 12.9A.5 [done-with-compat] Active-baseline contract is now canonical in rules/features (`profile.spo2_active_baseline_*` and `features.spo2.baseline_minus_4` now resolve via active baseline); legacy baseline mirror keys are intentionally retained for backward compatibility with existing dashboards/debug consumers | `CepEngine.kt`, rules JSON | canonical rule-side contract is active while legacy mirror remains read-only compatibility | verify legacy consumers before eventual key retirement |
| 12.9A.6 [done-tightened] Baseline-family selection remains majority-based but was refactored into explicit helper flow (`selectedFamilyForUpdate` vs `dominantWindowFamily` vs predicate baseline family) with mixed-window WARN abstain logic made explicit | `Spo2Sequencer.kt` | mixed-window handling is easier to audit and less implicitly coupled to tier logic | dedicated policy abstraction still deferred by design |
| 12.9A.7 [done] `spo2_clear_any` hold-down raised to 60 s | `cep_rules.json` | reduced clear spam risk | validate in sim/device logs |
| 12.9A.8 [done] SpO2 observability/debug outputs are now exposed via repository + UI: latest SpO2, latest tier event, active baseline value/family/bucket/provisional, ring size, and last invalid reason | `SkinTempService.kt`, `DataRepository.kt`, `PhysioAlertsActivity.kt` | runtime triage data is visible in-app without logcat-only debugging | manual UI verification still recommended on device |
| 12.9A.9 [done] Live runtime wiring for latest SpO2 and tier events is end-to-end and now consumed by UI/debug surface | `SkinTempService.kt`, `DataRepository.kt`, `PhysioAlertsActivity.kt` | UI/debug reflects current live state | on-device sanity check still recommended |
| 12.9A.10 [partial-tightened+++] Reviewed scenario matrix now runs with deterministic per-scenario result payloads and stricter expected-vs-observed checks (`expectedRuleIds`, `observedRuleIds`, expected/observed counts, missing IDs, unexpected IDs, scenario pass/fail, structured `assertionFailures`) and emits a single aggregated Phase-7 summary artifact (`phase7_spo2_summary.json`) per run for high-risk escalation, emergency corroboration, mixed-window WARN abstain, low-baseline WARN, long-outage clear, dual-path safety, and non-rest/non-walk baseline-update abstain; notification status highlights aggregate PASS vs FAIL | `CEPSimulationService.kt` and helpers | deterministic per-scenario outcomes + one aggregated run artifact make failures easier to trust and debug | still service-driven/log-asserted rather than JUnit assertions; full automation deferred |

### 12.9B Recommended Finish Order

The remaining work should be completed in this order so the highest-risk clinical behavior is validated before UI polish and doc closeout:

1. **Expand SpO2 simulation coverage first.**
   - Add the missing reviewed scenarios for trauma/high-risk escalation, emergency corroboration, mixed-window abstain, low-baseline Warn, long outage clear, and transport-path behavior.
   - The goal is to make the reviewed v7.1 behavior executable before more cleanup/refactoring happens.
2. **Finalize the active-baseline contract.**
   - Decide whether `profile.spo2_active_baseline_*` is now the canonical rule/UI/debug contract.
   - Migrate remaining consumers where practical and document any intentional legacy mirror retention.
3. **Finish the debug/UI visibility pass.**
   - Expose latest SpO2, latest tier event, active baseline, family, altitude bucket, provisional flag, ring size, and last invalid reason.
   - This is the fastest way to reduce field-debug ambiguity.
4. **Run targeted SpO2 verification.**
   - Execute the expanded Phase-7 scenarios.
   - Capture one complete Phase-7 result artifact per run instead of relying only on scattered log lines.
   - Verify off-wrist clear/reset behavior.
   - Verify clear-throttle behavior and repository state resets.
5. **Run wider CEP regression.**
   - Confirm SpO2 work did not regress HR / HRV / cold / pressure rules or event flow.
6. **Only then close documentation and changelog work.**
   - Update this file, `docs/CHANGELOG.md`, and `docs/ongoing_changes.md` with actual verified status.

### 12.10 Verification timeline

- After PR-5: full acquisition + sequencer path working in isolation; manual on-device breath-hold test should fire an ALERT.
- After PR-6: rules dispatch to TAK/SMS/Ditto; full end-to-end works.
- After PR-8: automated regression gate for SpO₂.
- After PR-9: merge-ready.

### 12.10A Exit Criteria For The Next Implementation Pass

The next pass should be considered successful only if all of the following are true:

- At least the reviewed high-risk/corroboration/mixed-window scenarios are added to `CEPSimulationService` and produce explicit pass/fail output.
- SpO2 scenario checks are tightened beyond service/log-only review as far as practical in this pass; if full automation is not landed, the exact remaining gap is documented.
- A single complete Phase-7 scenario outcome artifact/report is produced by the runtime or harness for review.
- The remaining active-baseline compatibility story is either completed or explicitly documented with a reason for deferral.
- A usable debug/UI surface exists for live SpO2 state.
- On-device validation of the debug panel is performed or explicitly deferred with a reason.
- Wider CEP regression expectations (P1–P6 + all SpO2 scenarios) are either executed or explicitly deferred with a reason.
- This file is updated so no task remains marked `[partial]` unless the exact missing piece is spelled out.

### 12.10B Verification Notes (latest pass)

Verified in this pass:

- `./gradlew assembleDebug` succeeds after the finish-pass changes.
- Local compile confirms the added simulation scenarios, active-baseline contract updates, repository debug state additions, and debug UI bindings are build-clean.
- Rule contract update validated in code review: `features.spo2.baseline_minus_4` now references `profile.spo2_active_baseline_pct`.
- `Spo2Sequencer` predicate evaluation path was tightened with helper methods and explicit baseline-selection naming without changing intended tier semantics.
- `SkinTempService` SpO2 timeout/error/invalid handling now also clears transient `latestSpo2TierEvent` on invalid outcomes so UI/debug state does not keep stale tier snapshots after invalid/timeout/error paths.
- Sequencer-side baseline classification was audited and confirmed: only `STILL` and `WALKING`/`LIGHT_ACTIVITY` can drive baseline updates; non-rest/non-walk activity abstains with explicit log output.
- `ProfileStore.updateSpo2BaselinesEma` now enforces the same second-line classification guard and returns `false` for unclassified activity states (`RUN`/`VEHICLE`/`UNKNOWN`/other), with explicit `baseline_ema_abstain` log output.
- `CEPSimulationService` Phase-7 SpO2 checks were tightened further: assertions now report `expectedRuleIds`, `observedRuleIds`, expected counts, observed counts, missing IDs, unexpected IDs, per-scenario `assertionFailures`, and scenario pass/fail.
- Phase-7 now includes a dedicated baseline-abstain scenario that validates resting/walk baselines and sample counts do not advance under non-rest/non-walk activity inputs.
- `CEPSimulationService` emits one aggregated Phase-7 report artifact (`phase7_spo2_summary.json`), one `[ASSERT][PHASE7_SUMMARY]` log entry per run, and explicit aggregate PASS/FAIL notification state.
- `CepEngine.handleTierEvent` now has a bounded idempotency guard (dedupe key `(tier,state,ts)`, LRU size 32) to prevent duplicate processing if dual transport paths call the handler.
- `./gradlew assembleDebug` was rerun in this pass and succeeded.

Not fully verified in this pass:

- Runtime execution of the full Phase-7 service loop was not launched in this pass, so no fresh summary artifact content was produced in this transcript.
- Full automated conversion of SpO2 scenario checks from service/log assertions into dedicated test-harness/JUnit assertions is still outstanding.
- On-device manual validation of the new PhysioAlerts SpO2 debug panel was not run in this pass.
- Wider full CEP regression (P1–P6 + all SpO2 scenarios under runtime conditions) remains recommended before merge.

### 12.11 Cursor Continuation Prompt

Use the following prompt as the handoff for the next implementation pass. It is intentionally detailed so Cursor can use this file as the primary guide without re-deriving the finish plan.

```text
Continue the SpO2 tightening/verification pass on branch WEAR-441.

Primary source of truth:
1. docs/spo2_implementation_plan_v7.md

Secondary design references:
- docs/spo2_cursor_implementation_plan.md
- docs/strategy.pdf

Historical review reference only if clarification is needed:
- docs/spo2_plan_review_and_v7_1_deltas.md

Current repo state:
- The core SpO2 path is already implemented (payloads, sequencer, ProfileStore, CepEngine wiring, SkinTempService integration, base rules).
- Several high-priority v7.1 deltas are already implemented in code: Alert disjunction, Warn upper-curve guard, paused-PPG sustain accounting fix, live altitude bucket plumbing, repository live-path writes, active-baseline feature publishing, debug/UI visibility, and reviewed Phase-7 simulation scenarios.
- The remaining work is now tightening and verification work, not feature expansion.
- Cursor feedback from the last pass identified three explicit follow-up items:
  1. Full automated conversion of SpO2 scenario checks from service/log assertions into dedicated test-harness assertions.
  2. Full runtime capture/report of all Phase-7 scenario outcomes in one artifact.
  3. On-device validation sweep of the debug panel plus broader CEP regression (P1–P6 + all SpO2 scenarios).
- Treat docs/spo2_implementation_plan_v7.md as a living status document and update it as you complete work.

Read first:
1. docs/spo2_implementation_plan_v7.md
2. docs/spo2_cursor_implementation_plan.md
3. docs/strategy.pdf
4. docs/spo2_plan_review_and_v7_1_deltas.md only when you need historical rationale

Before editing:
- Audit the current implementation first.
- Do not redo work that has already landed.
- Use docs/spo2_implementation_plan_v7.md sections 12.9A, 12.9B, and 12.10A as the finish checklist.

Primary objective for this pass:
- Tighten the existing SpO2 implementation so the remaining risk is reduced through stronger verification, cleaner capture of scenario outcomes, and explicit documentation of anything still deferred.

Execute in this order:

Step 1: Tighten Phase-7 verification first.
- Work in CEPSimulationService and related helpers first.
- Do not add new feature scope unless a missing verification path requires it.
- Focus on the three open verification gaps:
  1. move scenario checks closer to real automated assertions if practical in this pass
  2. produce one complete Phase-7 outcome artifact/report
  3. make scenario failures harder to misread
- Keep the reviewed scenario set intact:
  - trauma/high-risk escalation
  - emergency corroboration path
  - mixed-window WARN abstain
  - low-baseline WARN case
  - long sensor outage clear
  - dual-path / transport safety
- Preferred outcome:
  - deterministic result structure per scenario
  - one aggregated Phase-7 summary artifact/report
  - clearer failure messages than raw log scanning
- If full JUnit/test-harness conversion is too large for this pass, tighten the runtime/harness reporting as much as possible and document the exact automation gap that remains.

Step 2: Tighten active-baseline contract usage.
- Treat `profile.spo2_active_baseline_*` as the intended canonical contract unless the current code proves otherwise.
- Audit rules, debug consumers, and any remaining baseline feature usage.
- Migrate remaining consumers where practical.
- If legacy baseline mirror keys must remain, document exactly why in docs/spo2_implementation_plan_v7.md.

Step 3: Validate and tighten debug/UI behavior.
- The debug surface already exists. Verify and tighten it rather than redesigning it.
- Confirm it correctly reflects:
  - latest SpO2
  - latest tier event
  - active baseline value/family
  - active altitude bucket
  - provisional flag
  - ring size
  - last invalid reason
- Fix stale-state or reset issues if found.

Step 4: Verify the SpO2 feature directly.
- Run the SpO2 simulation phase and capture all scenario outcomes in one artifact/report.
- Verify off-wrist reset clears repository state and tier state.
- Verify clear throttling behavior remains acceptable.
- If possible, run local compile/build verification after changes.

Step 5: Verify broader CEP stability.
- Run or tighten coverage expectations for broader CEP regression:
  - P1–P6 baseline scenarios
  - all SpO2 scenarios
- If full runtime execution is not practical in this pass, document exactly what was and was not run.

Step 6: Update docs before finishing.
- Update docs/spo2_implementation_plan_v7.md with:
  - what you completed
  - what is still partial
  - what was verified
  - what could not be verified
- Do not mark an item `[done]` unless code and at least one relevant verification step are complete.
- If you defer anything, record the exact reason in the doc.

Documentation update requirement:
- Update docs/spo2_implementation_plan_v7.md before you finish.
- Mark completed items clearly.
- Keep the remaining items accurate enough that the next handoff does not need another repo-wide audit.
- Prefer changing stale `[partial]` rows into either `[done]` or a tighter statement of what is still missing.

Output expectation:
- Make the tightening/verification code changes.
- Update docs/spo2_implementation_plan_v7.md with completed work, verification status, remaining work, and explicit deferrals.
- If any reviewed item is intentionally deferred, record the reason explicitly in the doc.
- Leave the repo in a state where the next pass can continue from the doc alone.
```

---

## 13. Risk Register

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | **Battery draw regression** from `SPO2_MIN_GAP_MS` 1 h → 30 s | High | Medium (user complaints) | D5 on-device measurement; if > +5% daily drain, move to adaptive: 30 s while PPG-red good, 10 min otherwise |
| R2 | **Samsung SDK optical contention** under frequent SpO₂ cycling | Medium | Medium (no readings) | F8 path + UI banner after 3 consecutive backoffs; keep `pausePpgContinuousForSpo2` discipline |
| R3 | **Baseline cold-start false Warn** if provisional gate fails to flip | Low | High (first-day user experience) | P1 test; explicit log line when provisional flips; UI shows "baseline warming up" |
| R4 | **Edge-pulse decay race** — rule eval misses a pulse if eval coroutine is delayed | Low | High (silent miss) | Pulse width = `ruleEvalIntervalMs + 250`; if scheduler is late, a second rule eval still sees the edge. Add I2 test. |
| R5 | **FIFO overflow under burst** (e.g. flapping boundary during descent) | Low | Low | capacity=32; drop-oldest; log FIFO_DROP; FIFO is staging only, not the source of truth |
| R6 | **Thread-safety bug in sequencer** if Samsung calls fire concurrently | Low | High (ring corruption) | ReentrantLock around all state; S13 stress test |
| R7 | **Trauma profile misset** — user flagged trauma when healthy | Medium | Medium (spurious Alert) | UI gated behind operator-level permission; default stays "default" |
| R8 | **Stale baseline after long off-wrist** | Medium | Medium (false tier) | 72 h re-anchor path (§6.6); provisional restored |
| R9 | **JsonLogic `*_fresh` operator semantics mismatch** (hypothetical) | Low | High (rules don't fire) | Verify at PR-6 with an integration test that feeds a stale `spo2.pct` and asserts the rule does not fire |
| R10 | **Altitude bucket source not yet wired** | Medium (dependency) | Medium (Warn inaccurate at altitude) | Phase 1 hard-codes bucket=0; Phase 2 wires from `features.pressure.altitude_estimate_m` once available |
| R11 | **Rule compiler rejects tombstone syntax** | Low | Low | fallback is to delete the stub outright; the new rules are independent |
| R12 | **SMS cost on Emergency re-entry storm** (Emergency → clear → Emergency 15 s later) | Low | Medium (cost) | Dispatcher throttle 15 s + rule hold_down 300 s; if still noisy, raise hold_down to 600 s in v7.1 |
| R13 | **Interaction with cold-stress rules** — PPG amplitude ratio already feeds cold score; SPO₂ cycling pauses PPG continuous, briefly lowering amplitude_recent_20s | Medium | Low-Medium | PPG pause window is ≤30 s; amplitude_recent_20s with maxAgeSec=60 still holds a pre-pause value; acceptable per §11.5 regression check |
| R14 | **UI stale after reset(OFF_WRIST)** — `latestSpo2TierEvent` might stay on ENTER(ALERT) | Low | Low | reset emits CLEAR; UI reacts normally |
| R15 | **Log volume** from HELD emissions at 30 s cadence | Low | Low | HELD is D-level; release builds default to I+ |
| R16 | **PDF interpretation ambiguity — N value** | Medium | Medium | Pinned N=4 in config; single flip of `settings.spo2.consecutive_required` changes behaviour |
| R17 | **TAK dispatch over multicast when no peers** | Low | Low | existing behaviour, unchanged |
| R18 | **Android O+ foreground-service throttling** during long stretches | Low | Medium | existing mitigation via foreground notification; `SkinTempService` is already a foreground service |

---

## 14. Implementation-time Uncertainties (flagged; resolve in PR)

These are the spots where this plan cannot be 100% certain without running code. They should be resolved on the first PR that touches the relevant file and the plan updated:

- **U1** — Exact signature of `lte_key_fresh` / `gte_key_fresh` in `JsonLogic.kt`: 2-arg `[key, literal]` with freshness implicit, or 3-arg `[key, literal, maxAgeSec]`. Plan assumes 3-arg; verify at PR-6.
- **U2** — Whether the rule set compiles cleanly after deleting `spo2_drop_alert`. If not, revert to the previous ruleset and revisit the migration.
- **U3** — Exact path of `CEPSimulationService.kt`. Search at PR-8.
- **U4** — Whether `TopicRegistry` has an explicit payload-type table requiring `Spo2TierEventPayload` registration. Current evidence suggests no-op; verify at PR-1.
- **U5** — Exact PPG amplitude/quality thresholds (`PPG_AMP_MIN`, `PPG_QUALITY_MIN`) currently used in `SkinTempService` — read the file at PR-5 and reuse those constants, do not redefine.
- **U6** — `activity.confidence` is part of the plan contract. Implement it by extending `ActivityPayload` and mirroring it in `CepEngine`; verify at PR-5.
- **U7** — Whether altitude bucket is computable today from pressure-derived altitude. Plan's Phase 1 falls back to bucket=0 if not.
- **U8** — Whether `AlertRegistry.clear(ruleId)` is public. `CepEngine.checkAndClearAlerts` already calls `AlertRegistry.clear` internally, so visibility is almost certainly fine.
- **U9** — Whether the reviewed v7.1 active-baseline contract (`profile.spo2_active_baseline_*`) should replace the simpler current mirror immediately, or be staged behind a compatibility pass in rules/UI.
- **U10** — Whether the Samsung SDK exposes trustworthy in-window PPG-red evidence during the paused SpO2 session; if yes, prefer that over pre-pause sustain snapshotting.

---

*End of implementation plan.*
