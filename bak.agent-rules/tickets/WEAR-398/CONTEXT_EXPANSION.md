# Ticket Intent Summary
- Ticket: `WEAR-398`
- Raw ticket summary from Jira: when putting watch on/off wrist (or on charger), off-body can become true and HR `0` can trigger alerts.
- Core objective: eliminate false low-HR physiological alerts caused by off-wrist and invalid HR readings during wear-state transitions.
- Clarification decisions (confirmed by user):
  - Suppress both HR `0` and all HR processing while off-wrist.
  - Apply suppression to both alert generation and stored/displayed HR behavior.
  - Auto-clear active HR-origin alerts after sustained off-wrist period.

Resolved raw source ticket path used:
- Requested path: `agent-rules/tickets/WEAR-398/WEAR-398.md` (missing).
- Effective source: Jira details retrieved via `zsh -lic 'details'` for issue `WEAR-398`.

# Current Repo Context
Evidence-backed touchpoints relevant to this ticket:

- Off-wrist state source and broadcast:
  - `app/src/main/java/com/aitech/weartak_civ/service/OffBodyDetectService.kt`
  - Uses `Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT`.
  - Writes `latestIsDeviceWorn` via `DataRepository.storeLatestIsDeviceWorn(...)`.
  - Broadcasts `IsDeviceWornEvent` via EventBus.

- Runtime worn-state repository:
  - `app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt`
  - `latestIsDeviceWorn` is a `StateFlow<Boolean>` used by services/UI.

- HR sample production + CEP emission:
  - `app/src/main/java/com/aitech/weartak_civ/service/HeartrateService.kt`
  - Emits `Topics.HEART_RATE` samples to CEP (`HeartRatePayload`).
  - Stores latest HR in `DataRepository.storeLatestHeartRate(...)`.
  - Handles worn-state transitions via `IsDeviceWornEvent`.

- CEP low-HR rule currently not on-wrist gated:
  - `app/src/main/res/raw/cep_rules.json`
  - `hr_low_threshold_3s_alert` and `hr_rest_brady_10min_emergency` conditions do not include `offbody.on_wrist` checks.

- CEP alert dispatch path:
  - `app/src/main/java/com/aitech/weartak_civ/cep/engine/action/LogActionHandler.kt`
  - HR rules can create auto-generated physio alerts through `dataRepository.addPhysioAndAutoGenAlertRequest(...)`.

- Important nuance for implementation planning:
  - CEP has `OffBodyPayload`/`offbody.on_wrist` support in model/window store:
    - `app/src/main/java/com/aitech/weartak_civ/cep/model/payloads/OffBodyPayload.kt`
    - `app/src/main/java/com/aitech/weartak_civ/cep/engine/WindowStore.kt`
  - Production code appears to emit `Topics.OFFBODY` only in `CEPSimulationService`, not from `OffBodyDetectService`.

# Proposed Scope Boundaries (Will / Won't)
## Will Do
- Ensure off-wrist HR does not generate HR-origin alerts.
- Ensure HR value `<= 0` does not generate HR-origin alerts.
- Align stored/displayed HR behavior with off-wrist invalid-state handling.
- Add stable off-wrist auto-clear behavior for already-active HR-origin alerts using a sustained window (10s default).
- Keep changes limited to current service/CEP/data pathways.

## Won't Do
- Redesign CEP architecture or global alerting framework.
- Modify unrelated non-HR physiological rules/features.
- Expand scope into unrelated UX redesign or companion protocol changes.

# Ambiguities and Clarifying Questions
Clarification round completed.

Resolved:
- Q1: Suppress only `0` HR or all off-wrist HR?
  - Decision: both (suppress `0` and off-wrist HR processing).
- Q2: Apply to alerting only or also stored/displayed HR?
  - Decision: both.
- Q3: Behavior for active HR alert when device becomes off-wrist?
  - Decision after discussion: best behavior is sustained off-wrist auto-clear to avoid flapping; default 10s.

No blocking open questions remain for context expansion.

# Implementation Readiness Checklist
- [x] Problem statement clarified with concrete trigger scenario.
- [x] In-scope vs out-of-scope boundaries are explicit.
- [x] Affected components identified with repo evidence paths.
- [x] User clarification round completed and captured.
- [x] Acceptance criteria defined as observable outcomes.
- [x] Raw source provenance recorded (including missing in-repo path and effective Jira source).

# Ticket Rewrite Summary
A concise rewrite was produced in:
- `agent-rules/tickets/WEAR-398/TICKET_REWRITE.md`

Rewrite contract summary:
- Prevent false low-HR alerts from off-wrist/charging transitions.
- Treat off-wrist and HR `<= 0` as invalid for HR alerting.
- Keep displayed/stored HR aligned with invalid off-wrist state.
- Auto-clear active HR-origin alerts after 10s sustained off-wrist.
- Preserve existing valid on-wrist HR alert behavior and non-HR alert behavior.
