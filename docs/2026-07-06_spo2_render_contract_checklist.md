# 2026-07-06 SpO2 Render Contract and Missing-Data Handling Checklist

This checklist captures the implementation plan for making SpO2 alert rendering declarative, generic, and safe when data is missing.

## Plan

### 1. Verify the active runtime configuration on the device

- Objective: confirm whether the watch is using the working-tree built-in rules or a cached overlay.
- Files: no source edits yet.
- What to inspect:
  - runtime logs for rule id and snapshot contents
  - cached managed overlay state if available
  - any plugin overlay or applied-config diagnostic output
- Expected outcome:
  - either a clean built-in config path, or evidence that a stale overlay is still active

### 2. Add a generic render-contract field to rule definitions

- Objective: make the data needed to render alert text declarative in `cep_rules.json`.
- Files:
  - `app/src/main/res/raw/cep_rules.json`
  - `app/src/main/java/com/aitech/weartak_civ/cep/engine/CepConfig.kt`
  - `app/src/main/java/com/aitech/weartak_civ/cep/engine/RuleCompiler.kt`
  - `app/src/main/java/com/aitech/weartak_civ/cep/engine/config/CepConfigValidator.kt`
- Edits:
  - add a rule-level field such as `render_keys` or `required_snapshot_keys`
  - for SpO2 rules, declare the keys needed for message rendering
  - validate the field at config load time
- Expected outcome:
  - rules explicitly declare what values they need to render
  - the engine can enforce the contract generically

### 3. Derive render keys from the template by default

- Objective: avoid drift between the message template and a manually duplicated key list.
- Files:
  - `app/src/main/java/com/aitech/weartak_civ/cep/engine/RuleCompiler.kt`
  - `app/src/main/java/com/aitech/weartak_civ/cep/engine/config/CepConfigValidator.kt`
- Edits:
  - parse `{placeholder}` names from the rule message template
  - treat those names as the default render requirements
  - allow an explicit override field only when the rule needs additional non-rendered required keys
- Expected outcome:
  - existing and overlay rules are covered automatically
  - no separate render-key declarations are needed for normal cases

### 4. Add severity-aware missing-data policy to rule definitions

- Objective: avoid suppressing safety-critical alerts when formatting values are missing.
- Files:
  - `app/src/main/res/raw/cep_rules.json`
  - `app/src/main/java/com/aitech/weartak_civ/cep/engine/CepConfig.kt`
- Edits:
  - add a rule-level field such as:
    - `on_missing_render: "suppress" | "degrade"`
  - default to `suppress`
  - set SpO2 `WARN` and `ALERT` to `suppress`
  - set SpO2 `EMERGENCY` to `degrade`
- Expected outcome:
  - non-critical alerts are blocked if data is missing
  - emergency alerts still dispatch with a safe fallback if needed

### 5. Enforce missing-data policy in the generic rule engine

- Objective: suppress or degrade dispatch before the action handlers run.
- Files:
  - `app/src/main/java/com/aitech/weartak_civ/cep/engine/RuleEngine.kt`
  - `app/src/main/java/com/aitech/weartak_civ/cep/engine/action/BaseCepActionHandler.kt`
  - `app/src/main/java/com/aitech/weartak_civ/cep/engine/action/LogActionHandler.kt`
  - `app/src/main/java/com/aitech/weartak_civ/cep/engine/action/WarnActionHandler.kt`
  - `app/src/main/java/com/aitech/weartak_civ/cep/engine/action/WarnLogActionHandler.kt`
- Edits:
  - before dispatch, compare required render keys against the snapshot
  - if missing and policy is `suppress`, do not dispatch the alert
  - if missing and policy is `degrade`, dispatch a fallback message without placeholders
  - keep this logic in the engine layer, not per-metric handlers
- Expected outcome:
  - no user-facing `?` values
  - safety-critical alerts are not silently lost
  - dispatch behavior is generic across all rule types

### 6. Make suppression and diagnostics explicit

- Objective: avoid consuming the throttle window on suppressed fires and avoid log spam.
- Files:
  - `app/src/main/java/com/aitech/weartak_civ/cep/engine/RuleEngine.kt`
  - `app/src/main/java/com/aitech/weartak_civ/cep/engine/CepEngine.kt`
  - `app/src/main/java/com/aitech/weartak_civ/cep`
- Edits:
  - suppressed fires should not advance the normal alert throttle or hold-down state
  - record one diagnostic per rule per missing-data episode
  - store the diagnostic in the persistent sink, not only logcat
- Expected outcome:
  - temporary missing data does not silence the alert for the whole throttle period
  - diagnostics remain readable and rate-limited

### 7. Ensure SpO2 values are present in the same engine tick

- Objective: reduce the chance that the live snapshot is incomplete when the rule fires.
- Files:
  - `app/src/main/java/com/aitech/weartak_civ/cep/engine/CepEngine.kt`
  - `app/src/main/java/com/aitech/weartak_civ/service/SkinTempService.kt`
  - `app/src/main/java/com/aitech/weartak_civ/service/Spo2Sequencer.kt`
- Edits:
  - keep `spo2.pct` publication in the SpO2 tier path
  - make sure baseline/profile keys are refreshed before the alert can be evaluated
  - optionally publish the baseline fields in the same write batch as the tier event
- Expected outcome:
  - fewer incomplete snapshots
  - fewer suppressions caused by timing alone

### 8. Fix the warm-start gap for SpO2

- Objective: make a newly started engine less likely to fire with missing baseline fields.
- Files:
  - `app/src/main/java/com/aitech/weartak_civ/cep/engine/CepEngine.kt`
  - `app/src/main/java/com/aitech/weartak_civ/cep/engine/ProfileStore.kt`
- Edits:
  - seed SpO2 baseline and profile keys during warm-up
  - mirror the persisted SpO2 baseline into `FeatureStore` when the engine starts
  - keep the warm-start path generic so other metrics can follow the same pattern later
- Expected outcome:
  - fewer post-swap or post-restart `baseline=?` cases
  - less dependence on the first live reading to populate the snapshot

### 9. Keep the UI mappings

- Objective: preserve correct alert labels when valid SpO2 alerts are shown.
- Files:
  - `app/src/main/java/com/aitech/weartak_civ/presentation/PhysioAlertsActivity.kt`
- Edits:
  - keep `CAT_SPO2_WARN`, `CAT_SPO2_ALERT`, and `CAT_SPO2_EMERGENCY` mapped to meaningful labels
- Expected outcome:
  - if a valid alert is displayed, it renders with the right title
  - no generic `ERROR` fallback for SpO2 categories

### 10. Re-verify on device before shipping

- Objective: confirm whether the symptom is coming from overlay/config, stale engine identity, or incomplete snapshot timing.
- Files: no edits yet.
- What to do:
  - capture one alert in logcat
  - inspect the snapshot contents
  - dump the active overlay/config
- Expected outcome:
  - you know whether the suppression policy fixed the symptom
  - you know whether the root cause is config, engine identity, or timing

## Execution Order

1. Verify runtime config on the device.
2. Add template-derived render keys.
3. Add severity-aware missing-data policy.
4. Add suppression diagnostics.
5. Seed SpO2 warm-start values.
6. Recheck the live fire path.
7. Keep the UI mappings as-is.

## Notes

- Keep the rule contract declarative in `cep_rules.json`.
- Keep the enforcement generic in the CEP engine.
- Keep value publication in the source and engine paths.
- Do not hardcode SpO2-specific rendering policy in the action handlers.
- Prefer diagnostics over silent failure, but do not emit malformed user-facing alerts.

