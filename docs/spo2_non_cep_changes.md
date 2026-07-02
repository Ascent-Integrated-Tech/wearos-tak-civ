# SpO₂ Implementation: Non-CEP Code Changes

This document inventories the SpO₂ implementation work that happened **outside** the `app/src/main/java/com/aitech/weartak_civ/cep/` subtree.

It is intended to answer two questions:

1. Which non-CEP files were changed for the SpO₂ feature?
2. What was the purpose of each of those changes?

Scope notes:

- Included: files under `service/`, `data/`, `presentation/`, and other non-CEP locations that were changed to support the SpO₂ feature.
- Excluded: the CEP engine, CEP payload/topic plumbing, rules JSON, and anything under `app/src/main/java/com/aitech/weartak_civ/cep/`.
- Important exception: [Spo2Sequencer.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/Spo2Sequencer.kt:1) is included here because it lives under `service/`, not under `cep/`, even though it is part of the SpO₂ alerting pipeline.

## Summary

The SpO₂ work outside CEP falls into five groups:

- **Acquisition and sensor-session logic** in `SkinTempService`
- **Sequencing and tier decision logic** in `Spo2Sequencer`
- **Repository/state exposure** in `DataRepository`
- **Activity confidence publishing** in `ActivityRecognitionService`
- **Simulation and validation harness work** in `CEPSimulationService`
- **UI/debug visibility** in `PhysioAlertsActivity`

The non-CEP work exists to do four things:

- get trustworthy SpO₂ readings from the Samsung tracking stack
- turn those readings into a service-owned tier state machine before CEP dispatch
- expose live SpO₂ state to UI/debug surfaces
- provide a simulation path to validate the behavior

## File-By-File Changes

### 1. [app/src/main/java/com/aitech/weartak_civ/service/SkinTempService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/SkinTempService.kt:118)

This is the largest non-CEP SpO₂ change surface. It now owns the runtime measurement session and feeds validated samples into the sequencer.

#### What changed

- Added SpO₂ timing/config constants:
  - `SPO2_TIMEOUT_MS`
  - `SPO2_BACKOFF_AFTER_TIMEOUT_MS`
  - `SPO2_MIN_GAP_MS`
  - `SPO2_PPG_RED_SUSTAIN_REQUIRED_MS`
  - `SPO2_EMIT_FRESHNESS_MS`
  - `SPO2_STATUS_TIMEOUT`
  - `ACTIVITY_STILL_CONFIDENCE_MIN`
  - See [SkinTempService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/SkinTempService.kt:118)

- Added cached SpO₂ / PPG state used for validity and sequencing:
  - `lastSpo2Pct`
  - `ppgRedFirstContinuouslyOkSinceMs`
  - `ppgRedSustainedAtPauseMs`
  - `ppgPauseStartedAtMs`
  - `lastSpo2EmittedTs`
  - `lastSpo2InvalidReason`
  - `spo2Sequencer`
  - See [SkinTempService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/SkinTempService.kt:160)

- Added pause/resume accounting for continuous PPG during on-demand SpO₂ sessions:
  - `pausePpgContinuousForSpo2()`
  - `resumePpgContinuousAfterSpo2()`
  - See [SkinTempService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/SkinTempService.kt:430)

- Reworked the strict SpO₂ listener to:
  - accept only `status == 2` as a valid completed reading
  - compute `activityState`, `motionOk`, `ppgRedSustainedMs`, and `altitudeBucket`
  - ingest `activity.confidence`, `spo2.status_ok`, and `spo2.ppg_red_sustained_ms`
  - call `spo2Sequencer.onValid(...)`
  - push live values into `DataRepository`
  - track timeout/error invalid paths
  - See [SkinTempService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/SkinTempService.kt:517)

- Instantiates `Spo2Sequencer` in `onCreate()` and wires its publish callback to:
  - `cepEngine.handleTierEvent(payload)`
  - `DataRepository.storeLatestSpo2TierEvent(payload)`
  - `CepBus.emit(Topics.SPO2_TIER_EVENT, ...)`
  - See [SkinTempService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/SkinTempService.kt:1026)

- Added reset/clear behavior on off-wrist:
  - `spo2Sequencer.reset(ResetReason.OFF_WRIST)`
  - clears cached SpO₂ / tier event repository state
  - resets PPG sustain accounting
  - See [SkinTempService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/SkinTempService.kt:1180)

- Added helper methods for:
  - pause-safe validation sustain calculation: `ppgRedSustainedForValidation(...)`
  - altitude bucket derivation from pressure: `currentAltitudeBucket()`
  - repository/UI debug state updates: `updateSpo2DebugState()`
  - See [SkinTempService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/SkinTempService.kt:1236)

- Updated the 1 Hz CEP emit path to publish fresh `Spo2Payload` and `PpgPayload` values with the new SpO₂/PPG fields:
  - freshness gate on `lastSpo2Ts`
  - `Spo2Payload(... statusOk, motionOk, ppgRedSustainedMs, ppgAmplitude, ppgQuality)`
  - `PpgPayload(... ppgRedSustainedMs, ts)`
  - live repository write for latest SpO₂
  - See [SkinTempService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/SkinTempService.kt:1280)

#### Purpose

- Make SpO₂ collection reliable on Samsung hardware.
- Prevent false-valid readings by requiring:
  - successful SpO₂ completion
  - stillness/activity confidence
  - sustained PPG-red evidence
- Avoid over-crediting valid PPG time while continuous PPG is paused during the on-demand SpO₂ session.
- Provide the sequencer with the context it needs to make tier decisions.
- Surface live and debug state to the rest of the app.

---

### 2. [app/src/main/java/com/aitech/weartak_civ/service/Spo2Sequencer.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/Spo2Sequencer.kt:1)

This is a new non-CEP service-side component. It owns the SpO₂ tier state machine before CEP dispatch.

#### What changed

- Added `Spo2SequencerConfig` with the core tuning knobs:
  - ring size
  - consecutive N
  - thresholds
  - hysteresis
  - sustain requirements
  - trauma-profile thresholds
  - See [Spo2Sequencer.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/Spo2Sequencer.kt:13)

- Added internal data types:
  - `InvalidReason`
  - `ResetReason`
  - `Spo2ValidReading`
  - `Spo2SequencerSnapshot`
  - See [Spo2Sequencer.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/Spo2Sequencer.kt:33)

- Implemented `onValid(...)` to:
  - reject out-of-range or invalid readings
  - maintain a ring of valid readings including family and altitude bucket
  - update baselines via `ProfileStore`
  - compute active family / active bucket over the last-N window
  - evaluate Warn / Alert / Emergency predicates
  - publish ENTER / HELD / CLEAR tier events
  - See [Spo2Sequencer.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/Spo2Sequencer.kt:54)

- Implemented reviewed predicate behavior:
  - Alert: `all < 90` plus `(baseline-4 OR high-risk OR provisional bootstrap)`
  - Warn: baseline-drop plus upper-curve guard (`all >= 90`)
  - Emergency: `<88` corroborated by `<90` within 2 minutes or high-risk profile
  - Mixed-window WARN abstain through `activeFamily == null`
  - See [Spo2Sequencer.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/Spo2Sequencer.kt:111)

- Implemented clear and outage logic:
  - hysteresis-based clear after sustained recovery
  - long sensor outage clear
  - explicit reset on service stop / off-wrist
  - See [Spo2Sequencer.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/Spo2Sequencer.kt:138)

- Implemented `snapshot()` so the service/UI can expose current sequencer state.

#### Purpose

- Keep clinical/tier sequencing logic in one place instead of scattering it across CEP rules and sensor callbacks.
- Enforce the PDF/v7/v7.1 decision logic before dispatch.
- Track consecutive valid readings rather than trying to infer tier state from loose window features later.
- Provide a service-owned source of truth for current tier state and ring size.

---

### 3. [app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt:142)

This file was extended so the app can observe live SpO₂ state outside CEP internals.

#### What changed

- Added `Spo2DebugState` data model for read-only UI/debug consumption.
- Added state flows:
  - `latestSpo2`
  - `latestSpo2TierEvent`
  - `latestSpo2DebugState`
  - See [DataRepository.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt:482)

- Added repository update functions:
  - `storeLatestSpo2(...)`
  - `storeLatestSpo2TierEvent(...)`
  - `storeLatestSpo2DebugState(...)`
  - See [DataRepository.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt:1372)

- Added `setUserRiskProfile(...)`:
  - persists the risk profile via `ProfileStore`
  - triggers `CepEngine.updateSpo2Profile()`
  - See [DataRepository.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt:1384)

#### Purpose

- Expose live SpO₂ measurement and tier state to UI/debug screens without coupling them directly to CEP internals.
- Give the app a place to observe the current SpO₂ debugging snapshot.
- Provide a repository-level write path for risk-profile changes that affect sequencer thresholds and baseline selection.

---

### 4. [app/src/main/java/com/aitech/weartak_civ/service/ActivityRecognitionService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/ActivityRecognitionService.kt:107)

The SpO₂ work required activity confidence to become part of the published activity signal.

#### What changed

- The initial emitted activity state now includes `confidence = 0` for `UNKNOWN`.
- Live emitted activity updates now include Samsung’s activity confidence on `ActivityPayload`.
- See [ActivityRecognitionService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/ActivityRecognitionService.kt:107) and [ActivityRecognitionService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/ActivityRecognitionService.kt:199)

#### Purpose

- SpO₂ acquisition only trusts readings taken while the user is still enough.
- Publishing activity confidence allows the service and CEP path to agree on the stillness threshold.
- Without this, SpO₂ Alert/Warn gating would depend on a private service field with no observable external feature contract.

---

### 5. [app/src/main/java/com/aitech/weartak_civ/service/CEPSimulationService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/CEPSimulationService.kt:319)

This file was extended to simulate and validate the SpO₂ behavior.

#### What changed

- Added a dedicated Phase 7 SpO₂ scenario run loop.
- Added reviewed SpO₂ scenarios such as:
  - `SPO2_TRAUMA_HIGH_RISK_ESCALATION`
  - `SPO2_EMERGENCY_CORROBORATION_PATH`
  - `SPO2_MIXED_WINDOW_WARN_ABSTAIN`
  - `SPO2_LOW_BASELINE_WARN_CASE`
  - `SPO2_LONG_SENSOR_OUTAGE_CLEAR`
  - `SPO2_TRANSPORT_DUAL_PATH_SAFETY`
  - See [CEPSimulationService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/CEPSimulationService.kt:319) and [CEPSimulationService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/CEPSimulationService.kt:521)

- Extended the scenario model so SpO₂ runs can vary:
  - SpO₂ pattern
  - baseline
  - risk context
  - PPG quality
  - stillness/confidence
  - wrist state
  - See the `ScenarioConfig` expansion beginning at [CEPSimulationService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/CEPSimulationService.kt:536)

- Added expected-vs-observed rule assertions for SpO₂ scenarios.
- See [CEPSimulationService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/CEPSimulationService.kt:1991)

#### Purpose

- Provide an executable validation path for the SpO₂ rules and sequencer behavior.
- Catch regressions in tiering logic without depending only on manual watch testing.
- Validate the reviewed v7.1 edge cases such as corroboration, mixed windows, and low-baseline Warns.

---

### 6. [app/src/main/java/com/aitech/weartak_civ/presentation/PhysioAlertsActivity.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/presentation/PhysioAlertsActivity.kt:355)

This screen was extended to expose SpO₂ runtime/debug state.

#### What changed

- Collects repository flows for:
  - `latestSpo2`
  - `latestSpo2TierEvent`
  - `latestSpo2DebugState`
  - See [PhysioAlertsActivity.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/presentation/PhysioAlertsActivity.kt:355)

- Adds two visible metrics to the expanded card:
  - current SpO₂ percent
  - current SpO₂ tier
  - See [PhysioAlertsActivity.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/presentation/PhysioAlertsActivity.kt:376)

- Adds a read-only debug section showing:
  - active baseline and family
  - active altitude bucket
  - provisional flag
  - ring size
  - last invalid reason
  - See [PhysioAlertsActivity.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/presentation/PhysioAlertsActivity.kt:415)

#### Purpose

- Make the live SpO₂ state observable without digging through CEP internals or logcat.
- Give field testers and developers enough state to confirm whether a missing or unexpected alert is caused by:
  - low confidence / invalid readings
  - provisional baseline
  - wrong baseline family
  - wrong altitude bucket
  - empty / reset sequencer ring

---

## Files Considered But Not Counted As Non-CEP Changes

These files were touched for SpO₂ but are intentionally outside the scope of this document because they live inside the CEP subtree:

- `CepEngine.kt`
- `ProfileStore.kt`
- `Topics.kt`
- `TopicRegistry.kt`
- `Spo2Payload.kt`
- `PpgPayload.kt`
- `ActivityPayload.kt`
- `Spo2TierEventPayload.kt`
- `Spo2TierEventFifo.kt`
- `cep_rules.json`

Those changes are covered by the consolidated implementation plan in:

- [docs/spo2_implementation_plan_v7.md](/home/ghost/Documents/Ascent/WearOS/WEAR-441/docs/spo2_implementation_plan_v7.md:1)

## Practical Takeaway

Outside CEP, the SpO₂ implementation added:

- a service-owned acquisition and validation pipeline
- a dedicated service-layer sequencer/state machine
- repository/debug exposure
- activity-confidence publishing
- simulation coverage
- a visible UI/debug panel

That non-CEP work is what makes the CEP-side rules usable in practice. Without it, the CEP implementation would have had no trustworthy sensor session control, no context-rich valid-reading gate, no live observability, and no realistic simulation path.
