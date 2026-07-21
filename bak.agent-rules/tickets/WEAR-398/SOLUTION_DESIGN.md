# Section 1: Problem and Success Definition

## Problem
`WEAR-398` addresses false HR physiological alerts when the watch is removed from wrist or placed on charger, where off-body transitions and HR `0` values can trigger low-HR logic.

## Success Definition
The solution is successful when all are true:
1. Off-wrist state does not generate new HR-origin alerts.
2. HR `<= 0` does not generate new HR-origin alerts.
3. Off-wrist state does not keep a valid numeric HR in normal runtime state/UI paths.
4. Active HR-origin alerts auto-clear after sustained off-wrist state (10s default), without immediate flapping.
5. Valid on-wrist HR alerting remains functional.

## Raw Source Provenance
- Requested raw source ticket path: `agent-rules/tickets/WEAR-398/WEAR-398.md` (missing in repo).
- Effective source used: Jira issue `WEAR-398` via shell command `zsh -lic 'details'`.

# Section 2: Current-State Repo Findings

1. Off-wrist state is already captured and broadcast:
- `app/src/main/java/com/aitech/weartak_civ/service/OffBodyDetectService.kt`
- Updates `DataRepository.storeLatestIsDeviceWorn(...)` and posts `IsDeviceWornEvent`.

2. HR data flow into alerts exists in two paths:
- `app/src/main/java/com/aitech/weartak_civ/service/HeartrateService.kt`
  - Emits `Topics.HEART_RATE` to CEP.
  - Stores latest HR in `DataRepository.storeLatestHeartRate(...)`.
- `app/src/main/java/com/aitech/weartak_civ/cep/engine/action/LogActionHandler.kt`
  - Converts CEP rule firings into `addPhysioAndAutoGenAlertRequest(...)`.

3. CEP HR rules are not gated by on-wrist:
- `app/src/main/res/raw/cep_rules.json`
  - HR categories (`CAT_HR_*`) exist and can trigger without explicit `offbody.on_wrist` checks.

4. CEP off-body payload support exists but production emission appears absent:
- `app/src/main/java/com/aitech/weartak_civ/cep/model/payloads/OffBodyPayload.kt`
- `app/src/main/java/com/aitech/weartak_civ/cep/engine/WindowStore.kt`
- `Topics.OFFBODY` emission is visible in `CEPSimulationService`, not in `OffBodyDetectService`.

5. Alert clear mechanism is available by UID:
- `app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt`
  - `clearPhysioAndAutoGenAlertRequestUid(uid)`
- `app/src/main/java/com/aitech/weartak_civ/cep/engine/CepEngine.kt`
  - already uses UID-based clear flow for active CEP alerts.

# Section 3: Key Decisions Captured

## Decision Log
1. `approved` - Suppress HR while off-wrist.
- Source: user clarification in Context Expansion phase.

2. `approved` - Suppress HR `<= 0` as invalid input.
- Source: user clarification in Context Expansion phase.

3. `approved` - Apply suppression to both alert generation and stored/displayed HR behavior.
- Source: user clarification in Context Expansion phase.

4. `approved` - Auto-clear active HR-origin alerts after sustained off-wrist period.
- Source: user requested discussion + approved recommendation.

5. `approved` - Use 10-second sustained off-wrist default for clear behavior.
- Rationale: avoids immediate clear flapping during short transition noise.

6. `deferred` - Emit production `Topics.OFFBODY` into CEP for rule-level on-wrist gating in this ticket.
- Rationale: ticket can be solved with lower-risk service/data-path gating; CEP off-body ingestion integration can be a follow-up hardening task.

# Section 4: Solution Boundaries (Will / Won't)

## Will
1. Add HR ingestion guards in runtime path:
- If off-wrist or HR `<= 0`, do not emit HR to CEP.
- If off-wrist or HR `<= 0`, do not persist a valid numeric HR.

2. Add off-wrist sustained auto-clear for active HR-origin alerts:
- On off-wrist transition, after 10s sustained off-wrist, clear active HR-origin alerts by UID.

3. Keep valid on-wrist HR path unchanged for legitimate alerting.

4. Keep scope limited to existing service/repository/alert pathways.

## Won't
1. No architecture redesign of CEP engine or alert framework.
2. No broad rule-retuning across unrelated physio categories.
3. No unrelated UX/navigation changes.
4. No new feature flags unless needed for safe-disable fallback.

# Section 5: Implementation Contract

## Component Touchpoints
1. `HeartrateService` (`app/src/main/java/com/aitech/weartak_civ/service/HeartrateService.kt`)
- Guard CEP emit and repository HR updates:
  - Require `deviceWorn == true`.
  - Require `localHr > 0`.
- Maintain existing off-wrist handling (`N/A`/sentinel semantics), but ensure no valid numeric persistence while off-wrist.

2. Off-wrist alert clearing coordinator (likely in `HeartrateService`)
- On transition to off-wrist, schedule delayed clear at 10s.
- Before clearing, re-check `latestIsDeviceWorn` still false.
- Clear active HR-origin alerts by iterating `physioAndAutoGenAlertList` and sending `clearPhysioAndAutoGenAlertRequestUid(uid)` for HR categories.
- HR-origin category match set:
  - `CAT_HR_LOW`
  - `CAT_HR_REST_BRADY`
  - `CAT_HR_HIGH`
  - `CAT_HR_ALERT`
  - `CAT_HR_EMERGENCY`

3. No required schema/data-store migration
- Reuse existing `StateFlow`/request-flow mechanisms in `DataRepository`.

## Failure Handling
1. If off-wrist clear timer fires but device is worn again, skip clear.
2. If clear request fails downstream, keep existing queue/retry behavior (do not add new retry framework in this ticket).

## Verification Strategy
Manual verification (no formal test suite in repo):
1. Off-wrist + charger scenario with HR `0` samples:
- Expect no new HR-origin alerts.
- Expect no valid HR shown/stored.

2. Re-wear scenario:
- Expect valid HR to resume and legitimate HR alerts still trigger.

3. Active HR alert + off-wrist transition:
- Expect clear request after 10s sustained off-wrist.
- Expect no immediate clear during brief transition flap.

4. Regression spot checks:
- Non-HR alert categories continue unchanged.

## Safe-disable / Rollback
- Changes are localized and reversible in service-level guards and off-wrist clear block.
- If needed, rollback by removing off-wrist clear coordinator and reinstating prior HR emit/store behavior.

# Section 6: Open Risks and Follow-ups

## Open Risks
1. Off-body sensor jitter can still briefly oscillate; sustained timer mitigates but does not fully eliminate edge-case races.
2. If future HR-origin categories are added, category filter list must be kept in sync.

## Follow-ups (Not in this ticket)
1. Wire production `Topics.OFFBODY` emission from `OffBodyDetectService` to CEP and add explicit rule-level on-wrist gating.
2. Add targeted unit/integration coverage for HR/off-wrist guard logic when test scaffolding is available.

# Section 7: Approval Status

Status: `APPROVED FOR IMPLEMENTATION`

This solution design is ready for review. Implementation must not begin until explicit user approval token is provided:

`APPROVED FOR IMPLEMENTATION`
