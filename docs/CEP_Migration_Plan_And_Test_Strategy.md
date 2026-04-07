**CEP Multi-Rate Migration**

Phase Plan, Work Items & Simulation Test Strategy

**Project** WEAR-441-HZ **Date** 2026-03-19 **Status PENDING IMPLEMENTATION**

*Design approved. Do not implement until all sections are reviewed by the team.*

**Table of Contents**

**Overview**

This document is the single source of truth for the CEP multi-rate migration. It combines the approved engineering plan (what must change and why) with a concrete simulation test strategy (how we verify each change). The migration is divided into five sequential phases. Phases 1 and 2 are the most critical; later phases build on their correctness.

The core problem: the CEP engine assumes exactly one sensor sample arrives per tick. When any sensor delivers at above 1 Hz, sustain timers fire early, window features span the wrong time range, slopes are normalized incorrectly, and freshness ages are unreliable. The five phases address these problems incrementally, with Phase 1 being safe to merge independently before any structural changes begin.

  ----------- ----------------------------------- ---------------------------------------------------------- --------------------
  **Phase**   **Title**                           **Goal**                                                   **Merge After**
  **1**       Fix Timing Semantics                Wall-clock sustain + evt.ts propagation                    Standalone (first)
  **2**       Time-Based Windows & Slopes         Remove tick-rate coupling from ring queries and slopes     Phase 1
  **3**       Decouple Feature Compute            Feature update cadence independent of rule eval cadence    Phase 2
  **4**       Native-Rate Ingestion + Freshness   Sensors at native Hz; stale values do not satisfy rules    Phase 3
  **5**       Higher Evaluation Rate              Increase eval responsiveness after semantics are correct   Phase 4
  ----------- ----------------------------------- ---------------------------------------------------------- --------------------

**CEPSimulationService --- Registration & Usage**

**Registration**

CEPSimulationService must be declared in AndroidManifest.xml as a foreground service before any simulation-based test can run. It is currently implemented but may not be registered. Add the following inside the \<application\> block:

+-----------------------------------------------------------------------------------------------------+
| \<service android:name=\".service.CEPSimulationService\" android:foregroundServiceType=\"dataSync\" |
|                                                                                                     |
| android:exported=\"false\" /\>                                                                      |
+-----------------------------------------------------------------------------------------------------+

Start and stop via the companion methods from any Activity or Service:

-   CEPSimulationService.start(context) --- launches as foreground service

-   CEPSimulationService.stop(context) --- sends ACTION\_STOP intent

-   CEPSimulationService.isRunning --- AtomicBoolean, readable from any thread

**Current Capabilities**

The simulation service can today:

-   Emit all 16 payload types to CepBus on every tick

-   Control tick pace via tickMs (controls delay() between ticks)

-   Control signal values, slopes (hrSlopePerTick, skinSlopePerTickC, etc.)

-   Two-phase HR: entryHr switches to exitHr at exitStartTick

-   Apply Gaussian jitter to numerical signals via jitterD() / jitterF()

**What It Cannot Do Today --- Infrastructure Gaps**

The following capabilities are missing and must be added before specific test phases. See Appendix A for the full gap register.

-   E1: No programmatic pass/fail --- all assertions require logcat reading

-   E4: No per-signal cadence --- all signals emit at the same tickMs rate

-   E5: No timestamp injection --- ts always = System.currentTimeMillis() at emission

-   E6: No gap or burst simulation --- cannot stop or burst individual signals

-   E7: No deterministic RNG --- jitter results differ across runs

**Test Execution Approach**

At each phase: add the new Scenario enum entries, add ScenarioConfig branches with the parameters specified in each phase section below, and observe results via logcat (until E1 is implemented). The simulation runs continuously cycling through all active scenarios --- comment out scenarios not being tested to reduce noise. The ScenarioConfig parameters listed in each phase section below are the exact values that must be set for that phase\'s tests.

  ------------- -------------------------- ------------------------------
  **PHASE 1**   **Fix Timing Semantics**   **CRITICAL --- Merge first**
  ------------- -------------------------- ------------------------------

**Objective**

Replace tick-count sustain with wall-clock sustain in TriggerTimer. Fix the single-line evt.ts omission in CepEngine. Parse new JSON fields in RuleCompiler. These three changes are safe to merge as a unit without any structural changes to rings, windows, or slopes.

**Work Items**

  -------------------------- ---------------------------------------------------------------------------------------------------- ---------- -----------
  **File**                   **Change Required**                                                                                  **Risk**   **Phase**
  **TriggerTimer.kt**        Replace activeFor++ with conditionTrueStartMs wall-clock sustain. Identical public signature.        Low        1
  **CepEngine.kt**           Pass evt.ts to windows.ingest() --- single argument addition on line 72.                             Low        1
  **RuleEngine.kt**          No change to call site. effMain/effWarn still passed in seconds; TriggerTimer converts internally.   None       1
  **RuleCompiler.kt**        Parse rule\_eval\_interval\_ms from engine block. Parse max\_age\_sec per feature def.               Low        1
  **FeatureDef classes**     Add maxAgeSec: Long? = null to Window, MultiWindow, and SlopeMulti data classes.                     Low        1
  **cep\_rules.json**        Add rule\_eval\_interval\_ms to engine block. Add max\_age\_sec to time-sensitive features.          None       1
  **CEPSimulationService**   Add P1-T1 through P1-T6 Scenario entries with ScenarioConfig variants.                               Low        1
  -------------------------- ---------------------------------------------------------------------------------------------------- ---------- -----------

**ScenarioConfig Parameters for Phase 1 Tests**

  ------------------------------ ----------------------------------------------------------------------------
  **ScenarioConfig Parameter**   **Value / Notes**
  **ticks**                      15 (baseline P1-T1), 25 (2 Hz P1-T2), 45 (4 Hz P1-T3), 350 (warn P1-T4)
  **tickMs**                     1000 (1 Hz), 500 (2 Hz), 250 (4 Hz) --- controls emission AND eval cadence
  **entryHr**                    185.0 for \>HRmax tests; 38.0 for bradycardia tests
  **activity**                   \"RUN\" for exercise rules; \"STILL\" for resting rules
  **sleeping**                   false for all Phase 1 tests
  **onWrist**                    true (OffBodyPayload)
  **exitHr / exitStartTick**     Set exitHr to safe value; exitStartTick beyond the expected fire tick
  **NEW: tsOffsetMs**            Phase 1 does not require timestamp injection. Leave at default 0.
  ------------------------------ ----------------------------------------------------------------------------

**Test Cases**

  ---------------------------------------- ------------------------------------------------------------------------------------------------------------------------------------------------------------------
  **P1-T1** --- Baseline sustain at 1 Hz   
  **Purpose**                              Establishes the pre-migration regression baseline. Must pass before and after Phase 1 to confirm no regression at the canonical 1 Hz rate.
  **Inputs**                               HeartRatePayload(185 bpm), ActivityPayload(RUN), SleepPayload(false), OffBodyPayload(true). Rule: hr\_hrmax\_exercise\_60s\_emergency (sustained\_for\_sec: 10).
  **Timing**                               ticks = 15, tickMs = 1000 (15 real seconds). Profile: age=35, hrRest=60.
  **Expected**                             Alert fires between t=10s and t=11s. AlertRegistry.allActive() contains rule ID by tick 11.
  **Pass When**                            Alert present in AlertRegistry by tick 12. Not present at tick 9.
  **Catches**                              Any TriggerTimer change that breaks the canonical 1 Hz behavior.
  **Sim Status**                           ✓ Runnable today
  ---------------------------------------- ------------------------------------------------------------------------------------------------------------------------------------------------------------------

  -------------------------------------------------------------------- -----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  **P1-T2** --- 2 Hz eval --- sustain must NOT fire early (critical)   
  **Purpose**                                                          Directly validates the activeFor++ bug. Before Phase 1, tick-count sustain fires in 5 real seconds at 2 Hz. Wall-clock sustain must fire at 10 real seconds regardless of eval cadence.
  **Inputs**                                                           Identical to P1-T1. Same rule. Only tickMs changes.
  **Timing**                                                           ticks = 25, tickMs = 500 (12.5 real seconds). Same rule: sustained\_for\_sec: 10.
  **Expected**                                                         BEFORE fix: alert fires at tick 10 = 5 real seconds (bug confirmed). AFTER fix: alert fires at tick 20 = 10 real seconds.
  **Pass When**                                                        Post-fix: no alert before 10 real seconds. Alert present within 11 real seconds.
  **Catches**                                                          Tick-count sustain accumulating at twice the real-world rate at 2 Hz.
  **Sim Status**                                                       ✓ Runnable today
  -------------------------------------------------------------------- -----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

  --------------------------------------------------------- ---------------------------------------------------------------------------------------------------------------------------------
  **P1-T3** --- 4 Hz eval --- sustain must NOT fire early   
  **Purpose**                                               Extends P1-T2 to 4 Hz. Proves the wall-clock fix is rate-agnostic and holds under the maximum anticipated Phase 5 eval cadence.
  **Inputs**                                                Identical to P1-T1.
  **Timing**                                                ticks = 45, tickMs = 250 (11.25 real seconds). Rule: sustained\_for\_sec: 10.
  **Expected**                                              Alert fires between real second 10 and 11 only. Not at 2.5 seconds (which would be 10 ticks at 250ms).
  **Pass When**                                             Post-fix: no alert before 10 real seconds. Alert present by 11.5 seconds.
  **Catches**                                               Any tick-count residual that survives the Phase 1 refactor at high eval cadences.
  **Sim Status**                                            ✓ Runnable today
  --------------------------------------------------------- ---------------------------------------------------------------------------------------------------------------------------------

  ----------------------------------------------------------------- ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  **P1-T4** --- sustainedForSecWarn fires at wall-clock threshold   
  **Purpose**                                                       Verifies the warn threshold (warnFiredThisEpisode logic) also uses elapsed wall-clock, not tick count. Targeted rule: hr\_rest\_brady\_10min\_emergency (sustained\_for\_sec: 600, sustained\_for\_sec\_warn: 300).
  **Inputs**                                                        HeartRatePayload(38 bpm), ActivityPayload(STILL), OffBodyPayload(true), SleepPayload(false). Baseline (1 Hz) run then 2 Hz run.
  **Timing**                                                        Run A: ticks=350, tickMs=1000. Run B: ticks=700, tickMs=500. Both observe real seconds elapsed.
  **Expected**                                                      Run B (2 Hz): warn fires at real second 300 (not tick 300 = 150 real seconds). Main at real second 600.
  **Pass When**                                                     In Run B, warn fires between real seconds 298 and 302. Main fires between 598 and 602.
  **Catches**                                                       Warn threshold check using activeFor (tick count) rather than elapsed wall-clock.
  **Sim Status**                                                    ✓ Runnable today
  ----------------------------------------------------------------- ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

  ----------------------------------------------------------- ---------------------------------------------------------------------------------------------------------------------------------------------
  **P1-T5** --- evt.ts propagates to FeatureStore timestamp   
  **Purpose**                                                 After CepEngine passes evt.ts to windows.ingest(), FeatureStore.ts\[key\] must reflect sensor measurement time, not engine processing time.
  **Inputs**                                                  HeartRatePayload with explicit ts = currentTimeMs - 15000 (15 seconds in the past). Query FeatureStore.ageSeconds() immediately.
  **Timing**                                                  Single event injection. FeatureStore read immediately after ingest.
  **Expected**                                                featureStore.ageSeconds(\'heart\_rate\', nowMs) ≈ 15 seconds. Without fix: ≈ 0 seconds.
  **Pass When**                                               Reported age \>= 14 seconds and \<= 16 seconds.
  **Catches**                                                 evt.ts not forwarded into windows.ingest(); freshness age artificially small for back-dated sensor events.
  **Sim Status**                                              ✗ Blocked --- infrastructure gap listed
  ----------------------------------------------------------- ---------------------------------------------------------------------------------------------------------------------------------------------

  ------------------------------------------------------------------------ ------------------------------------------------------------------------------------------------------------------------------
  **P1-T6** --- holdDownSec already wall-clock --- confirm no regression   
  **Purpose**                                                              holdDownSec uses (now - lastFireAt) \>= holdDownSec \* 1000 --- already correct. Confirm Phase 1 refactor does not break it.
  **Inputs**                                                               HR above threshold. After first alert fires, HR stays above threshold. Rule with holdDownSec \> 0.
  **Timing**                                                               ticks = 200, tickMs = 500 (100 real seconds). Enough time for fire + hold-down + second window.
  **Expected**                                                             Second alert does not fire within holdDownSec real seconds of first alert, at both 1 Hz and 2 Hz.
  **Pass When**                                                            Second alert timestamp - first alert timestamp \>= holdDownSec \* 1000 ms.
  **Catches**                                                              Any regression in holdDown wall-clock logic introduced during Phase 1 refactor.
  **Sim Status**                                                           ✓ Runnable today
  ------------------------------------------------------------------------ ------------------------------------------------------------------------------------------------------------------------------

  ------------- --------------------------------- -------------------
  **PHASE 2**   **Time-Based Windows & Slopes**   **After Phase 1**
  ------------- --------------------------------- -------------------

**Objective**

Add timestamps to ring buffers and replace count-based window queries (tailByTimeMs) and lookbacks (getBackByTimeMs). Add lsqSlopeTimestamped to MathOps. Remove the tickHz proxy from slope normalization. This phase depends entirely on Phase 1 being complete.

**Work Items**

  -------------------------- ------------------------------------------------------------------------------------------------------------------------------------------------------------- ---------- -----------
  **File**                   **Change Required**                                                                                                                                           **Risk**   **Phase**
  **Rings.kt**               Add LongArray tsBuf parallel to buf. Add tailByTimeMs(), tailWithTimestamps(), getBackByTimeMs(). Keep all existing methods.                                  Medium     2
  **WindowStore.kt**         Replace val n = windowSec \* tickHz + tail(n) with tailByTimeMs(). Pass tsMs to all ring.add() calls. Update computeSlopeMulti to use tailWithTimestamps().   Medium     2
  **MathOps.kt**             Add lsqSlopeTimestamped(samples: List\<Pair\<Double,Long\>\>). Existing lsqSlope() untouched.                                                                 Low        2
  **FeatureStore.kt**        Add doubleIfFresh(key, maxAgeMs, nowMs) as additive overload. Existing double() untouched.                                                                    Low        2
  **RingConfig.kt**          Update maxSecondsNumeric capacities to account for maximum expected sensor Hz per signal.                                                                     Low        2
  **CEPSimulationService**   Add emitEveryNTicks: Map\<String,Int\> to ScenarioConfig for per-signal cadence. Add P2 scenarios.                                                            Medium     2
  -------------------------- ------------------------------------------------------------------------------------------------------------------------------------------------------------- ---------- -----------

**ScenarioConfig Parameters for Phase 2 Tests**

  ------------------------------ ---------------------------------------------------------------------------------------------------
  **ScenarioConfig Parameter**   **Value / Notes**
  **emitEveryNTicks (NEW)**      Map\<String,Int\> --- e.g. {HEART\_RATE: 1, SKIN\_TEMP: 5} for HR at full rate, skin temp at 0.2x
  **tickMs**                     500 for 2 Hz delivery tests; 5000 for 0.2 Hz skin temp tests
  **skinSlopePerTickC**          -0.75 for 0.2 Hz delivery test (0.15 C/s × 5s per tick)
  **hrSlopePerTick**             +1.0 for slope accuracy test (1 bpm/sec × tickMs)
  **ticks**                      120 for window tests; 130 for delta tests; 300 for sparse slope tests
  **NEW: tsOffsetMs**            Needed for P2-T5 jitter test --- small ±random offset per event
  ------------------------------ ---------------------------------------------------------------------------------------------------

**Note on Infrastructure Gaps**

P2-T1 through P2-T5 all require either per-signal cadence (E4), FeatureStore read access (E2), or timestamp injection (E5). These tests cannot be run until those infrastructure items are added to CEPSimulationService. Schedule E4 and E2 as the first implementation tasks within Phase 2.

**Test Cases**

  -------------------------------------------------------------------- -----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  **P2-T1** --- 30s window at 2 Hz sensor rate spans 30 real seconds   
  **Purpose**                                                          Core window semantics test. val n = windowSec \* tickHz at 1 Hz CEP tick with 2 Hz HR delivery gives n=60 samples covering only 30 real seconds. tailByTimeMs(60\_000) must return all 120 samples.
  **Inputs**                                                           HeartRatePayload at 2 Hz (500ms cadence). Other signals at 1 Hz. Steady HR = 100 bpm. Feature: window\_sec: 60 HR average.
  **Timing**                                                           ticks = 120, tickMs = 500. HR emits every tick; other signals every other tick (via emitEveryNTicks).
  **Expected**                                                         After Phase 2: window feature reflects 120 samples (60 seconds of data). Before fix: only 60 samples (30 seconds).
  **Pass When**                                                        60-second HR window feature non-null with value matching expected average across full 60-second window.
  **Catches**                                                          Count-based tail(n) compressing high-rate delivery to half the declared window duration.
  **Sim Status**                                                       ✗ Blocked --- infrastructure gap listed
  -------------------------------------------------------------------- -----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

  ----------------------------------------------------------------- ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  **P2-T2** --- 0.2 Hz skin temp slope over 20 seconds is correct   
  **Purpose**                                                       Validates tailByTimeMs() and lsqSlopeTimestamped() for sparse delivery. At 0.2 Hz only 4 samples in 20s. Count-based tail(20) grabs 20 samples spanning 100 real seconds.
  **Inputs**                                                        SkinTempPayload at 0.2 Hz (one sample every 5 seconds). Temperature declining at -0.15 C/sec. Feature: features.skin\_temp.slope\_c\_per\_s\_20s (window\_sec: 20).
  **Timing**                                                        ticks = 300, tickMs = 5000 (25 minutes wall-clock with time compression). skinSlopePerTickC = -0.75 (5s per tick × -0.15 C/s).
  **Expected**                                                      After Phase 2: slope ≈ -0.15 C/sec. Before fix: slope distorted because count-based window spans 100 real seconds, diluting the recent trend.
  **Pass When**                                                     features.skin\_temp.slope\_c\_per\_s\_20s within ±0.02 of -0.15 C/sec.
  **Catches**                                                       Count-based window pulling in samples from outside the declared window\_sec range at sparse delivery rates.
  **Sim Status**                                                    ✗ Blocked --- infrastructure gap listed
  ----------------------------------------------------------------- ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------

  ------------------------------------------------------- -----------------------------------------------------------------------------------------------------------------------------------------------------------------------
  **P2-T3** --- HR slope same at 1 Hz and 2 Hz delivery   
  **Purpose**                                             lsqSlopeTimestamped() uses actual timestamps as x-axis; the slopePerSample \* tickHz proxy doubles the result at 2 Hz. Both runs must report the same physical trend.
  **Inputs**                                              HeartRatePayload rising at +1 bpm/second. Run A: tickMs=1000. Run B: tickMs=500.
  **Timing**                                              Run A: ticks=120, tickMs=1000 (120s). Run B: ticks=120, tickMs=500 (60s). Same physical slope.
  **Expected**                                            After Phase 2: computed slope ≈ +1 bpm/sec in both runs. Before fix: Run B slope ≈ +2 bpm/sec (tickHz proxy doubles it).
  **Pass When**                                           Slope in both runs within ±0.05 bpm/sec of each other. Both within ±0.1 of ground truth (+1 bpm/sec).
  **Catches**                                             tickHz proxy error in slopePerSample \* tickHz normalization at delivery rates above 1 Hz.
  **Sim Status**                                          ✗ Blocked --- infrastructure gap listed
  ------------------------------------------------------- -----------------------------------------------------------------------------------------------------------------------------------------------------------------------

  --------------------------------------------------- -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  **P2-T4** --- Delta/lookback uses time, not count   
  **Purpose**                                         At 0.5 Hz delivery, getBack(60 \* tickHz) = getBack(60) but only 30 samples exist after 60 real seconds. getBackByTimeMs(60\_000) finds the correct oldest sample within the time window.
  **Inputs**                                          HeartRatePayload at 0.5 Hz (tickMs=2000). HR starts at 70 bpm, rises to 80 bpm over 60 seconds. Feature: features.hr.delta\_60s (horizon\_sec: 60).
  **Timing**                                          ticks = 130, tickMs = 2000 (260 real seconds). hrSlopePerTick = (80-70)/(60/2) = 0.333 bpm per 2-second tick.
  **Expected**                                        After Phase 2: features.hr.delta\_60s ≈ 10 bpm. Before fix: delta = null (getBack(60) requires 60 samples, only 30 exist).
  **Pass When**                                       features.hr.delta\_60s is non-null and within ±2 bpm of 10 bpm at t=80s.
  **Catches**                                         Count-based getBack(k) silently returning null when sensor delivers below tickHz rate.
  **Sim Status**                                      ✗ Blocked --- infrastructure gap listed
  --------------------------------------------------- -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

  ---------------------------------------------------------- ------------------------------------------------------------------------------------------------------------------------------------------------------------------
  **P2-T5** --- Irregular timestamps produce correct slope   
  **Purpose**                                                lsqSlopeTimestamped() absorbs per-sample delivery jitter because the x-axis is actual elapsed seconds. Index-based lsqSlope accumulates jitter into slope error.
  **Inputs**                                                 SkinTempPayload at nominal 1 Hz with ±300ms per-sample delivery jitter. Declining at -0.10 C/sec.
  **Timing**                                                 ticks = 100, tickMs = 1000 with per-event tsOffsetMs jitter ±300ms. Feature: skin\_temp.slope\_c\_per\_s\_20s.
  **Expected**                                               After Phase 2: slope within ±0.02 C/sec of -0.10 across 5 consecutive windows. Index-based slope drifts outside this tolerance.
  **Pass When**                                              slope in \[-0.12, -0.08\] C/sec across 5 consecutive 20-second windows under jitter.
  **Catches**                                                Index-based x-axis (sample count) amplifying timing jitter into compounding slope error.
  **Sim Status**                                             ✗ Blocked --- infrastructure gap listed
  ---------------------------------------------------------- ------------------------------------------------------------------------------------------------------------------------------------------------------------------

  ------------- -------------------------------------- -------------------
  **PHASE 3**   **Decouple Feature Compute Cadence**   **After Phase 2**
  ------------- -------------------------------------- -------------------

**Objective**

Extract FeatureWindowComputer abstractions so each feature can compute at its own cadence, independent of the rule evaluation loop. This is the most structurally invasive phase but does not change external rule semantics.

**Work Items**

  -------------------------- ------------------------------------------------------------------------------------------------------------------ ---------- -----------
  **File**                   **Change Required**                                                                                                **Risk**   **Phase**
  **CepEngine.kt**           Extract FeatureWindowComputer abstractions. Replace computeFeaturesSafely() with per-feature coroutine dispatch.   High       3
  **WindowStore.kt**         Refactor into FeatureWindowComputer pattern; each feature computes on own cadence.                                 High       3
  **CepConfig.kt**           Add updateIntervalMs: Long = 1000 per FeatureDef.                                                                  Medium     3
  **RuleCompiler.kt**        Parse update\_interval\_ms per feature def.                                                                        Low        3
  **CEPSimulationService**   Add SimulationObserver / result sink for timing assertions. Add P3 scenarios.                                      Medium     3
  -------------------------- ------------------------------------------------------------------------------------------------------------------ ---------- -----------

**Test Cases**

  --------------------------------------------------------------------- -------------------------------------------------------------------------------------------------------------------------------------------------------------
  **P3-T1** --- Slow-computing feature does not block rule evaluation   
  **Purpose**                                                           A feature with update\_interval\_ms = 5000 must not stall rule evals running at 1000ms. Between feature computes, rules see the cached value without delay.
  **Inputs**                                                            Steady HR above threshold. features.skin\_temp.hand\_min\_c\_1m with compute interval = 5s. Rules eval every 1s.
  **Timing**                                                            ticks = 120, tickMs = 1000. Feature computes on ticks 0, 5, 10, 15\... Rules evaluate on every tick.
  **Expected**                                                          Alert fires after 60 real seconds. No evaluation stall between feature computes. Feature is non-null (uses cached value) on off-compute ticks.
  **Pass When**                                                         Alert fires at ≈60s. All 120 tick evals complete within 2x normal duration. No null evaluation errors.
  **Catches**                                                           Feature compute blocking or locking the main rule eval loop; null cached value causing condition to drop during compute gap.
  **Sim Status**                                                        ✗ Blocked --- infrastructure gap listed
  --------------------------------------------------------------------- -------------------------------------------------------------------------------------------------------------------------------------------------------------

  ------------------------------------------------------------------------------ -----------------------------------------------------------------------------------------------------------------------------------------------------------
  **P3-T2** --- Fast-computing feature provides most recent value at rule eval   
  **Purpose**                                                                    Feature computing at 500ms cadence, rules evaluating at 1000ms. Rule must observe the value from the most recent 500ms compute, not the 1000ms-old value.
  **Inputs**                                                                     HR rising across 160 bpm at t=500ms. Feature compute at 500ms. Rule eval at 1000ms.
  **Timing**                                                                     Single 1-second window. HR = 158 at t=0ms, HR = 162 at t=500ms (feature computes). Rule evals at t=1000ms.
  **Expected**                                                                   Rule condition evaluates true at t=1000ms (sees cached 162 bpm from t=500ms compute).
  **Pass When**                                                                  Rule condition is true at the first rule eval after HR crosses the threshold.
  **Catches**                                                                    Cached feature value not being updated between rule evals; rule seeing a 1-cycle-stale fast feature.
  **Sim Status**                                                                 ✗ Blocked --- infrastructure gap listed
  ------------------------------------------------------------------------------ -----------------------------------------------------------------------------------------------------------------------------------------------------------

  -------------------------------------------------------------------- -------------------------------------------------------------------------------------------------------------------------------------------------------
  **P3-T3** --- Feature compute exception does not stall other rules   
  **Purpose**                                                          computeFeaturesSafely() wraps features in try/catch. Phase 3 confirms this extends to FeatureWindowComputer coroutines and does not crash the engine.
  **Inputs**                                                           One sensor delivering NaN or Infinity (set hrSlopePerTick to cause overflow). All other sensors healthy. Multiple rules active.
  **Timing**                                                           ticks = 30, tickMs = 1000.
  **Expected**                                                         Broken feature evaluates to null. Other features update normally. Other rules fire on schedule. No engine crash or stall.
  **Pass When**                                                        Engine continues running for all 30 ticks. Rules not dependent on broken feature fire within expected windows.
  **Catches**                                                          Uncaught exception in a FeatureWindowComputer coroutine propagating and killing the engine loop.
  **Sim Status**                                                       ◑ Partially runnable --- notes in Sim Status
  -------------------------------------------------------------------- -------------------------------------------------------------------------------------------------------------------------------------------------------

  ------------- --------------------------------------- -------------------
  **PHASE 4**   **Native-Rate Ingestion & Freshness**   **After Phase 3**
  ------------- --------------------------------------- -------------------

**Objective**

Allow sensors to deliver at their native rates. Enforce freshness so that stale feature values do not continue to satisfy rule conditions when sensor data has stopped arriving. Gate the legacy HeartrateService path to prevent duplicate alerts.

**Work Items**

  -------------------------- ------------------------------------------------------------------------------------------------------------ ---------- -----------
  **File**                   **Change Required**                                                                                          **Risk**   **Phase**
  **EnvSensorService.kt**    Make EMIT\_INTERVAL\_MS configurable. Remove hard 1000L throttle for high-rate sensors.                      Medium     4
  **HeartrateService.kt**    Gate legacy EvictingQueue path to prevent duplicate alerts with CEP path.                                    Medium     4
  **FeatureStore.kt**        Wire doubleIfFresh() into DerivedEval and JsonLogic.eval() condition path.                                   Medium     4
  **JsonLogic.kt**           Treat null freshness result as false in all comparison operators.                                            Low        4
  **CepBus.kt**              Review DROP\_OLDEST (256-item) buffer. Consider SUSPEND or larger buffer for long-horizon trend features.    Medium     4
  **CEPSimulationService**   Add tsOffsetMs: Long = 0 to ScenarioConfig for timestamp injection. Add signalGapRange for gap simulation.   Medium     4
  -------------------------- ------------------------------------------------------------------------------------------------------------ ---------- -----------

**ScenarioConfig Parameters for Phase 4 Tests**

  ------------------------------ --------------------------------------------------------------------------------------------
  **ScenarioConfig Parameter**   **Value / Notes**
  **NEW: tsOffsetMs**            Negative value (e.g. -15000) to inject events with a past timestamp for freshness tests
  **NEW: signalGapRange**        Map\<String, IntRange\> --- e.g. {HEART\_RATE: 5..50} to silence HR for ticks 5 through 50
  **NEW: burstOnTick**           Map\<String, Int\> --- tick index on which to emit a burst of N events for that signal
  **ticks**                      55 for stale/gap tests; 60 for timer-reset tests
  **entryHr**                    185.0 (above HRmax) for burst and staleness tests
  ------------------------------ --------------------------------------------------------------------------------------------

**Test Cases**

  ------------------------------------------------------- ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  **P4-T1** --- High-rate burst captured at native rate   
  **Purpose**                                             Sub-tick burst of 10 HR events (200 bpm) within 500ms must enter the ring buffer and elevate the window average. CepBus DROP\_OLDEST at 256 items must not silently discard the burst.
  **Inputs**                                              10 HeartRatePayload(200 bpm) events emitted within 500ms. Then HR returns to 80 bpm. burstOnTick = {HEART\_RATE: 5}.
  **Timing**                                              Burst at t=5s. Window feature evaluated at t=6s (next CEP tick). Total: ticks=20, tickMs=1000.
  **Expected**                                            HR window average feature is elevated above 80 bpm for at least one tick following the burst.
  **Pass When**                                           HR window feature \> 80 bpm at tick 6. Non-null.
  **Catches**                                             CepBus DROP\_OLDEST discarding burst events before they reach the ring buffer; ring.add() not receiving all burst samples.
  **Sim Status**                                          ✗ Blocked --- infrastructure gap listed
  ------------------------------------------------------- ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

  ---------------------------------------------------------------- -----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  **P4-T2** --- Stale feature stops satisfying rule after maxAge   
  **Purpose**                                                      With freshness enforcement active, a rule condition on a stale HR feature must become false after max\_age\_sec seconds of silence. Timer must reset.
  **Inputs**                                                       HR = 185 bpm for 5 seconds. Then no HR events for 45 seconds (signalGapRange = {HEART\_RATE: 5..50}). Rule: hr\_hrmax\_exercise\_60s\_emergency (sustained\_for\_sec: 10, max\_age\_sec: 30).
  **Timing**                                                       ticks = 55, tickMs = 1000. HR active ticks 0-4. Gap ticks 5-49. Resume tick 50.
  **Expected**                                                     No alert fires during the gap. After 30 seconds of silence, HR feature becomes null, condition = false, timer resets.
  **Pass When**                                                    AlertRegistry.allActive() is empty at t=50s despite HR being above threshold at t=0-4s.
  **Catches**                                                      Stale feature value persisting indefinitely and continuing to mature the sustain timer during sensor silence.
  **Sim Status**                                                   ✗ Blocked --- infrastructure gap listed
  ---------------------------------------------------------------- -----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

  --------------------------------------------------------------------------------------------- -----------------------------------------------------------------------------------------------------------------------------------------------------
  **P4-T3** --- Sustain resets (not pauses) during freshness gap; full new cycle after resume   
  **Purpose**                                                                                   Timer must reset during the stale period, not pause. After data resumes, a full new sustained\_for\_sec cycle must complete before the alert fires.
  **Inputs**                                                                                    HR = 185 bpm for 8 seconds (approaching sustained\_for\_sec:10). Then 35-second gap. Then HR = 185 bpm again.
  **Timing**                                                                                    Phases: 0-7s active, 8-42s gap (signalGapRange), 43s+ active. ticks = 65, tickMs = 1000.
  **Expected**                                                                                  Alert fires at ≈53s (43s resume + 10s sustain), NOT at ≈45s (completing the old 8s episode with only 2s more).
  **Pass When**                                                                                 Alert fires no earlier than t=52s. Fires no later than t=55s after test start.
  **Catches**                                                                                   Sustain timer pausing rather than resetting during the stale window --- old episode incorrectly completed after data resumes.
  **Sim Status**                                                                                ✗ Blocked --- infrastructure gap listed
  --------------------------------------------------------------------------------------------- -----------------------------------------------------------------------------------------------------------------------------------------------------

  ---------------------------------------------------------------- ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  **P4-T4** --- Legacy HR path does not produce duplicate alerts   
  **Purpose**                                                      HeartrateService has both the legacy EvictingQueue path (lines 253-289) and the CEP path (lines 300-314). AlertRegistry.tryActivate() must prevent duplicates from escaping to the user.
  **Inputs**                                                       HR \> thresholds.hr.high\_bpm for \> sustained\_for\_sec seconds. Both service paths active and running.
  **Timing**                                                       ticks = 700, tickMs = 1000. Rule: hr\_high\_threshold\_resting\_alert (sustained\_for\_sec: 600).
  **Expected**                                                     Exactly one alert in AlertRegistry with category CAT\_HR\_HIGH. No duplicate notification issued.
  **Pass When**                                                    AlertRegistry.allActive().count { it.category == \'CAT\_HR\_HIGH\' } == 1 at all times during the scenario.
  **Catches**                                                      Both legacy and CEP paths firing independently for the same condition; AlertRegistry.tryActivate() not blocking the second.
  **Sim Status**                                                   ◑ Partially runnable --- notes in Sim Status
  ---------------------------------------------------------------- ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

  -------------------------------------------------------------------------------------- ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  **P4-T5** --- CepBus DROP\_OLDEST does not silently lose oldest trend-relevant event   
  **Purpose**                                                                            A 24-hour HRV baseline window needs the oldest ring entries. Under burst load the 256-item bus drops from the oldest end --- exactly the samples needed for long-horizon features.
  **Inputs**                                                                             Burst of 300 events across all 16 signal types emitted in rapid succession. Then steady delivery resumes.
  **Timing**                                                                             Burst at t=0. Feature evaluation at t=1s (next CEP tick). Observe features.hrv.rmssd\_baseline\_24h.
  **Expected**                                                                           HRV baseline feature non-null and reflects steady-state HRV from the burst. No silent sample loss.
  **Pass When**                                                                          features.hrv.rmssd\_baseline\_24h is non-null with a value within ±5ms of the emitted HRV.
  **Catches**                                                                            DROP\_OLDEST on 256-item bus silently discarding the oldest HRV samples needed for long-horizon (24h) windows.
  **Sim Status**                                                                         ✗ Blocked --- infrastructure gap listed
  -------------------------------------------------------------------------------------- ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

  ------------- ------------------------------------- -------------------
  **PHASE 5**   **Safely Increase Evaluation Rate**   **After Phase 4**
  ------------- ------------------------------------- -------------------

**Objective**

With timing semantics fully correct (Phases 1-4), raise rule\_eval\_interval\_ms to improve alert responsiveness. A 30-second sustain must still mean 30 real seconds at any eval cadence. This phase produces no behavioral changes --- only latency improvements.

**Work Items**

  -------------------------- ---------------------------------------------------------------------------------------------------------- ---------- -----------
  **File**                   **Change Required**                                                                                        **Risk**   **Phase**
  **CepEngine.kt**           Make tickHz adjustable at runtime without engine restart. Wire to rule\_eval\_interval\_ms config field.   Low        5
  **cep\_rules.json**        Adjust rule\_eval\_interval\_ms to target rate (e.g. 500ms for 2 Hz). No sustain value changes.            None       5
  **TriggerTimer.kt**        Verify zero tick-count residuals remain after Phase 1. Should be clean --- confirm only.                   None       5
  **CEPSimulationService**   Add SimulationObserver (E1) result sink. Add P5 multi-rate scenario variants.                              Low        5
  -------------------------- ---------------------------------------------------------------------------------------------------------- ---------- -----------

**Test Cases**

  --------------------------------------------------------------------------------------- -----------------------------------------------------------------------------------------------------------------------------------------------------
  **P5-T1** --- 30-second sustain fires at 30 real seconds at 1, 2, and 4 Hz eval rates   
  **Purpose**                                                                             Primary Phase 5 regression test. Wall-clock sustain from Phase 1 must remain correct when CEP eval rate is intentionally raised for responsiveness.
  **Inputs**                                                                              Three runs. Same scenario, same rule (sustained\_for\_sec: 10). Different tickMs only: 1000, 500, 250.
  **Timing**                                                                              Run A: ticks=15, tickMs=1000. Run B: ticks=25, tickMs=500. Run C: ticks=45, tickMs=250.
  **Expected**                                                                            All three runs fire at ≈10 real seconds. None fire early. Each fires within 1 tickMs after the 10-second mark.
  **Pass When**                                                                           Fire time within 1 tickMs of 10 real seconds in all three runs. Cross-run delta \< 500ms.
  **Catches**                                                                             Any tick-count residual that survived Phase 1 manifesting when eval rate is intentionally increased.
  **Sim Status**                                                                          ✓ Runnable today
  --------------------------------------------------------------------------------------- -----------------------------------------------------------------------------------------------------------------------------------------------------

  ----------------------------------------------------------------------- ----------------------------------------------------------------------------------------------------------------------------
  **P5-T2** --- holdDownSec enforced at wall-clock at higher eval rates   
  **Purpose**                                                             holdDownSec already uses wall-clock. Confirm it remains correct after Phase 5 eval rate change and produces no regression.
  **Inputs**                                                              HR above threshold. Alert fires at ≈t=10s. HR stays above threshold throughout. holdDownSec as defined in rule.
  **Timing**                                                              Run A: tickMs=1000. Run B: tickMs=500. Both: ticks=150. Observe time between first and second fire.
  **Expected**                                                            Second fire does not occur within holdDownSec real seconds of first fire at either eval rate.
  **Pass When**                                                           Second alert timestamp - first alert timestamp \>= holdDownSec \* 1000ms in both runs.
  **Catches**                                                             holdDownSec regression at higher eval rates introduced during Phase 5 changes to CepEngine tick loop.
  **Sim Status**                                                          ✓ Runnable today
  ----------------------------------------------------------------------- ----------------------------------------------------------------------------------------------------------------------------

  ------------------------------------------------------------------- ----------------------------------------------------------------------------------------------------------------------------------------------------------------------
  **P5-T3** --- exitSustainSec behavior correct at higher eval rate   
  **Purpose**                                                         checkAndClearAlerts() uses wall-clock elapsed. At 2 Hz eval, exit condition checked twice per second. Alert must still require exitSustainSec real seconds to clear.
  **Inputs**                                                          Alert active. Exit condition (HR drops below threshold) becomes true at time T. exitSustainSec = 10. Run at tickMs=500 (2 Hz eval).
  **Timing**                                                          Observe delta from exit condition first becoming true to alert clearing from AlertRegistry.
  **Expected**                                                        Alert clears after 10 real seconds of exit condition being satisfied. Not after 5 seconds (which would be 10 evals at 500ms).
  **Pass When**                                                       Alert clears between real second 10 and 12 after exit condition first becomes true.
  **Catches**                                                         Any tick-count path in checkAndClearAlerts() that was not corrected in Phase 1.
  **Sim Status**                                                      ✓ Runnable today
  ------------------------------------------------------------------- ----------------------------------------------------------------------------------------------------------------------------------------------------------------------

  ------------------------------------------------------------------------- -----------------------------------------------------------------------------------------------------------------------------------------
  **P5-T4** --- Responsiveness improvement measurable at higher eval rate   
  **Purpose**                                                               The only valid reason to increase eval rate is lower alert latency. Confirm the improvement is real and bounded by tickMs.
  **Inputs**                                                                HR exactly crosses \>HRmax threshold. Rule: hr\_hrmax\_exercise\_60s\_emergency (sustained\_for\_sec: 10). Three runs at 1/2/4 Hz eval.
  **Timing**                                                                Measure wall-clock delta from last tick where condition is false to first tick where alert fires.
  **Expected**                                                              1 Hz: max latency ≈1000ms. 2 Hz: max latency ≈500ms. 4 Hz: max latency ≈250ms. Semantics unchanged.
  **Pass When**                                                             Measured latency \< 1.5 × tickMs at each rate. All three runs fire at same real-world time.
  **Catches**                                                               Eval rate increase having no effect on latency (bottleneck elsewhere) OR producing incorrect early fires.
  **Sim Status**                                                            ✓ Runnable today
  ------------------------------------------------------------------------- -----------------------------------------------------------------------------------------------------------------------------------------

**Appendix A --- Simulation Infrastructure Gaps**

The following gaps must be addressed in CEPSimulationService (and CepEngine/FeatureStore where noted) before the corresponding test phases can run with programmatic assertions. Gaps marked \'Phase 1\' are lowest cost and highest impact --- build E1, E5, and E7 first.

  -------- ---------------------------------------------------------------- --------------------------------------------------------------------------------------------------- ----------------------
  **\#**   **Gap**                                                          **Impact on Tests**                                                                                 **Add Before Phase**
  **E1**   SimulationObserver / result sink                                 Blocks programmatic pass/fail. Without it all assertions require manual logcat.                     Phase 1
  **E2**   FeatureStore.debugSnapshot() external access                     Blocks any test that must assert feature values at a specific point in time.                        Phase 2
  **E3**   TriggerTimer elapsed read accessor                               Blocks asserting \'sustain has been accumulating for X seconds\' at each tick.                      Phase 1
  **E4**   Per-signal emission cadence in ScenarioConfig                    Blocks all Phase 2 mixed-rate window and slope tests (P2-T1 through P2-T5).                         Phase 2
  **E5**   Timestamp injection (tsOffsetMs in ScenarioConfig)               Blocks P1-T5 (evt.ts propagation) and all Phase 4 freshness tests.                                  Phase 1/4
  **E6**   Gap and burst simulation (signalGapRange, burstOnTick)           Blocks P4-T1 (burst), P4-T2 (stale gap), P4-T3 (timer reset), P4-T5 (bus overflow).                 Phase 4
  **E7**   Deterministic RNG (deterministicSeed: Long? in ScenarioConfig)   Without fixed seed, jitter-dependent tests are non-reproducible across runs.                        Phase 2
  **E8**   Simulated time source (injectable nowMs: () -\> Long)            Without time compression, a 600-tick scenario at 1s/tick = 10 real minutes. Blocks CI automation.   Phase 3+
  -------- ---------------------------------------------------------------- --------------------------------------------------------------------------------------------------- ----------------------

**Appendix B --- Test Priority Order**

Run tests in this sequence. Each tier depends on the previous tier passing.

  ---------- -------------------------------- ------------------------------------------------------------------------------------------ ----------------------------------
  **Tier**   **Test IDs**                     **Rationale**                                                                              **Infrastructure Needed**
  **1**      **P1-T1**                        Baseline 1 Hz --- must pass before any migration work begins. Confirms current behavior.   None --- runnable today
  **2**      **P1-T2, P1-T3**                 Critical regression: proves wall-clock fix prevents early fires at 2/4 Hz.                 None --- runnable today
  **3**      **P1-T4, P1-T6**                 Verify warn threshold and holdDown are also correct at higher cadences.                    None --- runnable today
  **4**      **P1-T5**                        evt.ts propagation --- requires timestamp injection (E5).                                  E5: tsOffsetMs in ScenarioConfig
  **5**      **P5-T1, P5-T2, P5-T3, P5-T4**   Phase 5 rate triangle (Phase 1 must be complete). Runnable today post-Phase 1.             None --- runnable after Phase 1
  **6**      **P2-T3**                        Slope at 1 Hz vs 2 Hz --- partial: run two scenarios, compare log output.                  E2: FeatureStore read access
  **7**      **P2-T1, P2-T2, P2-T4, P2-T5**   Full Phase 2 window and slope tests require per-signal cadence.                            E4: emitEveryNTicks; E2; E7
  **8**      **P3-T1, P3-T2, P3-T3**          Phase 3 decoupling tests --- Phase 3 architecture must be complete.                        Phase 3 impl + E1
  **9**      **P4-T4**                        Duplicate alert test --- partially runnable. Requires AlertRegistry inspection.            E1 (or manual logcat)
  **10**     **P4-T1 through P4-T5**          Full Phase 4 freshness and native-rate tests.                                              E5, E6, E1
  ---------- -------------------------------- ------------------------------------------------------------------------------------------ ----------------------------------

Phase 1 tests (Tiers 1--3) are runnable today by adding new Scenario entries with tickMs = 500 and tickMs = 250 variants of any existing high-HR scenario. Start there.
