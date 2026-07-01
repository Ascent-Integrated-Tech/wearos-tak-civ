# WEAR-398 Testing Notes

## Temporary Test Changes Applied (and Reverted)
Purpose: make HR alerts trigger quickly during live watch validation.

1. `app/src/main/java/com/aitech/weartak_civ/cep/engine/CepEngine.kt`
- Temporary debug-only HR threshold override (`hrHighResting=85`, `hrLow=120`) and debug log marker.
- Status: reverted.

2. `app/src/main/res/raw/cep_rules.json`
- Temporary reduced `sustained_for_sec` for faster HR rule triggering.
- Status: reverted.

3. `app/src/main/java/com/aitech/weartak_civ/service/HeartrateService.kt`
- Temporary extra diagnostic logs for suppression/off-wrist clear scheduling.
- Status: reverted.

## Current State
- Non-test tuning restored.
- Core WEAR-398 fix remains active in `HeartrateService`:
  - off-wrist/invalid HR gating for CEP emission + persisted/displayed HR
  - sustained off-wrist (10s) auto-clear for active HR-origin alerts.
