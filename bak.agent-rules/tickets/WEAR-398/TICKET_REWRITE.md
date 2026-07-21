# WEAR-398: Prevent false HR alerts when watch is off-wrist or charging

## Problem / Why
When the watch is removed from the wrist (including charger placement transitions), off-body state can be true and heart-rate can read `0`. This can trigger false low-heart-rate physiological alerts.

## User Impact
Users receive inaccurate low-HR alerts while not wearing the watch, causing alert fatigue and reduced trust in physiological alerting.

## In-scope
- Treat HR as invalid while off-wrist.
- Suppress HR value `0` as invalid input.
- Prevent HR-based alert generation from invalid off-wrist/zero HR input.
- Auto-clear active HR-origin alerts after sustained off-wrist state.
- Keep implementation consistent with existing service, DataRepository, and CEP patterns.

## Out-of-scope
- Redesign of the full physiological alert architecture.
- Retuning unrelated physiological thresholds/rules.
- Broad UI redesign.

## Functional Requirements
- While `isDeviceWorn == false`, HR samples must not drive HR alert generation.
- HR sample value `<= 0` must not drive HR alert generation.
- While off-wrist, HR persistence/display should not present a valid numeric HR (use existing non-valid pattern such as `N/A`/sentinel behavior).
- Existing HR alerting on valid on-wrist samples remains intact.
- If an HR-origin alert is active and device transitions off-wrist, clear it after a sustained off-wrist window (default 10 seconds) to avoid transition flapping.

## Non-functional Constraints
- No architecture redesign.
- Minimize scope to relevant service/CEP/alert-path touchpoints.
- Preserve current alert dispatch + clear flows for non-HR or valid-HR scenarios.

## Acceptance Criteria
1. Repro false-positive scenario: remove watch or place on charger; HR `0`/off-wrist transitions do not create new low-HR physiological alerts.
2. On-wrist valid HR samples still propagate and can trigger legitimate HR alerts according to thresholds.
3. Off-wrist state does not show/store a valid HR value in normal runtime display/state paths.
4. Existing active HR-origin alert is auto-cleared after 10 seconds of sustained off-wrist state.
5. No regressions in non-HR alert categories.

## Dependencies / Risks
- CEP low-HR rules are currently not on-wrist gated, so rule-level and/or emitter-level guarding is required.
- Off-body signal is tracked in runtime state and EventBus; transition timing can flap, so sustained window behavior is required for stable clear semantics.

## Source
- Requested raw source path: `agent-rules/tickets/WEAR-398/WEAR-398.md` (not present in repo at time of expansion).
- Effective raw source used: Jira issue details from shell command `zsh -lic 'details'` for `WEAR-398`.
