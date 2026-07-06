# 2026-07-06 SpO2 Alerts Re-enabled, Snapshot Rendering Fixed, and UI Categories Mapped

## Summary

This change restores the SpO2 alert path in `Develop` after the temporary `none_log` disable, while also fixing the two issues that made the alert screenshots misleading:

- missing snapshot values in SpO2 alert messages
- unmapped SpO2 categories in the alert UI title table

It also removes the stale placeholder from the clear message so the clear path no longer depends on a value that is not guaranteed to be fresh.

## What Changed

### 1. Re-enabled the SpO2 alert actions

The SpO2 rules in `app/src/main/res/raw/cep_rules.json` were restored from `none_log` back to `log` for:

- `spo2_warn_baseline_drop`
- `spo2_alert_sustained_hypoxemia`
- `spo2_emergency_severe_hypoxemia`
- `spo2_clear_any`

This means the rules now evaluate and emit their normal alert handling again.

### 2. Added snapshot keys for the SpO2 alert messages

The SpO2 alert rules now explicitly request the feature values used in their message templates:

- `spo2_warn_baseline_drop`
  - `spo2.pct`
  - `profile.spo2_active_baseline_pct`
- `spo2_alert_sustained_hypoxemia`
  - `spo2.pct`
- `spo2_emergency_severe_hypoxemia`
  - `spo2.pct`

This fixes the `?%` and `baseline=?%` rendering problem by making `RuleEngine` include the required values in the snapshot passed to the action handlers.

### 3. Removed the stale placeholder from the clear message

`spo2_clear_any` no longer formats `{spo2.pct}` in its message.

The clear event payload does not reliably carry a fresh SpO2 value, so the placeholder could show a stale or missing value. The clear message is now stable and does not depend on that field.

### 4. Mapped SpO2 categories in the alert UI

The alert category title table in `PhysioAlertsActivity.kt` now recognizes:

- `CAT_SPO2_WARN`
- `CAT_SPO2_ALERT`
- `CAT_SPO2_EMERGENCY`

These no longer fall through to the generic `ERROR` label in the alerts UI.

## Files Updated

- `app/src/main/res/raw/cep_rules.json`
- `app/src/main/java/com/aitech/weartak_civ/presentation/PhysioAlertsActivity.kt`

## Result

After this change:

- SpO2 alerts are active again
- alert messages should render actual values instead of `?`
- SpO2 alerts should show meaningful titles in the UI
- the clear path no longer depends on a potentially stale percentage

## Follow-up Work

The warm-up / provisional alert sensitivity in the SpO2 sequencer is still a product-tuning decision. If those alerts are still too eager after re-enabling, the next step is to adjust the gating logic in `Spo2Sequencer.kt`.
