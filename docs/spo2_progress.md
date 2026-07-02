# SpO2 Progress

**Date:** 2026-04-25  
**Branch:** `WEAR-416`  
**Purpose:** summarize the remaining SpO2 issues after the current implementation pass and define the next hardening steps.

## Current State

The SpO2 feature is largely implemented end-to-end:

- `SkinTempService` drives the live acquisition path.
- `Spo2Sequencer` handles tier evaluation and baseline selection.
- `ProfileStore` now abstains from baseline updates for unclassified activity.
- `CepEngine` publishes tier events into CEP and exposes active-baseline features.
- `PhysioAlertsActivity` exposes live SpO2 debug state.
- `CEPSimulationService` can run individual phases or all phases.
- `Spo2Phase7Runner` runs the reviewed Phase-7 SpO2 scenarios and writes `phase7_spo2_summary.json`.

The remaining work is not feature invention. It is verification, alert semantics, and simulation harness correctness.

## What The Logs Show

From the latest Phase-7 runs:

- The baseline-abstain regression is fixed and now passes.
- Phase selection works.
- The runner publishes valid SpO2 tier events.
- CEP does fire SpO2 rules in the simulation and real runtime.
- The current observer and scenario semantics still need tightening.

## Remaining Issues

### 1. Phase-7 observer counting is too loose

The Phase-7 harness was counting active alerts across repeated polls instead of first activation only. That inflated counts while a rule remained active.

Fix:

- Count first activation only.
- Keep a `seenRuleIds` set per scenario.
- Record a rule once when it first appears in `AlertRegistry`, not on every poll.

### 2. SpO2 tier overlap is still too permissive

The logs show `WARN`, `ALERT`, and `EMERGENCY` can all become active in the same scenario window when the conditions overlap.

This is not a duplicate-rule bug. It is a tier-exclusivity issue.

Fix:

- Decide whether tier priority is required.
- If yes, enforce it explicitly:
  - `EMERGENCY` suppresses `ALERT` and `WARN`
  - `ALERT` suppresses `WARN`
  - `WARN` is only allowed when higher tiers are inactive
- If overlap is intended, update the simulation expectations to match that product behavior.

### 3. Simulation state isolation must remain explicit

The Phase-7 runner now resets rule timers, dispatch throttle state, alert registry state, and SpO2 tier-event features. That should remain part of the harness.

Keep:

- `AlertRegistry` reset
- rule timer reset
- action throttle reset
- SpO2 tier-event feature reset
- scenario-specific baseline seeding control

### 4. Real-world clear behavior should stay verified

The runtime has a clear path, but it is separate from alert activation.

Need to keep validating:

- SpO2 alerts trigger when thresholds are crossed.
- clear behavior happens through the intended clear path.
- alerts do not remain stale after recovery or timeout.

## Recommended Next Steps

1. Tighten `Spo2Phase7Runner` counting to first-fire semantics only.
2. Decide and encode tier exclusivity for `WARN` / `ALERT` / `EMERGENCY`.
3. Re-run Phase 7 and confirm the summary artifact is stable.
4. Keep the reset hooks in place so scenarios do not contaminate one another.
5. Run a final device validation pass for live debug state and reset behavior.

## Working Target

Current practical assessment:

- Implementation completeness: about 90%
- Validation and closeout: about 70%
- Production-ready confidence: about 75%

That is good enough to finish with a focused hardening pass, not a redesign.

