# SPO2 Plan Critique for `WEAR-441`

## 1. Executive Verdict

**DO NOT IMPLEMENT YET**

The plan is not safe to implement as written. It gets the big idea right in one place: consecutive-valid SPO2 logic should not live in CEP windows. But the actual design then spreads ownership across `SkinTempService`, `Spo2Sequencer`, `CepEngine`, CEP rules, `ProfileStore`, and optionally `DataRepository`, and that creates correctness gaps, duplicated gating, and repo-fit failures.

The blocking issues are not cosmetic:

- **Definite problem:** the plan is not compliant with the PDF on Alert, Emergency, and baseline behavior. It drops required corroboration, invents trauma thresholds, and replaces the PDF baseline method with a much weaker EMA path ([plan §4.3, §5, §6](./spo2_implementation_plan_v7.md)).
- **Definite problem:** the proposed CEP pulse design is race-prone and internally inconsistent. `Spo2TierEventFifo` is introduced as a reliability primitive, but nothing in the plan actually drains it, and it cannot protect against `CepBus` overflow because it only receives events after `CepBus.events.collect` does ([plan lines 110-125, 224-229](./spo2_implementation_plan_v7.md), [CepBus.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/cep/bus/CepBus.kt:13)).
- **Definite problem:** the plan assumes repo capabilities that do not exist today: `activity.confidence` is not in CEP, `disabled: true` is not supported by `RuleCompiler`, and direct `AlertRegistry.clear(ruleId)` does not clear the persisted alert/UI state ([ActivityPayload.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/cep/model/payloads/ActivityPayload.kt:4), [RuleCompiler.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/cep/engine/RuleCompiler.kt:115), [AlertRegistry.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/cep/engine/action/AlertRegistry.kt:146), [CepEngine.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/cep/engine/CepEngine.kt:272)).
- **Likely problem:** the plan assumes a 30 s acquisition cadence, but the current `SkinTempService` scheduler is 60 s by default. Changing `SPO2_MIN_GAP_MS` to 30 s does not by itself produce 30 s consecutive samples ([SkinTempService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/SkinTempService.kt:72), [SkinTempService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/SkinTempService.kt:167), [SkinTempService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/SkinTempService.kt:317)).

## 2. What the Plan Gets Right

- **Strong choice:** moving consecutive-valid logic out of `cep_rules.json` and out of time-averaged SPO2 windows is correct. The PDF explicitly says not to use second-by-second averaging.
- **Strong choice:** fixing the 1 Hz repeat-emission bug in `emitLatestCep()` is necessary. The current service really does emit the same latest SPO2 value every second until replaced ([SkinTempService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/SkinTempService.kt:1141)).
- **Strong choice:** off-wrist should hard reset SPO2 tier state. That is cleaner than trying to clear via rule monitors.
- **Strong choice:** WARN/ALERT/EMERGENCY should be derived from a service-side sequence of valid readings, not from CEP windows over raw SPO2.

## 3. Critical Problems

- **Definite problem: the logic split is wrong.** The plan puts validity and consecutive sequencing in `Spo2Sequencer`, mirrors tier edges in `CepEngine`, then re-gates with `offbody.on_wrist`, `activity.state`, freshness, and provisional baseline in CEP rules. That is duplicated authority, not separation of concerns. A sample can be valid in the sequencer and still be rejected by the rule layer for unrelated state skew.
- **Definite problem: `Spo2TierEventFifo` is dead weight.** The plan creates it for deterministic draining ([plan lines 112-124](./spo2_implementation_plan_v7.md)) but the only described use is `offer()` in the bus collector ([plan lines 224-225](./spo2_implementation_plan_v7.md)). There is no planned drain path in `processTick`. Right now the real engine has no such mechanism.
- **Definite problem: the FIFO does not solve bus reliability.** The plan claims it decouples from `CepBus` `DROP_OLDEST`, but `offer()` only happens after `CepBus.events.collect` receives the event. If `CepBus` drops the event, the FIFO never sees it.
- **Definite problem: pulse edges are race-prone.** The plan proposes `featureStore.put(edge=true)` followed by a delayed `put(edge=false)` after `ruleEvalIntervalMs + 50` ms ([plan lines 227-229, 585-588](./spo2_implementation_plan_v7.md)). That is fragile under coroutine delay jitter and can clobber newer pulses if a second event lands before the first delayed reset runs.
- **Definite problem: `AlertRegistry.clear(ruleId)` is insufficient.** The current real clear path also removes the alert from `DataRepository` and emits cleared events ([CepEngine.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/cep/engine/CepEngine.kt:297)). `AlertRegistry.clear()` alone only drops the in-memory active flag ([AlertRegistry.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/cep/engine/action/AlertRegistry.kt:146)). The plan would leave stale alerts in UI/dispatch state.
- **Definite problem: baseline ownership is incoherent.** The plan updates baseline in the sequencer on every qualifying reading ([plan lines 449-450](./spo2_implementation_plan_v7.md)) but only describes `CepEngine.updateSpo2Profile()` being called from `start()` ([plan line 241](./spo2_implementation_plan_v7.md)). That means rules reading `profile.spo2_baseline_pct` would see stale values unless more refresh wiring is added.
- **Definite problem: tier clear semantics are under-specified and partly wrong.** The plan says clear is owned by sequencer, but it also leaves monitor exits in the rule action as a backup. That means there are two clear systems with different conditions and different side effects.
- **Likely problem: off-wrist and event timing can race.** The plan keeps an `offbody.on_wrist` rule gate even though `reset(OFF_WRIST)` is already supposed to be authoritative. If EventBus off-wrist reset and CepBus off-body state are not in lockstep, the rule layer can disagree with the sequencer.
- **Likely problem: battery cost is being accepted without proving feasibility.** Cutting `SPO2_MIN_GAP_MS` from 1 hour to 30 s and timeout backoff from 30 min to 5 min is a massive duty-cycle change ([plan lines 185-188](./spo2_implementation_plan_v7.md), [SkinTempService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/SkinTempService.kt:117), [SkinTempService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/SkinTempService.kt:119)). The repo currently uses the opposite posture.
- **Definite problem: high-risk / trauma logic is inconsistent.** The config adds `traumaAlertPct=92`, `traumaEmergencyPct=89`, and `traumaSustainDivisor`, but `N` is not actually reduced in the provided algorithm (`if trauma then consecutiveRequired else consecutiveRequired`) ([plan lines 343-364, 457-458](./spo2_implementation_plan_v7.md)). This is internally inconsistent and also not what the PDF says.

## 4. Overengineering / Simplification Opportunities

- **Remove `Spo2TierEventFifo`.** It does not solve the stated problem and the plan does not use it.
- **Avoid tier-edge booleans if you keep CEP out of the critical path.** The current pulse proposal is brittle and adds delayed-reset races.
- **Do not extend `Spo2Payload` and `PpgPayload` just to support a service-local sequencer.** The plan’s own data flow calls `Spo2Sequencer` directly from `SkinTempService`. Those payload expansions are mostly diagnostic bloat.
- **Do not add `TopicRegistry` work unless a real caller needs it.** I found no repo use of `TopicRegistry.payloadClass()` outside the registry itself.
- **Do not add `DataRepository.latestSpo2TierEvent` or `setUserRiskProfile()` in phase 1.** That drags UI and persistence concerns into an already unstable design.
- **Do not keep both sequencer clear logic and rule monitor clear logic.** Pick one owner.
- **Do not add a separate baseline mirror path unless CEP rules truly need it.** If the sequencer owns warn/alert/emergency decisions, CEP does not need to continuously mirror baseline state just to rediscover a decision already made.

## 5. Repo-Reality Gaps

- **Definite problem:** `activity.confidence` does not exist in CEP. `ActivityPayload` only has `state` ([ActivityPayload.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/cep/model/payloads/ActivityPayload.kt:4)). `WindowStore` only writes `activity.state` ([WindowStore.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/cep/engine/WindowStore.kt:94)).
- **Definite problem:** `AlertRegistry.clear(ruleId)` is public, but it does not do what the plan needs. It does not clear persisted alerts or emit `AlertClearedEvent`.
- **Definite problem:** `RuleCompiler` does not support `"disabled": true`. It blindly parses every rule object in the array ([RuleCompiler.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/cep/engine/RuleCompiler.kt:115)).
- **Definite problem:** `TopicRegistry` registration is probably unnecessary. I found no call sites for `payloadClass()`.
- **Definite problem:** the current `SkinTempService` does not expose “30 s PPG-red presence.” The continuous callback reads `PPG_GREEN`, not red, and computes a heuristic quality from amplitude ([SkinTempService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/SkinTempService.kt:685), [SkinTempService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/SkinTempService.kt:873)).
- **Definite problem:** the current `SkinTempService` cadence is not proven to support 30 s consecutive SPO2 sampling. The scheduler defaults to 60 s dispatch ([SkinTempService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/SkinTempService.kt:72)).
- **Likely problem:** altitude bucket support is not present in CEP. I found pressure thresholds and device location altitude, but no SPO2 altitude-bucket feature path.
- **Verified assumption:** `CepEngine.ingest(...)` can be called from services; the repo already does this for weather and cold input flags ([SkinTempService.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/service/SkinTempService.kt:491)).
- **Verified assumption:** `windows.ingest(...)` will effectively ignore unknown custom payload types because the `when` has no matching branch ([WindowStore.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/cep/engine/WindowStore.kt:63)).
- **Likely problem:** new SPO2 categories will not get bespoke UI labels/icons unless `PhysioAlertsActivity` is updated. Current category mapping is explicit and does not include SPO2 categories ([PhysioAlertsActivity.kt](/home/ghost/Documents/Ascent/WearOS/WEAR-441/app/src/main/java/com/aitech/weartak_civ/presentation/PhysioAlertsActivity.kt:198)).

## 6. Correctness Against the PDF

- **Valid reading gate: partially compliant.** The plan enforces Samsung OK, low motion, 30 s sustained PPG signal, and ≤60 s consecutive gap. But it assumes a usable 30 s red-channel signal exists in this repo, which is unproven, and it turns “valid amplitude” into an implicit byproduct of a sustained-signal timer instead of an explicit checked gate.
- **N consecutive valid logic: compliant in intent, not yet proven in repo timing.** The sequencer ring is the right approach. The repo cadence is the problem.
- **Reduced N for emergency: non-compliant.** The PDF allows reduced N with corroboration. The plan hard-codes `eN=2` but then requires both readings to be `<88`, which is not the PDF condition.
- **Baseline-minus-4 WARN: non-compliant.** The PDF requires all N readings `<= baseline - 4` and most readings in `92-94`. The plan omits the `92-94` corroboration entirely.
- **Alert under `<90%`: non-compliant.** The PDF requires all N readings `<90` and at least one reading `<= baseline - 4` or high-risk context. The plan only checks all N `<90`; the required corroboration is gone.
- **Emergency under `<88%`: non-compliant.** The PDF requires one valid `<88` plus either another valid `<90` within ~2 min or high-risk context. The plan instead requires `eN` readings all `<88` and ignores the explicit alternative corroboration path.
- **Motion gating: compliant in principle, duplicated in design.** The sequencer gate is enough. Repeating it in rules is not needed.
- **Freshness: partially compliant.** The `emitLatestCep()` freshness fix is correct. The extra rule-side freshness check is okay. The problem is the pulse design, not freshness itself.
- **Consecutive-spacing `<=60 s`: partially compliant.** The sequencer checks the gap, but the repo’s current scheduler still suggests 60 s cadence, not 30 s cadence, so the “~2 minutes for N=4” assumption is unproven.
- **Not relying on averaging: compliant.** This is one of the stronger parts of the plan.

## 7. Specific Design Changes You Recommend

1. **Current plan choice:** tier ownership split across sequencer, CEP pulse booleans, and rules.
   **Why it is a problem:** duplicated truth and race windows.
   **Recommended alternative:** make `Spo2Sequencer` the single owner of validity, consecutive-state, tier transitions, and clears.
   **Impact:** removes rule-side revalidation and most timing bugs.

2. **Current plan choice:** use `Spo2TierEventPayload` + `CepBus` + delayed edge booleans.
   **Why it is a problem:** fragile pulses and no true delivery guarantee.
   **Recommended alternative:** either dispatch alerts directly from sequencer through a thin alert-dispatch adapter, or if CEP must stay in the loop, expose one stable numeric tier state and one monotonic transition counter instead of ephemeral booleans.
   **Impact:** simpler sequencing, less pulse loss.

3. **Current plan choice:** add `Spo2TierEventFifo`.
   **Why it is a problem:** unused and ineffective against `CepBus` loss.
   **Recommended alternative:** delete it.
   **Impact:** removes one whole class and one false-reliability claim.

4. **Current plan choice:** keep rule monitors while also clearing from sequencer.
   **Why it is a problem:** two clear mechanisms with different semantics.
   **Recommended alternative:** one clear path only. If sequencer clears, it must call the same repo/UI clear path CEP uses today, not just `AlertRegistry.clear`.
   **Impact:** consistent operator-visible state.

5. **Current plan choice:** update baseline with every qualifying still/on-wrist reading via EMA.
   **Why it is a problem:** this drifts baseline downward during the very desaturation episodes you are trying to detect, and it does not match the PDF’s baseline method.
   **Recommended alternative:** phase 1 should either freeze baseline to a conservative stored value, or only update it from clearly normal readings well above alerting territory and outside active SPO2 tiers.
   **Impact:** preserves WARN sensitivity and avoids self-canceling baselines.

6. **Current plan choice:** add trauma thresholds (`92`, `89`) and sustain divisor.
   **Why it is a problem:** not in the PDF and internally inconsistent.
   **Recommended alternative:** keep the SPO2 thresholds fixed at `<90` and `<88`; only let high-risk context reduce corroboration requirements or N where the PDF explicitly allows it.
   **Impact:** brings the feature back into spec.

7. **Current plan choice:** use CEP rules for `activity.confidence >= 50`.
   **Why it is a problem:** that key does not exist in CEP.
   **Recommended alternative:** keep confidence gating entirely in `SkinTempService` / sequencer unless you explicitly add confidence to `ActivityPayload` and `WindowStore`.
   **Impact:** avoids a guaranteed broken rule.

8. **Current plan choice:** add payload fields to `Spo2Payload` and `PpgPayload`.
   **Why it is a problem:** the sequencer already has direct service inputs; this is extra coupling.
   **Recommended alternative:** keep payloads small unless another CEP consumer truly needs the new fields.
   **Impact:** less schema churn and less test surface.

## 8. Proposed Reduced / Safer Implementation Shape

- **`Spo2TierEventFifo`: remove it.**
- **Tier-edge booleans: avoid them if possible.** They are a workaround for CEP not having a real event-consumption primitive.
- **`Spo2Sequencer` dispatch path:** prefer one of two shapes only.
- **Shape A, safer:** `Spo2Sequencer` publishes final alert enter/clear decisions directly through a tiny alert-dispatch adapter that reuses existing `AlertRegistry`/`DataRepository` behavior.
- **Shape B, second-best:** `Spo2Sequencer` writes one stable current-tier feature plus one monotonic transition counter; CEP rules key off counter changes, not delayed booleans.
- **Baseline updates:** happen in one place only, inside the sequencer or a dedicated baseline store invoked by it. CEP should consume baseline, not own its refresh cadence.
- **`Spo2Payload`: keep it small.** Fix stale 1 Hz replay, but do not turn it into a multipurpose diagnostic struct unless needed.
- **Rules:** if rules remain at all, keep them thin. They should map `ENTER WARN/ALERT/EMERGENCY` to dispatch policy. They should not re-decide SPO2 physiology.

## 9. Risk Ranking

1. **Wrong physiology in Alert/Emergency logic.** Severity: High. Likelihood: High. Why: the plan directly diverges from the PDF on corroboration and emergency semantics.
2. **Orphaned active alerts on clear.** Severity: High. Likelihood: High. Why: `AlertRegistry.clear()` does not clear persisted/UI alerts.
3. **Pulse-edge loss or clobber.** Severity: High. Likelihood: High. Why: delayed false writes are inherently racy.
4. **Assumed 30 s cadence not actually delivered.** Severity: High. Likelihood: Medium. Why: current scheduler is 60 s by default.
5. **Baseline drift during desaturation.** Severity: High. Likelihood: Medium. Why: EMA on all qualifying readings can normalize the abnormal.
6. **PPG-red requirement not actually implementable in current service.** Severity: High. Likelihood: Medium. Why: repo reads `PPG_GREEN`, not verified red presence.
7. **Repo keys missing in rules.** Severity: Medium. Likelihood: High. Why: `activity.confidence` is absent from CEP today.
8. **Rule disabling assumption breaks config rollout.** Severity: Medium. Likelihood: High. Why: `disabled: true` is unsupported.
9. **Battery regression from rapid SPO2 polling.** Severity: Medium. Likelihood: Medium. Why: the plan moves from hourly attempts toward near-continuous eligibility.
10. **UI/category integration gaps.** Severity: Low. Likelihood: Medium. Why: new categories will work generically but not cleanly in current alert presentation.

## 10. Go-Forward Recommendation

Revise the plan first.

The right next step is not coding this whole design. The best next move is:

- lock down the SPO2 decision owner as **one class**, not five places
- rewrite Alert and Emergency logic to match the PDF exactly
- decide whether SPO2 alert dispatch should bypass CEP pulses
- prove the repo can actually produce the required cadence and the required signal-quality gate
- prototype only the critical path first:
  `SkinTempService` freshness fix + minimal `Spo2Sequencer` + one correct ALERT path + one correct CLEAR path

Until those are resolved, this plan is too coupled, too assumption-heavy, and too easy to get wrong in production.
