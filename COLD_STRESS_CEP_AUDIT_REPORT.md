# Cold Stress CEP Audit Report

**Scope:** Cold Stress Risk Detection only (rules, feature emitters, CEP runtime, logging). No heat stress, generic HR exertion, or other risk domains.  
**Constraint:** No code or file changes; report only.

---

## Executive Summary (Cold Stress Only)

The cold stress pipeline is **partially implemented** with several **blocker-level gaps** that cause silent failure or incomplete detection in production:

1. **Ambient temperature** is never emitted in production (only in simulation). Wind chill and all ambient-based overrides depend on `ambient.temp_c`; without it, wind chill and frostbite override cannot compute.
2. **HRV (`hrv.rmssd_ms`)** is never emitted in production (only in CEPSimulationService). HRV baseline and drop-ratio features are never populated, so the HRV contribution to the cold score is always missing and baseline-relative HRV logic is inert.
3. **24-hour baselines** (HRV 86400s, SpO2 86400s) cannot be fulfilled: ring buffer capacity is 3600s. Long windows are effectively capped at 1 hour, so “24h baseline” is wrong.
4. **Skin temperature** is emitted as a single channel labeled “hand” only; framework expects wrist + hand and wrist-specific thresholds (e.g. drop >3°C in ~30 min, <28°C, <20°C). Wrist logic is absent.
5. **No `snapshot_keys`** on cold rules, so alert messages that use `{features.cold.score}` or `{features.wind_chill.c}` resolve to `"?"` in the message.
6. **`features.cold.inputs_ok`** is emitted for gating but is not referenced by any rule; cold score is not skipped when PPG is unavailable.

When inputs are missing, cold score case branches use "else": 0, so the score becomes 0 and risk_state NORMAL—**false safe** (real risk can be missed). Override rules still see null for derived booleans and do not fire.

---

## 1) Rule Coverage Table (Cold Stress)

| Rule ID | Trigger (when) | Required input keys | Outputs | Window / Staleness | Override → Emergency |
|--------|----------------|---------------------|---------|--------------------|----------------------|
| **features.skin_temp.hand_min_c_1m** | (feature) | `skin_temp` (array) | 1m min over min-channel °C | 60s rolling | — |
| **features.skin_temp.severe_vasoconstriction** | hand_min ≤ 20 | hand_min_c_1m | bool | — | Used by override |
| **features.skin_temp.slope_c_per_s_20s** | (feature) | skin_temp | °C/s slope, min channel | 20s | — |
| **features.hand.dexterity_loss** | hand_min ≤ 15 | hand_min_c_1m | bool | — | — |
| **features.hand.tactile_failure** | hand_min ≤ 8 | hand_min_c_1m | bool | — | Used by override |
| **features.wind_chill.c** | (derived) | ambient.temp_c, ambient.wind_mps | °C | — | — |
| **features.wind_chill.emergency_override** | wind_chill.c ≤ -27 | features.wind_chill.c | bool | — | Yes |
| **features.immersion.suspected** | slope ≤ -0.15 AND ppg collapse | slope_c_per_s_20s, features.ppg.collapse_gt50pct | bool | 20s + 20s/600s PPG | — |
| **features.ppg.amplitude_ratio** | (derived) | amplitude_recent_20s, amplitude_baseline_600s | ratio | 20s, 600s | — |
| **features.ppg.collapse_gt50pct** | ratio ≤ 0.5 | amplitude_ratio | bool | — | — |
| **features.spo2.delta_pct** | (derived) | recent_120s, baseline_86400s | % | 120s, 86400s | — |
| **features.hrv.rmssd_drop_ratio** | (derived) | rmssd_baseline_24h, rmssd_recent_1m | ratio | 60s, 86400s | — |
| **features.env.wet_flag** | water_sensor.wet OR humidity ≥ 95% | water_sensor.wet, humidity.relative_percent | bool | — | — |
| **features.cold.score** | sum of 6 case terms | hand_min_c_1m, shiver_present, shiver_duration_s, motion.active, heart_rate, hr.slope_bpm_per_min_60s, hr.hrr, rmssd_drop_ratio, ppg.collapse_gt50pct, spo2.delta_pct, wind_chill.c | 0–20+ points | — | — |
| **features.cold.risk_state** | categorical(score) | features.cold.score | NORMAL/WARN/ALERT/EMERGENCY | — | — |
| **cold_override_wind_chill_frostbite** | ref wind_chill.emergency_override | features.wind_chill.emergency_override | EMERGENCY, TAK, 10s sustain | — | Yes |
| **cold_override_tactile_failure** | ref hand.tactile_failure | features.hand.tactile_failure | EMERGENCY, TAK, 20s sustain | — | Yes |
| **cold_override_skin_temp_20c** | ref severe_vasoconstriction | features.skin_temp.severe_vasoconstriction | EMERGENCY, TAK, 60s sustain | — | Yes |
| **cold_alert_dexterity_loss** | ref hand.dexterity_loss | features.hand.dexterity_loss | ALERT, TAK, 60s sustain | — | No |
| **immersion_suspected_alert** | ref immersion.suspected | features.immersion.suspected | ALERT, 10s sustain | — | No |
| **cold_state_warn** | eq_key risk_state WARN | features.cold.risk_state | WARN, local, 60s sustain | — | No |
| **cold_state_alert** | eq_key risk_state ALERT | features.cold.risk_state | ALERT, TAK, 60s sustain | — | No |
| **cold_state_emergency** | eq_key risk_state EMERGENCY | features.cold.risk_state | EMERGENCY, TAK, 30s sustain | — | No |

**Deviations from framework checklist:**

- **Wrist skin temp:** Framework: drop >3°C in ~30 min ⇒ Warn; <28°C ⇒ Alert; <20°C ⇒ Emergency. Implementation uses a single “hand” channel and hand_min_c_1m only; no 30-min delta, no wrist-specific thresholds.
- **Shiver:** Framework: intermittent ⇒ Warn, sustained ≥30s ⇒ Alert, absent despite cold ⇒ Emergency. Implementation: shiver_present (0/1), shiver_duration_s, and “inactive + no shiver” ⇒ 4 points; no explicit “absent despite cold” override rule (only score term).
- **HR:** Brady <40 ⇒ 4 points (emergency path); slope ≤0 ⇒ 2; HRR ≥0.3 ⇒ 1. No explicit “tachy above baseline” as separate Warn; HR terms are folded into score.
- **HRV:** ≥20% drop ⇒ Warn/Alert by magnitude. Implementation: ≥0.3 ratio ⇒ 3, ≥0.2 ⇒ 2. Baseline is 24h; see baselines section.
- **SpO2/PPG:** SpO2 drop ~2–3% and PPG ≥50% reduction and collapse ⇒ Alert; implementation aligns (delta_pct ≤ -3, ratio ≤0.5). No explicit “collapse ⇒ Emergency” rule beyond score.
- **Ambient/wind chill:** <10°C ⇒ Warn, ≤-10°C ⇒ Alert, ≤-27°C ⇒ Emergency. Implementation: wind chill only in score (≤-27 ⇒ 4, ≤-10 ⇒ 2); no separate <10°C Warn tier in score (wind_chill contributes 0 for >-10).
- **Motion + shiver:** Inactive + sustained shiver / inactive + absent shiver implemented via score terms; no separate override rule for “absent shiver when inactive.”
- **Garment (ALS):** features.coverage.exposed exists but is not used in cold score or cold rules.
- **Scoring:** Bins 0–3 NORMAL, 4–6 WARN, 7–10 ALERT, ≥11 EMERGENCY; overrides force Emergency via separate rules. Implementation matches.

---

## 2) Dependency Map

```
Raw / Ingested:
  heart_rate (HeartrateService → CepBus HEART_RATE)
  skin_temp (SkinTempService → SKIN_TEMP; c_peripheral list, single "hand" channel)
  ambient.temp_c, ambient.wind_mps (AmbientPayload → only in simulation; production: wind_mps via WindPayload)
  water_sensor.wet (WaterPayload → WeatherService)
  humidity.relative_percent (HumidityPayload → WeatherService)
  ppg.amplitude (SkinTempService → PPG)
  spo2 (SkinTempService → SPO2)
  hrv.rmssd_ms (only CEPSimulationService in codebase; no production emitter)
  features.accel_low_motion, features.shiver_present, features.shiver_duration_s (MovementDetectionService → ingest)
  profile.hr_rest_bpm, profile.hr_max_bpm (CepEngine from DataRepository)

Derived (cold-relevant):
  features.skin_temp.hand_min_c_1m ← skin_temp (window_multi 60s min)
  features.skin_temp.slope_c_per_s_20s ← skin_temp (slope_multi 20s min_channel)
  features.skin_temp.severe_vasoconstriction, features.hand.dexterity_loss, features.hand.tactile_failure ← hand_min_c_1m
  features.wind_chill.c ← ambient.temp_c, ambient.wind_mps
  features.wind_chill.emergency_override ← features.wind_chill.c
  features.ppg.amplitude_recent_20s, features.ppg.amplitude_baseline_600s ← ppg.amplitude
  features.ppg.amplitude_ratio ← recent_20s / baseline_600s
  features.ppg.collapse_gt50pct ← amplitude_ratio
  features.spo2.recent_120s, features.spo2.baseline_86400s ← spo2
  features.spo2.delta_pct ← recent_120s, baseline_86400s
  features.hrv.rmssd_recent_1m, features.hrv.rmssd_baseline_24h ← hrv.rmssd_ms
  features.hrv.rmssd_drop_ratio ← baseline_24h, recent_1m
  features.env.wet_flag ← water_sensor.wet, humidity.relative_percent
  features.motion.active ← features.accel_low_motion (eq 0)
  features.heart_rate ← resolve(heart_rate)
  features.hr.delta_60s ← heart_rate (delta 60s)
  features.hr.slope_bpm_per_min_60s ← features.hr.delta_60s
  features.hr.hrr ← heart_rate, profile

  features.cold.score ← sum of 6 case terms (skin, shiver, HR, HRV, PPG/SpO2, wind chill)
  features.cold.risk_state ← categorical(score) → NORMAL|WARN|ALERT|EMERGENCY

Rules → Actions:
  cold_override_* → EMERGENCY, isTak, sustained 10–60s
  cold_alert_dexterity_loss, immersion_suspected_alert → ALERT
  cold_state_warn/alert/emergency → log + optional TAK, message with {features.cold.score}
```

---

## 3) Missing/Unemitted Feature Matrix

| Feature key | Expected type & units | Who emits | Cadence | Known missing modes | When missing (rules behavior) | Severity |
|-------------|------------------------|-----------|---------|----------------------|-------------------------------|----------|
| ambient.temp_c | °C double | **None in production** (only CEPSimulationService) | — | Always missing in prod | wind_chill.c not computed → return in DerivedEval; frostbite override never fires | **Blocker** |
| ambient.wind_mps | m/s double | WeatherService (WindPayload); WindowStore maps to same key | On weather poll | Network/API off, no location | wind_chill.c missing if temp also missing; if only wind missing, wind_chill returns | **High** (if temp present) |
| skin_temp (array) | list of °C | SkinTempService SKIN_TEMP; single channel "hand" | Emit loop (when lastSkinTempC set) | Sensor off, low perfusion, service off | hand_min_c_1m empty → no skin terms; score null if any term null | **High** |
| heart_rate | bpm double | HeartrateService HEART_RATE | Per HR sample (variable) | Sensor off, off-wrist | resolve to "heart_rate"; missing → HR case null → score null | **High** |
| hrv.rmssd_ms | ms double | **Only CEPSimulationService**; no production emitter | — | Always missing in prod | rmssd_baseline_24h, rmssd_recent_1m not computed; rmssd_drop_ratio null; HRV term in score null → score null | **Blocker** |
| ppg.amplitude | numeric | SkinTempService PPG | When PPG running, ~2s compute period | PPG paused for SpO2, optical contention, no tracker | amplitude_ratio/collapse null; immersion and PPG score term null | **High** |
| spo2 | % double | SkinTempService SPO2 (on-demand, then cached) | Sparse (on-demand + backoff) | Timeout, backoff, sensor fail | baseline_86400s / recent_120s sparse; delta_pct may be null or stale | **Medium** |
| water_sensor.wet | boolean | WeatherService WATER | On weather poll | API off | features.env.wet_flag uses OR with humidity; wet alone missing → humidity path still used | **Low** |
| humidity.relative_percent | % float | WeatherService HUMIDITY | On weather poll | API off | wet_flag may false; not used in cold score directly | **Low** |
| features.shiver_present | 1.0/0.0 | MovementDetectionService ingest | ~1 Hz (throttled) | Gyro off, no MovementDetectionService | shiver case null → score null | **High** |
| features.shiver_duration_s | seconds | MovementDetectionService ingest | ~1 Hz | Same | Same | **High** |
| features.accel_low_motion | 1.0/0.0 | MovementDetectionService ingest | ~1 Hz | Accel off | motion.active derived; "inactive + no shiver" term depends on it | **High** |
| profile.hr_rest_bpm, profile.hr_max_bpm | bpm | CepEngine updateHrProfile from DataRepository | On load / YOB change | Not loaded | HRR/slope use profile; default 60/190 in karvonen; cold score HR case uses them | **Medium** |

**Defaulting / sentinels:** FeatureStore has no explicit NaN or “missing” sentinel. Missing key → `fs.double(key)` = null. In **features.cold.score**, each case block has `"else": 0`; when a condition involves a missing key, the comparison returns null/false and the branch falls to else, so the term contributes 0. Thus the **cold score becomes 0** (sum of six 0s) and **risk_state = NORMAL** when inputs are missing—**false safe**. For derived features that have no else (e.g. wind_chill.c), missing key causes DerivedEval to return without writing, so that term’s case again yields 0. JsonLogic: missing ref → false; eq_key with null → false. Net: missing inputs → score 0, NORMAL → detection lost, **false safe**.

---

## 4) Baselines & Relative Thresholds Audit

**Where baselines are defined:**

- **HR:** profile.hr_rest_bpm, profile.hr_max_bpm (from DataRepository; CepEngine.updateHrProfile). Used for HRR and (indirectly) cold score HR case. Not learned from a window; static from user/resting HR.
- **HRV:** features.hrv.rmssd_baseline_24h = avg(hrv.rmssd_ms over 86400s). features.hrv.rmssd_recent_1m = avg over 60s. Drop ratio = (baseline - recent) / max(1, baseline).
- **SpO2:** features.spo2.baseline_86400s = avg(spo2 over 86400s), features.spo2.recent_120s = avg over 120s. Delta % = (recent - baseline) / max(0.1, baseline) * 100.
- **PPG:** features.ppg.amplitude_baseline_600s = avg(ppg.amplitude over 600s), features.ppg.amplitude_recent_20s = avg over 20s. Ratio = recent / max(1e-6, baseline).

**How baseline is learned:** Rolling window average over the stated horizon. No separate “resting vs activity” baseline; single long window for HRV/SpO2.

**Cold start:**  
- HRV/SpO2 24h baselines need 86400 * tickHz samples. Ring buffer capacity (RingConfig.default()) is maxSecondsNumeric = 3600, so at 1 Hz only 3600 samples (1 hour) are stored. **24h windows are impossible**; tail(86400) returns at most 3600 samples. Effective baseline is ~1h, not 24h.  
- Early in session, rings are empty or sparse → window avg null → derived baseline null → drop_ratio/delta_pct null → those score terms null → score can be null.

**When baseline is missing:**  
- wind_chill: DerivedEval returns without writing if temp or wind missing; wind_chill term in score case gets else 0.  
- HRV: rmssd_drop_ratio uses baseline and recent; if either null, div returns null; that derived feature is not written. The cold score’s HRV case uses key rmssd_drop_ratio; missing → condition false → else 0.  
- SpO2/PPG: same pattern; null propagates to derived; score case terms fall to else 0.  
- features.cold.score: each case block has "else": 0, so missing keys cause terms to contribute 0; score is 0 (not null).  
- features.cold.risk_state: categorical(0) → NORMAL.  
- Rules: state rules see risk_state "NORMAL"; override rules ref bool → null != true → false. So **missing inputs → score 0, NORMAL → false safe**.

**Recommended safe fallbacks (report only):**  
- Use a shorter nominal baseline (e.g. 1h) and document it, or increase ring capacity to support 24h.  
- For HRV, add a production emitter or document that cold score does not use HRV until one exists.  
- Optional: when baseline is empty, use “recent” as fallback (ratio 1.0 / no drop) so cold score can still compute without HRV term.  
- Optional: default wind_chill to ambient.temp_c when wind missing (or skip wind_chill term in score when temp missing).

---

## 5) Units & Semantics Audit

| Item | Finding | Risk |
|------|---------|------|
| **Skin temp** | SkinTempService emits list of one value with label "hand". Units °C (c_peripheral). No wrist channel; framework expects wrist + hand. | **Medium:** Wrist-specific thresholds (3°C/30min, 28°C, 20°C) not implementable; hand-only. |
| **Wind chill / ambient** | features.wind_chill.c uses Canadian formula; inputs ambient.temp_c (°C) and ambient.wind_mps (m/s). Thresholds in rules -27, -10 °C. No °F in pipeline. | **Low** (if ambient.temp_c were emitted). |
| **IMU shiver** | MovementDetectionService: BiquadBandpass 9 Hz center, Q=1, fs=50 Hz → ~6–12 Hz band. RMS over 2s; gated by accel_low_motion; ON after 2.5s dwell, OFF after 2s. Emits features.shiver_present (1/0), features.shiver_duration_s. | **Low:** Aligns with “~6–12 Hz tremor”; duration is monotonic while ON. |
| **SpO2 / PPG amplitude** | PPG: ratio = recent_20s / baseline_600s; collapse_gt50pct = ratio ≤ 0.5 (≥50% reduction). SpO2 delta_pct vs 24h baseline; rule uses ≤ -3%. No explicit signal-quality gating in rules (low perfusion could make both invalid). | **Medium:** Amplitude metric matches “≥50% reduction”; add quality gating in rules or in emitter. |
| **Water sensor** | water_sensor.wet from WaterPayload (WeatherService: wet = rain/snow/pop logic); boolean. humidity.relative_percent ≥ 95% OR wet → features.env.wet_flag. No temperature slope requirement for “immersion suspected” in wet_flag; immersion_suspected uses skin slope + PPG collapse. | **Low:** Wet is binary; immersion suspected is slope+PPG, not wet_flag in rule. |
| **ALS / coverage** | features.coverage.exposed = (als.covered == false) OR (light.lux ≥ 5). WindowStore: LightPayload.covered → als.covered. Not referenced by any cold rule. | **Low:** Modifier not wired; framework calls out “exposed wrist increases confidence.” |

---

## 6) Alert Outputs & Reason Strings

**What CEP outputs:**  
- Rule actions: type "log", severity WARN/ALERT/EMERGENCY, message template, tags, category, isTak, isLocal, timeout.  
- Message templates include placeholders: `{features.cold.score}`, `{features.wind_chill.c}` (e.g. frostbite override).

**Snapshot for message expansion:**  
- RuleDef.snapshotKeys is taken from rule JSON; **no cold rule defines snapshot_keys**. So snapshot is empty (RuleCompiler default).  
- LogActionHandler.expandMessage(template, snapshot): placeholder `{features.cold.score}` uses snapshot["features.cold.score"] → empty snapshot → **"?"**. So leader-facing messages show "score=?" and "wind chill ?°C" instead of values.

**State, score, reason codes:**  
- risk_state is categorical (NORMAL/WARN/ALERT/EMERGENCY).  
- Score is features.cold.score (numeric).  
- No dedicated "reason" or "why" field in the action payload; tags (e.g. "cold", "override", "wind_chill") and category (e.g. CAT_COLD_FROSTBITE_OVERRIDE) provide some context.  
- Override vs state-based: overrides have distinct rule IDs and categories; state rules fire on risk_state.  
- TAK vs on-watch: isTak true/false and isLocal control TAK send and local display; cold_state_warn is isTak: false, others isTak: true.

**Gaps:**  
- **Reason strings:** No structured “cause + urgency” (e.g. “immersion”, “frostbite risk”, “absent shiver”) in a single field; only message template + tags/category.  
- **Placeholders unresolved:** Add snapshot_keys (e.g. features.cold.score, features.wind_chill.c, features.cold.risk_state) to cold rules so messages show actual values.  
- **Override firing reason:** Message text is correct (e.g. “frostbite risk (wind chill X°C)”) but X is "?" without snapshot_keys.

---

## 7) Top 10 Breakpoints (Actionable)

| # | Breakpoint | Impact | Where | How to validate | Suggested remediation (no impl) |
|---|------------|--------|-------|------------------|----------------------------------|
| 1 | ambient.temp_c never emitted in production | Wind chill and frostbite override never compute; cold score wind_chill term always missing | EnvSensorService: TYPE_AMBIENT_TEMPERATURE commented; no other emitter | Log CEP ingest for ambient.temp_c in prod; run without simulation | Enable ambient temp sensor in EnvSensorService and emit AmbientPayload (or derive temp from API and emit) |
| 2 | hrv.rmssd_ms never emitted in production | HRV baseline and drop_ratio never computed; HRV term in cold score always null; score can be null | Only CEPSimulationService emits HRV | Check CepBus/WindowStore for hrv.rmssd_ms in prod | Add HRV producer (e.g. from existing HR/PPG pipeline) and emit HrvPayload to CepBus |
| 3 | 24h windows exceed ring capacity | HRV 24h and SpO2 24h baselines use at most 1h of data; baseline semantics wrong | RingConfig.maxSecondsNumeric = 3600; window_sec 86400 in cep_rules.json | Log ring size vs requested tail(86400); inspect baseline values over time | Increase RingConfig for 24h (e.g. 86400) or change rules to 1h baseline and document |
| 4 | Missing inputs → score 0 and risk_state NORMAL (false safe) | Cold score is sum of case terms; each case has "else": 0, so missing key → term 0; score = 0, state NORMAL; real risk can be missed | cep_rules.json: all case blocks in features.cold.score have "else": 0 | With key inputs disabled, confirm score 0 and NORMAL in [CEP][DERIVED] logs | Do not default missing terms to 0; use null/unknown and skip state or require minimum inputs before scoring |
| 5 | No snapshot_keys on cold rules | Message placeholders {features.cold.score}, {features.wind_chill.c} resolve to "?" | cep_rules.json cold rules lack snapshot_keys | Fire a cold rule and check log message for "?" | Add snapshot_keys to cold rules: e.g. features.cold.score, features.cold.risk_state, features.wind_chill.c |
| 6 | Single-channel skin temp (hand only) | Wrist thresholds and “drop >3°C in 30 min” not possible | SkinTempService emits c_peripheral = listOf(c), labels = ["hand"] | Inspect SkinTempPayload in CEP; only one channel | Add wrist channel if hardware supports; or document hand-only and adjust framework expectations |
| 7 | features.cold.inputs_ok not used in rules | Cold score not gated when PPG unavailable; may compute with stale/missing PPG | SkinTempService pushes inputs_ok; no rule references it | Set PPG off and observe cold score still computed or null | Reference inputs_ok in a gating condition or in a “skip cold score when 0” rule |
| 8 | WindPayload overwrites ambient.wind_mps but ambient.temp_c never set | Wind chill uses temp_key and wind_key; temp missing → wind_chill not computed | WindowStore: WindPayload writes ambientWind; AmbientPayload writes both (sim only) | In prod, log fs.double("ambient.temp_c") and wind_chill.c | Ensure ambient.temp_c is set (see #1); optionally merge API temp with WindPayload in one payload |
| 9 | TriggerTimer uses tick count, not wall-clock seconds | sustained_for_sec 60 means 60 ticks; at 1 Hz = 60s. If tick Hz changes, timing changes | TriggerTimer.tick: activeFor++; mature = activeFor >= sustainedForSec | Change tick Hz and verify rule fire delay | Document dependency on config.tickHz or use wall-clock in TriggerTimer |
| 10 | Empty or sparse HRV/SpO2 rings → null baselines | Early session or low emission rate: 24h/1m/120s windows empty → derived null | WindowStore.computeWindow: ring.tail(n); avg of empty → null | Run short session; log rmssd_baseline_24h, spo2.baseline_86400s | Shorter nominal baseline or “recent-only” fallback when baseline empty (report only) |

---

## 8) Recommendations (No Code Changes)

**Prioritized**

1. **Blocker:** Emit **ambient.temp_c** in production (e.g. enable TYPE_AMBIENT_TEMPERATURE in EnvSensorService and emit AmbientPayload, or provide temp from weather API and write to FeatureStore / CepBus so wind chill and frostbite override can run).  
2. **Blocker:** Either **emit HRV** in production (hrv.rmssd_ms) or **remove/deprecate HRV-dependent cold score terms** and document that cold stress does not use HRV until an emitter exists.  
3. **Blocker:** Fix **24h baseline vs ring capacity**: increase RingConfig (e.g. maxSecondsNumeric ≥ 86400) for 24h windows, or change HRV/SpO2 baseline window_sec to 3600 and document “1h baseline.”  
4. **High:** Add **snapshot_keys** to all cold rules so that message templates get features.cold.score, features.cold.risk_state, features.wind_chill.c (and any other placeholders).  
5. **High:** Define **wrist vs hand** strategy: add wrist skin temp channel if available, or document hand-only and align framework checklist (e.g. drop >3°C/30min and wrist 28/20°C as N/A).  
6. **High:** **Gate cold score** on features.cold.inputs_ok (or equivalent) so that when PPG is unavailable, cold score is not computed or is explicitly “unknown.”  
7. **Medium:** Add **signal-quality gating** for SpO2/PPG in rules or emitters (e.g. do not use SpO2/PPG terms when quality is low or perfusion flag set).  
8. **Medium:** **Document** that missing cold inputs cause score 0 and state NORMAL (false safe) and add a minimal “cold inputs incomplete” log or telemetry when key inputs are missing.  
9. **Low:** Wire **ALS/coverage** (features.coverage.exposed) into cold score or rules as a modifier if product requires “exposed wrist” sensitivity.  
10. **Low:** Consider **TAK vs on-watch** differentiation in message text (e.g. “cause + urgency” field) for leader-facing clarity.

**Minimal test plan to confirm fixes**

- **Cold exposure (no immersion):** In simulation or test, set ambient.temp_c & wind_mps so wind_chill ≤ -27; sustain 10s; confirm cold_override_wind_chill_frostbite fires and message shows numeric wind_chill.  
- **Immersion:** Set skin slope ≤ -0.15 °C/s and PPG ratio ≤ 0.5 for 10s; confirm immersion_suspected_alert and message.  
- **Wind chill override:** With real or simulated ambient temp + wind, test wind chill formula and override at -27°C and -10°C.  
- **Baselines:** After adding HRV emitter or adjusting rings, run 1h (or 24h) and verify rmssd_baseline_24h and spo2.baseline_86400s are non-null and drop_ratio/delta_pct compute.  
- **Missing input / false safe:** Disable one of HR, skin temp, or HRV; confirm cold score becomes 0 and risk_state NORMAL (false safe). After fix, confirm score/state are null or “unknown” when required inputs are missing.  
- **Snapshot:** Add snapshot_keys, fire cold_state_alert, and confirm log message contains actual score and state, not "?".

---

*End of Cold Stress CEP Audit Report. No code or files were modified.*
