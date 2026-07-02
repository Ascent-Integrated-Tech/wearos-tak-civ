# SpO₂ Implementation — Progress Analysis & Recommendations

**Date:** 2026-04-25
**Document type:** point-in-time audit memo. Not the live status of record. The live status of record remains §1.0 of `docs/spo2_implementation_plan_v7.md`, which the team keeps current as PRs land.
**Inputs reviewed:**
- `docs/spo2_implementation_plan_v7.md` (the "v1" of the implementation plan, currently at v7)
- `docs/spo2_plan_review_and_v7_1_deltas.md` (v7.1 review with §3.1–§3.10 deltas)
- `docs/spo2_cursor_implementation_plan.md` (Cursor handoff)
- `docs/spo2_non_cep_changes.md` (non-CEP file inventory)
- `docs/strategy.pdf` (`AP-SPO2 Alerting Strategy V1.0` — authoritative source)
- The actual WEAR-441 codebase on disk

**Net assessment (revised after codex meta-review):** SpO₂ implementation is **largely complete**. The v7 plan was executed and the v7.1 deltas have landed in code, including the parts that matter most clinically (Warn upper-curve guard, mixed-window WARN abstain via `dominantWindowFamily`, Emergency corroboration, Alert disjunction, dual baselines, edge-pulse semantics, dispatch transport, debug surface, reviewed Phase-7 simulation scenarios). What remains splits cleanly into three buckets:

1. **One current correctness gap** — baseline update classification for `RUN` / `VEHICLE` / `UNKNOWN`.
2. **Verification closeout** — JUnit-enforced gating of the Phase-7 SpO₂ scenarios, on-device validation of the debug panel and reset behaviour, and a wider regression run.
3. **Three architectural hardening opportunities** — none of which are active bugs today. They are future-proofing items: `handleTierEvent` idempotency LRU, 72 h per-family-per-bucket re-anchor, and altitude-bucket EMA freeze / warm-start.

---

## 1. Docs Directory Inventory

| File | Purpose | State |
|---|---|---|
| `spo2_implementation_plan_v7.md` | Master plan (1237 lines). §1.0 keeps a live status checklist. | Plan landed; status checklist current as of 2026-04-24 |
| `spo2_plan_review_and_v7_1_deltas.md` | My review with §3.1–§3.10 deltas. | Final after second-pass codex revisions to §3.7/§3.8/§3.10 |
| `spo2_cursor_implementation_plan.md` | Cursor handoff (789 lines, 9 phases, 18 scenarios). | Aligned with v7.1; Phase 3.2 / 3.7 just re-mirrored |
| `spo2_non_cep_changes.md` | File-by-file inventory of non-CEP changes. | Up to date; useful audit trail |
| `strategy.pdf` | PDF "V1.0" authoritative source. | Reference only; no edits |
| `CEP_Migration_Plan_And_Test_Strategy.md` | Older CEP migration doc; not SpO₂-specific. | No SpO₂ content; out of scope here |
| `CHANGELOG.md` | Project changelog. | No SpO₂ entries yet |
| `ongoing_changes.md` | Working-state changelog. | No SpO₂ entries yet |
| `SAMPLE_SIZE_WINDOW.md` | CEP windowing reference. | Pre-existing, indirectly relevant |
| `SIMULATION.md` | Simulation harness reference. | Pre-existing, indirectly relevant |

---

## 2. Implementation Status (v7 Plan vs. Code on Disk)

I cross-referenced the plan against the WEAR-441 codebase. The legend below uses ✅ done, ⚠ partial / regression, ❌ missing.

### 2.1 New components (Phase 1)

| Component | Plan path | Disk path | State |
|---|---|---|---|
| `Spo2TierEventPayload` | `cep/model/payloads/Spo2TierEventPayload.kt` | same | ✅ |
| `Spo2TierEventFifo` | `cep/engine/Spo2TierEventFifo.kt` | same | ✅ implemented (cap=32) |
| `Spo2Sequencer` | `service/Spo2Sequencer.kt` | same | ✅ 242 LOC, full predicate set |
| `Spo2Payload` extension | `cep/model/payloads/Spo2Payload.kt` | same | ✅ has `statusOk`, `motionOk`, `ppgRedSustainedMs`, `ppgAmplitude`, `ppgQuality` |
| `PpgPayload` extension | `cep/model/payloads/PpgPayload.kt` | same | ✅ |
| `ActivityPayload.confidence` | `cep/model/payloads/ActivityPayload.kt` | same | ✅ |
| `Topics.SPO2_TIER_EVENT` | `cep/model/Topics.kt` | same | ✅ |

### 2.2 Sequencer predicate semantics (the substance of v7.1)

| v7.1 review delta | Codebase evidence | State |
|---|---|---|
| §3.4 — Alert disjunction (`< 90 AND (≤baseline-4 OR provisional OR high-risk)`) | `Spo2Sequencer.kt:236-238` | ✅ implemented exactly |
| §3.6 — Edge-pulse decay widened to `ruleEvalIntervalMs + 250 ms` | `CepEngine.kt:385,404` | ✅ |
| §3.7 — Dual baseline families with mixed-window WARN abstain | `Spo2Sequencer.kt:197-214` (`resolveActiveBaselineSelection`) returns null on family tie; `computePredicates` line 231 gates Warn on `dominantWindowFamily != null` | ✅ behaviour correct |
| §3.7 — `BaselineSelectionPolicy` interface for pluggable classification | (not introduced; classification hard-coded in Sequencer + ProfileStore) | ⚠ predicate side correct; update side has the RUN/VEHICLE/UNKNOWN → RESTING regression (see §3.A.1). The interface itself is §3.D.1, cosmetic. |
| §3.8 — Upper-curve gate `all >= alertPct` (not hard 92-94 band) | `Spo2Sequencer.kt:234` | ✅ |
| §3.9 — Emergency corroboration (`<88 AND (<90 within 2 min OR high-risk)`) | `Spo2Sequencer.kt:226-239` | ✅ |
| §3.10 — Direct sequencer→`handleTierEvent` non-lossy path | `SkinTempService.kt:1031-1041` calls `cepEngine.handleTierEvent(payload)` directly **and** emits to bus for observers | ✅ option (C) chosen |
| §3.10 — Single-owner idempotency on `handleTierEvent` | `CepEngine.kt:367-411` does NOT have an LRU/dedupe guard; bus collector at line 70 only calls `spo2TierFifo.offer` (does NOT call `handleTierEvent`) | ⚠ no active bug — only one caller today (the direct path); future-proofing item, see §3.C.1 |
| Trauma-N shrinkage | `Spo2Sequencer.kt:105` `max(2, N/2)` when `userRiskProfile() == "trauma"` | ✅ |
| `ResetReason.SERVICE_STOP` | `Spo2Sequencer.kt:34`, used in `SkinTempService.onDestroy()` | ✅ |
| Hysteresis-based clear (1 % above tier + 60 s sustain) | `Spo2Sequencer.kt:138-155` | ✅ |
| Long sensor outage clear (>5 min) | `Spo2Sequencer.kt:162-166` | ✅ |

### 2.3 Engine wiring (Phase 4)

| Item | Disk evidence | State |
|---|---|---|
| `CepEngine.spo2TierFifo` field | `CepEngine.kt:35` | ✅ |
| `CepEngine.handleTierEvent` writes edge/clear booleans + decay | `CepEngine.kt:367-411` | ✅ |
| `CepEngine.updateSpo2Profile` publishes active-baseline mirror | `CepEngine.kt:413-431` | ✅ |
| Bus collector publishes `activity.confidence` to FeatureStore | `CepEngine.kt:72-74` | ✅ |
| Bus collector also calls `handleTierEvent` | (collector only offers to FIFO; does NOT call `handleTierEvent`) | ⚠ deliberate single-path arrangement, no active bug — see §3.C.1 for the future-proofing case |
| `onAltitudeBucketChange` flow (EMA freeze on bucket change) | (not implemented) | ❌ — hardening item, see §3.C.3 |
| 72 h per-family-per-bucket re-anchor | `ProfileStore` has only `resetSpo2Baselines()` (resets ALL); no scoped re-anchor; sequencer never invokes it | ❌ — hardening item, see §3.C.2 |

### 2.4 Service wiring (Phase 5)

| Item | Disk evidence | State |
|---|---|---|
| Sequencer instantiation in `onCreate` | `SkinTempService.kt:1031-1044` | ✅ |
| Dual dispatch (direct + bus) | same lines | ✅ |
| Activity confidence published | `ActivityRecognitionService.kt:204` | ✅ |
| `pausePpgContinuousForSpo2` / `resumePpgContinuousAfterSpo2` accounting | `ppgRedSustainedAtPauseMs` snapshot per non-CEP doc §1 | ✅ (per doc; not re-verified line-by-line in this pass) |
| Live altitude bucket via `currentAltitudeBucket()` from pressure | `SkinTempService.kt:1266-1276` | ✅ — better than v7's "Phase 1 stub bucket=0" |
| Reset on `onDestroy()` and `onIsDeviceWornEvent(false)` | `SkinTempService.kt:1134-1135, 1176` | ✅ |
| `ACTIVITY_STILL_CONFIDENCE_MIN` constant | (not re-verified by line; non-CEP doc §1 lists it) | ✅ per doc |
| Freshness gate `lastSpo2EmittedTs` / `SPO2_EMIT_FRESHNESS_MS` | non-CEP doc §1 lists them | ✅ per doc |

### 2.5 Rule JSON (Phase 6)

| Rule id | State | Notes |
|---|---|---|
| `spo2_warn_baseline_drop` | ✅ added (`cep_rules.json:1976`) | gates on `tier_event_warn_edge`, `offbody.on_wrist`, `provisional == false`, `pct` fresh ≤30 s |
| `spo2_alert_sustained_hypoxemia` | ✅ added (`cep_rules.json:2002`) | gates on `tier_event_alert_edge`, `STILL`, `confidence ≥ 50`, `pct ≤ 90` fresh |
| `spo2_emergency_severe_hypoxemia` | ✅ added (`cep_rules.json:2029`) | gates on `tier_event_emergency_edge`, `pct ≤ 88` fresh; no STILL gate (consistent with §3.9 PDF intent) |
| `spo2_clear_any` | ✅ added (`cep_rules.json:2054`) | `any` over the three clear booleans |
| `spo2_drop_alert` legacy | ✅ deleted | only present in `x_old_cep_rules.json` |
| `features.spo2.baseline_minus_4` derived feature | ✅ added (`cep_rules.json:218`) | |

### 2.6 Repository / UI / debug surface (Phase 7)

| Item | State |
|---|---|
| `DataRepository.latestSpo2`, `latestSpo2TierEvent`, `latestSpo2DebugState` | ✅ per non-CEP doc §3 |
| `DataRepository.setUserRiskProfile` triggers `updateSpo2Profile` | ✅ per non-CEP doc §3 |
| `PhysioAlertsActivity` debug card | ✅ per non-CEP doc §6 |

### 2.7 Simulation coverage (Phase 8)

| Scenario | State |
|---|---|
| `SPO2_TRAUMA_HIGH_RISK_ESCALATION` | ✅ |
| `SPO2_EMERGENCY_CORROBORATION_PATH` | ✅ |
| `SPO2_MIXED_WINDOW_WARN_ABSTAIN` | ✅ |
| `SPO2_LOW_BASELINE_WARN_CASE` (the case my first §3.8 fix wrongly suppressed) | ✅ |
| `SPO2_LONG_SENSOR_OUTAGE_CLEAR` | ✅ |
| `SPO2_TRANSPORT_DUAL_PATH_SAFETY` | ✅ |
| Per-scenario assertion + aggregated `phase7_spo2_summary.json` | ✅ exists, but runtime/service-driven (executed via the simulation harness, not via `:app:testDebugUnitTest`) |
| JUnit/test-suite enforcement of those scenarios | ❌ — the headline verification gap; see §3.B.1. Until this lands, every PR thereafter is one keystroke away from silently regressing the corroboration predicate or upper-curve gate without CI noticing. |

### 2.8 Coverage of the v7 §1.0 status checklist

The v7 plan keeps a live PR-by-PR status block at lines 17–36. It correctly reports:

- PR-1 through PR-7 landed.
- PR-8 substantially expanded (Phase-7 SpO₂ scenarios + aggregated artifact).
- Remaining gaps: JUnit enforcement, fresh runtime artifact capture, on-device validation, wider regression run.

That matches the codebase. The v7 status block is not stale.

---

## 3. Gap Analysis

The remaining gaps split into three categories. Mixing them at the same severity level — as the first version of this memo did — overstated the urgency of the architectural hardening items relative to the genuine correctness gap. The split below is the corrected framing.

### 3.A — Current Correctness Gap (one item)

This is the only item where the current code's behaviour produces a worse clinical outcome than the v7.1 spec under realistic real-world inputs.

#### 3.A.1 `RUN` / `VEHICLE` / `UNKNOWN` activity samples are silently bucketed into the `resting` baseline

**Where:** `Spo2Sequencer.kt:87` and `ProfileStore.kt:61-64`.
```kotlin
val family = when (activityState) {
    "WALKING", "LIGHT_ACTIVITY" -> "walk"
    else                        -> "resting"     // catches RUN, VEHICLE, UNKNOWN, anything
}
```

**What works correctly today (credit where due):** the *predicate* side of dual-baseline reasoning is in good shape. The sequencer's `resolveActiveBaselineSelection` (`Spo2Sequencer.kt:197-214`) does explicit majority-vote over the ring's per-sample family and returns null on a tie, and the WARN predicate in `computePredicates` (line 231) gates on `dominantWindowFamily != null`, so mixed-window WARN abstain is real and working. This memo's first version did not give that machinery enough credit. Alert and Emergency are baseline-independent in this code path, so they continue firing through mixed windows — exactly the behaviour the v7.1 review recommended.

**What is still broken:** the *update* side. The classification used to *route a sample into a baseline EMA* still defaults `RUN` / `VEHICLE` / `UNKNOWN` into `resting`. Over time a user who runs regularly trains their resting EMA on running-derived SpO₂ values, which are systematically lower than true resting due to ventilatory load. The downstream effect is that `(baseline − 4)` ends up artificially low, suppressing future Warns for that user.

**Severity:** moderate-to-high for active users; the population this feature primarily serves.

**Fix shape:** abstain on `RUN`, `VEHICLE`, `UNKNOWN`:
```kotlin
val family = when (activityState) {
    "STILL"                     -> "resting"
    "WALKING", "LIGHT_ACTIVITY" -> "walk"
    else                        -> null    // do not update baseline; do not classify into either family
}
if (family != null) profile.updateSpo2BaselinesEma(...)
```
Mirror the change in `ProfileStore.updateSpo2BaselinesEma` so callers cannot accidentally hit the `else -> "resting"` path. Add a unit test that asserts a `RUN` sample does not advance any EMA. Correct fix is one diff in two files.

### 3.B — Verification Closeout (the v7 §1.0.1 list)

These items are already on the v7 plan's own remaining-work list. They are not architectural changes; they are gating and validation work that should land before the feature is considered shipped.

#### 3.B.1 JUnit-level enforcement of Phase-7 SpO₂ scenarios

The Phase-7 SpO₂ scenarios in `CEPSimulationService` (high-risk escalation, emergency corroboration, mixed-window WARN abstain, low-baseline WARN, long-outage clear, dual-path transport safety) all assert pass/fail and emit a per-run `phase7_spo2_summary.json`. The assertions are correct and the artifact is real. **The gap is that the harness is runtime/service-driven, not gated by `:app:testDebugUnitTest`** — so a regression in the corroboration predicate, the upper-curve gate, or the mixed-window abstain is only caught on a manual harness run, not on CI.

This is the difference between "we have tests" and "the build fails when the tests fail". It is the highest-ROI verification item because every PR thereafter inherits the protection.

**Severity:** moderate.

#### 3.B.2 Fresh runtime `phase7_spo2_summary.json` capture

The v7 plan §1.0.1 lists this as remaining: a fresh runtime-produced summary from a full Phase-7 service run was not captured during the most recent verification pass. Run it; commit the artifact; reference it in the merge note.

#### 3.B.3 On-device validation of SpO₂ debug panel + reset behaviour

The debug card in `PhysioAlertsActivity` is wired and the data flows are populated from `DataRepository`, but the v7 §1.0.1 list flags that on-device end-to-end validation of the panel and full reset behaviour (off-wrist → reset → on-wrist → bootstrap) has not been executed in this pass. Required before merge.

#### 3.B.4 Wider CEP regression run

Run P1–P6 plus all SpO₂ scenarios under runtime conditions on a full app boot. v7 §1.0.1 explicitly flags this as not fully executed.

### 3.C — Architectural Hardening Opportunities (no active bug; future-proofing)

These three items are real architectural follow-ups but **none of them are active bugs in the current codebase**. They will become bugs the moment the surrounding shape changes — adding a second producer for tier events, supporting deployments with long off-wrist gaps as a routine flow, supporting altitude-mission profiles where the wearer crosses bucket boundaries multiple times per hour. Land them when those use cases become real, or when the team has a slack PR cycle. The first version of this memo treated them as same-weight as the correctness gap above; that was wrong.

#### 3.C.1 `handleTierEvent` idempotency LRU

**State today:** `CepEngine.handleTierEvent` (`CepEngine.kt:367-411`) has no LRU/dedupe. The bus collector at `CepEngine.kt:69-71` only offers tier events to the FIFO and does NOT call `handleTierEvent` — only the direct sequencer path (`SkinTempService.kt:1031-1041`) does. So **there is exactly one caller in steady state and double-dispatch cannot occur today.**

**Why it is still worth landing:** the v7.1 review committed to option (C) for the dispatch-transport story, which is the dual-path arrangement. The dual-path arrangement is safe-by-coincidence today because the bus side is deliberately not wired to `handleTierEvent`. If a future PR wires it (for example, to dispatch tier events emitted by `CEPSimulationService`'s synthetic injection path or from a second producer), the dedupe must already be in place or the engine will double-dispatch. The fix is a 5-line LRU and a block comment documenting the contract — close to free.

**Also note:** `Spo2TierEventFifo` is offered to but never drained anywhere in `app/src/main`. It is currently a 32-slot ring with one writer and zero readers. Either wire a real drain consumer (e.g. recorders in `CEPSimulationService`, or a periodic mirror to `DataRepository`) or delete it. This is not blocking anything but it is dead code that creates the appearance of a contract that does not exist.

**Severity:** zero today. Latent risk + dead-code cleanup.

#### 3.C.2 72 h per-family-per-bucket baseline re-anchor

**State today:** `ProfileStore.kt:91` only exposes `resetSpo2Baselines()` (resets *all* families and buckets). No scoped re-anchor exists; the sequencer never calls one.

**When it matters:** long gap → resume scenarios. User takes the watch off for a multi-day deployment leg and comes back. The EMA resumes from a stale anchor and is biased toward whatever the last few samples were. Warn coverage is suppressed for the day or two it takes the EMA to re-stabilise.

**Severity:** low-to-moderate, scenario-bounded.

**Fix shape:**
```kotlin
fun resetSpo2Baseline(family: String, bucket: Int) { /* clear pct, count, provisional, updated_ts for that key tuple */ }
```
Then in the sequencer or `ProfileStore.updateSpo2BaselinesEma` check `nowMs - updatedTs > 72 * 3_600_000L` per (family, bucket) and call the scoped reset before applying the new sample.

#### 3.C.3 Altitude-bucket EMA freeze / warm-start

**State today:** the bucket is recomputed each `onValid` call from `currentAltitudeBucket()` (`SkinTempService.kt:1266-1276`, which derives bucket from live pressure — better than the v7 plan's "Phase 1 stub bucket=0"). `ProfileStore.updateSpo2BaselinesEma` writes to the new bucket's EMA the moment it sees a sample at that bucket. There is no "10 min freeze after bucket change" or "warm-start from prior bucket".

**When it matters:** ascent/descent. When the wearer crosses a 1000 m / 2500 m / 4000 m boundary, the first valid sample at the new bucket initialises an EMA with zero history — so the next predicate evaluation in that bucket runs against `baseline=97` (the default) and `provisional=true`. Alert and Emergency still fire (they are baseline-independent), but **Warn is gated on `provisional == false`, so users at altitude silently lose Warn coverage for the time it takes to bootstrap (>20 valid samples ≈ several hours of wear).**

**Severity:** moderate at altitude, zero at sea level.

**Fix shape:** either a freeze ("don't write to the new bucket's EMA for the first 10 min after the change, keep evaluating Warn against the prior bucket's baseline") or a warm-start ("pre-seed the new bucket's EMA from the prior bucket's value as a 1-sample bootstrap"). Either is consistent with v7's R10 mitigation intent.

### 3.D — Minor / Cosmetic

#### 3.D.1 `BaselineSelectionPolicy` interface not introduced

The v7.1 §3.7 abstraction was not done. The classifier is duplicated inline in `Spo2Sequencer.kt:87` and `ProfileStore.kt:61-64`. The two locations agree today, so this is not a bug; it is a maintenance hazard for the day someone changes one and not the other (especially when Phase 2's HR-band policy lands).

**Fix shape:** at minimum, lift the `family = when(activityState) { ... }` into a single helper that both call sites consume. The full interface from v7.1 §3.7 is preferable for the Phase 2 swap, but the helper-function form is sufficient to remove the duplication today.

**Severity:** low. Land when 3.A.1 is being touched (the same code is being edited).

#### 3.D.2 Telemetry diagnostics not emitted

- `spo2.warn_readings_in_example_band_ratio` (v7.1 §3.8) — not published.
- `CEP_SPO2 warn_abstain reason=MIXED_WINDOW` log line (v7.1 §3.7) — not present; mixed-window suppressions are silent.

**Severity:** low. Pure observability. Cheap to land alongside any of the above.

#### 3.D.3 Block comment on the dispatch-transport contract

`CepEngine.handleTierEvent` is correct-by-coincidence with respect to dual-path dedupe, as discussed in 3.C.1. A 5-line block comment at the top of `handleTierEvent` referencing v7.1 §3.10 — explaining that the bus collector intentionally does NOT re-dispatch and that any future change MUST add the LRU before wiring the bus path — is the cheapest possible safeguard against the latent regression.

#### 3.D.4 `CHANGELOG.md` and `ongoing_changes.md` have no SpO₂ entries

Both are empty for this feature. Land entries summarising PR-1 through PR-8 so blame archaeology in 12 months is straightforward.

---

## 4. Recommendations

Ordered by category to match §3, then by ROI within category.

### 4.A — Land before the next field test (correctness)

1. **Fix the `RUN`/`VEHICLE`/`UNKNOWN` baseline-bucketing regression (§3.A.1).** Single change in `ProfileStore.updateSpo2BaselinesEma` plus a mirror in `Spo2Sequencer.onValid`. Add a unit test that asserts a `RUN` sample does not advance any EMA. Highest clinical ROI of anything on this list — and the only item on this list that fixes a current bug rather than future-proofing. While in the same diff, consider 4.D.1 (consolidate the duplicated classifier) and 4.D.2 (`MIXED_WINDOW` log line) since the same code is being touched.

### 4.B — Verification closeout (already on the v7 §1.0.1 list)

2. **Land the JUnit enforcement gate for the six Phase-7 SpO₂ scenarios (§3.B.1).** Assertions already exist in `CEPSimulationService`; the work is forwarding them into `:app:testDebugUnitTest` so CI fails on regression. Highest verification ROI because every PR thereafter inherits the protection.

3. **Capture a fresh runtime `phase7_spo2_summary.json` from a full Phase-7 run (§3.B.2).** Commit the artifact and reference it in the merge note.

4. **On-device validation of the SpO₂ debug panel + full reset behaviour (§3.B.3).** Off-wrist → reset → on-wrist → bootstrap path, end-to-end, on real hardware.

5. **Wider CEP regression run — P1–P6 + all SpO₂ scenarios under runtime (§3.B.4).**

### 4.C — Architectural hardening (no active bug; land when surrounding shape changes or in a slack cycle)

6. **Add the `handleTierEvent` idempotency LRU + decide `Spo2TierEventFifo`'s fate (§3.C.1).** No active bug today (only one path calls `handleTierEvent`), but is the cheapest single safeguard against a future regression when a second dispatch path is added. Either wire a real drain consumer for the FIFO or delete it.

7. **Add the 72 h per-family-per-bucket baseline re-anchor (§3.C.2).** Small `ProfileStore.resetSpo2Baseline(family, bucket)` plus a check in `onValid`. Land when long off-wrist gaps become a routine field flow.

8. **Add altitude-bucket EMA freeze or warm-start (§3.C.3).** Either approach is consistent with v7's R10 mitigation intent. Land before any altitude-mission deployment that crosses bucket boundaries within a single sortie.

### 4.D — Minor / cosmetic (cheap; land opportunistically)

9. **Consolidate the `BaselineSelectionPolicy` classifier into one helper (§3.D.1).** Lift `family = when(activityState) { ... }` to a single function used by both `Spo2Sequencer` and `ProfileStore`. Phase 2's HR-band policy will benefit; the full interface from v7.1 §3.7 is preferable but a shared helper is sufficient.

10. **Emit telemetry diagnostics (§3.D.2):** `spo2.warn_readings_in_example_band_ratio` and the `CEP_SPO2 warn_abstain reason=MIXED_WINDOW` log line. Pure observability.

11. **Add a block comment to `CepEngine.handleTierEvent` documenting the dispatch-transport contract (§3.D.3).** Five lines referencing v7.1 §3.10. Trivial, but it is the lowest-effort safeguard against the latent dual-path regression.

12. **Update `docs/CHANGELOG.md` and `docs/ongoing_changes.md` with SpO₂ entries (§3.D.4).** Both are empty for this feature.

---

## 5. Bottom Line

SpO₂ implementation is **largely complete**. The reviewed v7.1 deltas have landed, including all of the parts that affect clinical behaviour: Warn upper-curve guard, mixed-window WARN abstain (real, working machinery in `resolveActiveBaselineSelection` plus the `dominantWindowFamily != null` gate in the WARN predicate), Emergency corroboration, Alert disjunction, dual baselines per altitude bucket, and reviewed Phase-7 simulation scenarios with assertions and a per-run summary artifact.

What remains is one current correctness gap (RUN/VEHICLE/UNKNOWN baseline bucketing — recommendation #1, ship before the next field test), four verification-closeout items the v7 plan already tracks (JUnit gating, runtime artifact capture, on-device validation, regression run), and three architectural hardening opportunities that are not active bugs and can be sequenced behind the surrounding feature shape.

This memo is best read as an audit reference, not a status of record. The status of record is §1.0 of `docs/spo2_implementation_plan_v7.md`.
