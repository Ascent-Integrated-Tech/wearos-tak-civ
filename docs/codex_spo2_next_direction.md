# SPO2 Next Direction for `WEAR-441`

## 1. Best Course of Action

The best architecture is:

- **`SkinTempService` owns acquisition and sampling mode selection**
- **`Spo2Sequencer` owns SPO2 validity, consecutive-reading state, tier decisions, baseline reads, and tier clears**
- **CEP does not decide SPO2 physiology**
- **CEP still owns dispatch policy, operator routing, logging, throttling, and alert lifecycle integration**

That is a **hybrid**, but not the v7 hybrid. The split must be hard:

- `SkinTempService`: when to measure, whether the device is in a mode that allows a measurement, and passing each completed measurement attempt into the sequencer.
- `Spo2Sequencer`: whether a reading is valid, whether it continues a consecutive sequence, whether WARN/ALERT/EMERGENCY/clear should occur, and whether baseline may be updated.
- CEP: convert final sequencer transitions into the repo’s existing alert/dispatch machinery. CEP should not re-check motion, cadence, baseline math, or consecutive rules.

### Why this is the right shape

- The PDF requires **consecutive valid readings**, which is sequence logic, not CEP window logic.
- SPO2 is **intermittent**, so CEP’s multi-rate support helps with surrounding context, but it is not the right place to derive the core SPO2 state machine.
- Battery constraints mean measurement cadence must be **stateful and selective**, which belongs in the service layer.
- The repo already has CEP alert transport and clearing infrastructure, so reusing CEP for **dispatch**, not **decision-making**, is the right balance.

### How consecutive valid readings should be modeled

Model them as a **small ordered ring of completed valid SPO2 readings** inside `Spo2Sequencer`, each with:

- `pct`
- `ts`
- validity reason inputs already resolved at ingest time
- optional corroboration flags snapshot if needed

The sequencer must:

- reject invalid readings
- clear/reset the run when the gap between valid readings exceeds 60 s
- evaluate WARN/ALERT/EMERGENCY only from the last N **valid completed measurements**

That matches the PDF and avoids fake continuity from 1 Hz re-emission or CEP windows.

### How multi sample-rate support should be used

Use multi sample-rate support for **context streams**, not to force SPO2 into a continuous model.

- Activity, off-body, HR, pressure, sleep, and any high-risk context can keep their own native cadences.
- SPO2 remains an **on-demand discrete measurement stream**.
- The sequencer consumes completed SPO2 attempts and reads the latest context snapshot from the service or from a thin context adapter.

So the feature benefits from multi-rate support indirectly:

- fast streams tell you **when to attempt SPO2**
- slow/intermittent SPO2 gives you **decision samples**

### Where WARN/ALERT/EMERGENCY should be decided

They should be decided **before CEP**, in `Spo2Sequencer`.

CEP should still own:

- alert dispatch into existing handlers
- TAK/SMS policy
- throttling / dedupe
- persistent alert lifecycle integration
- operator-visible logging

CEP should receive **final tier transition events**, not raw SPO2 physiology ingredients.

## 2. Battery-Aware Sampling Strategy

The right model is a **state-based cadence**, not a fixed cadence.

### Recommended modes

1. **Baseline mode**
- Goal: gather low-cost normal data to build or refresh baseline
- Preconditions: on-wrist, still, acceptable confidence, no active SPO2 concern
- Cadence: **every 10-15 minutes**
- Notes: only while the user is in a low-motion state long enough to justify an attempt

2. **Watch mode**
- Goal: periodically check for emerging desaturation in suitable conditions
- Preconditions: on-wrist, still/low-motion, baseline already available or provisional collection ongoing
- Cadence: **every 3-5 minutes**
- Notes: this is the normal monitoring mode if product requires actual SPO2 alerting readiness

3. **Confirmation mode**
- Goal: satisfy the PDF’s consecutive-valid requirement after a suspicious valid low reading
- Entry: first qualifying low reading or relevant high-risk context
- Cadence: **every 30-45 seconds**
- Duration: until one of:
  - N-valid sequence resolves to no alert
  - WARN/ALERT/EMERGENCY decision reached
  - repeated invalid attempts hit a stop threshold
  - max confirmation window reached, e.g. **3-5 minutes**

4. **Recovery mode**
- Goal: confirm recovery / clear active tier without staying in high-power confirmation forever
- Entry: after active WARN/ALERT/EMERGENCY when reading improves
- Cadence: **every 60-90 seconds**
- Duration: until clear condition is satisfied or concern returns

5. **Backoff mode**
- Goal: protect battery and avoid optical churn when readings are unavailable
- Entry: repeated timeouts, unusable signal, off-wrist, motion too high
- Cadence: **5-15 minutes**, depending on cause

### When SPO2 should be sampled

Normal attempts should require:

- on-wrist
- still / low-motion gate
- signal prerequisites that make a successful measurement plausible

Confirmation attempts should be allowed when:

- a first low valid reading has already occurred
- a high-risk context is active
- an active SPO2 tier needs confirmation or recovery tracking

### Should the app escalate sampling temporarily from other signals?

Yes.

That is the best use of multi sample-rate support in this repo.

Recommended triggers into **Confirmation mode**:

- first valid SPO2 below warn/alert/emergency criteria
- high-risk context flag becoming active
- strong corroborating signal that makes hypoxemia more plausible

But do **not** trigger high-rate SPO2 from generic activity or noisy physiology alone. Confirmation mode must stay bounded in time.

### Baseline collection cadence vs alert confirmation cadence

They should be different.

- Baseline collection: sparse and cheap, because battery matters and baseline does not require dense measurement.
- Alert confirmation: temporarily dense, because the PDF requires consecutive valid readings over roughly 2 minutes.

### What cadence model is realistic here

For this repo, the realistic model is:

- do **not** run near-continuous SPO2 in the background
- use sparse baseline/watch attempts
- use **temporary 30-45 s confirmation cadence** only when a valid low or high-risk condition justifies it

That is the only practical way to satisfy the PDF intent without turning SPO2 into a battery-heavy periodic sensor.

## 3. Follow-Up Questions on the Other Issues

### Must answer before coding

1. Can the current Samsung integration in `SkinTempService` actually observe the required **red-channel / 30 s PPG-red presence**, or does the implementation only have green-derived perfusion heuristics?
2. What is the shortest **stable real-device SPO2 attempt cadence** that succeeds without unacceptable battery or optical contention: 30 s, 45 s, 60 s?
3. What exact corroboration inputs exist today and are trustworthy enough for phase 1:
   off-wrist, stillness, sleep, trauma/high-risk flag, pressure/altitude context, anything else?
4. Should final SPO2 tier transitions be sent to CEP as a **stable transition event**, or should dispatch bypass CEP and call the existing alert persistence/dispatch path directly?
5. If sequencer clears an alert, what is the single end-to-end clear path that also clears `AlertRegistry`, persistent alert state, and UI state?
6. What is the minimum acceptable baseline model for first ship:
   provisional static default, sparse rolling median, or constrained update model?
7. What is the product-approved representation of **high-risk context** in this repo:
   explicit user/profile flag, external event, mission mode, or not available yet?

### Can answer during implementation

1. Should SPO2 transition events carry just `tier + state + pct + ts`, or do they also need a reason code for logging?
2. Do new SPO2 categories need custom icons/text in `PhysioAlertsActivity`, or is generic category handling acceptable for the first iteration?
3. Should baseline state live only in `ProfileStore`, or should a small baseline-status mirror be exposed for debug/UI?
4. What invalid-attempt threshold should enter Backoff mode: count-based, time-based, or both?
5. Should recovery cadence be 60 s or 90 s after active alerting?

### Can defer until field validation

1. Is the baseline model stable enough, or do we need a more formal rolling median by altitude band?
2. Are confirmation-mode cadences too aggressive for battery on field devices?
3. Does high-risk context legitimately justify reduced N in operational use, or should reduced N be limited to Emergency only?
4. Do some mission contexts need different backoff or cadence settings?
5. Are operator-facing clear events useful enough to surface explicitly in UI/log timeline?

## 4. Corrected Logic Direction

### WARN

Safest PDF-aligned interpretation:

- require `N >= 4` consecutive **valid** readings
- each reading must be `<= baseline - 4`
- most of those readings should be in the `92-94` range, as the PDF states
- WARN should be suppressed while baseline is provisional unless product explicitly approves a fallback rule

So WARN is **baseline-relative sustained desaturation**, not just “some lows.”

### ALERT

Corrected logic:

- require `N >= 4` consecutive **valid** readings
- all N must be `< 90`
- require corroboration:
  - at least one reading also `<= baseline - 4`, or
  - approved high-risk context

That corroboration belongs in the sequencer decision, not in CEP rules.

### EMERGENCY

Corrected logic:

- trigger when there is a valid reading `< 88`
- require corroboration:
  - another valid reading `< 90` within about 2 minutes, or
  - approved high-risk context
- reduced N is acceptable here, but only as the PDF allows:
  - effectively `1 very low + corroboration`, not “all of N below 88”

### Corroboration handling

Corroboration should be represented as a **small explicit input set** to the sequencer:

- baseline-drop corroboration
- second-low-reading corroboration
- high-risk-context corroboration

Do not let CEP try to reconstruct corroboration after the fact from unrelated feature timing.

### Reduced N handling

Reduced N should apply only where the PDF clearly allows it:

- Emergency
- possibly Alert only if product explicitly maps “high-risk context” to that relaxation

Do not invent alternate thresholds like `92` or `89` to simulate reduced N.

### Clear semantics

Clear should also be sequencer-owned.

Recommended clear behavior:

- active tier remains until enough subsequent **valid** readings show recovery above that tier’s clear threshold
- clear requires recovery confirmation, not just absence of data
- off-wrist and long outage should end active monitoring state, but whether they force an operator-visible clear should be an explicit product decision

Pragmatic first-ship rule:

- physiological clear: only from recovery-valid readings
- operational reset: off-wrist/service stop/outage ends the sequencer state, and alert lifecycle handling must decide whether to clear or mark unavailable

### Baseline during active desaturation

Do **not** update baseline during active desaturation or while any SPO2 tier is active.

Safest direction:

- baseline updates only from clearly normal, low-motion, on-wrist readings
- no baseline update during Confirmation mode after a suspicious low
- no baseline update while WARN/ALERT/EMERGENCY is active

That prevents the baseline from drifting downward and masking the event.

## 5. Simplified Target Design

### Keep

- `SkinTempService` as the acquisition owner
- new `Spo2Sequencer`
- `ProfileStore` as baseline owner
- CEP alert dispatch infrastructure

### Remove

- `Spo2TierEventFifo`
- CEP edge booleans
- CEP-side SPO2 physiology logic
- payload expansion that is only useful for internal sequencing

### Recommended component design

#### `SkinTempService`
- owns SPO2 attempt scheduling
- owns mode transitions: baseline/watch/confirmation/recovery/backoff
- provides completed measurement results plus current context snapshot to the sequencer

#### `Spo2Sequencer`
- validates readings against PDF gates
- tracks consecutive valid run with `<=60 s` gap logic
- reads/writes baseline via `ProfileStore`
- decides WARN/ALERT/EMERGENCY/clear transitions
- emits **final transition events only**

#### `ProfileStore`
- owns baseline persistence only
- stores provisional state and metadata needed for baseline validity
- does not own alert logic

#### CEP
- receives final sequencer transitions
- maps them to alert dispatch policy
- owns throttling, TAK/SMS policy, persistence integration, and operator logging
- does not re-evaluate SPO2 medical logic

### Do we still need `Spo2Payload` expansion?

No, not as part of the core design.

Fix stale re-emission first. Keep raw SPO2 payloads minimal unless another consumer needs more fields.

### Should `Spo2Sequencer` directly emit final tier transitions?

Yes.

That is the central simplification. The transition event is the contract. CEP consumes it for dispatch.

## 6. Implementation Phasing

### Phase 0: Validation Spike

- Objective: prove the repo/device assumptions that matter
- Scope:
  - confirm red vs green PPG reality
  - confirm feasible confirmation cadence on device
  - inventory corroboration inputs actually available
  - decide clear path ownership
- Deferred:
  - baseline sophistication
  - UI polish
- Acceptance:
  - written answers to the must-answer questions
  - chosen cadence envelope
  - chosen event/dispatch contract

### Phase 1: Freshness and Sampling Control

- Objective: stop fake consecutive behavior and add battery-safe stateful sampling
- Scope:
  - fix stale SPO2 1 Hz re-emission
  - add service-side sampling modes
  - add bounded confirmation cadence
- Deferred:
  - full WARN logic
  - UI/category polish
- Acceptance:
  - one completed SPO2 attempt yields one logical reading
  - service can enter/exit confirmation mode cleanly
  - no uncontrolled rapid polling

### Phase 2: Minimal Sequencer + Emergency/Alert Core

- Objective: ship the safest high-value decision path first
- Scope:
  - introduce `Spo2Sequencer`
  - implement valid-reading gate
  - implement consecutive-valid logic
  - implement corrected ALERT and EMERGENCY decisions
  - wire final transitions into CEP dispatch
- Deferred:
  - full baseline-derived WARN
  - altitude-aware baseline
- Acceptance:
  - ALERT/EMERGENCY fire only from valid consecutive readings with correct corroboration
  - clearing path is end-to-end correct

### Phase 3: Baseline + WARN

- Objective: add baseline-relative logic safely
- Scope:
  - add `ProfileStore` baseline model
  - add provisional handling
  - implement corrected WARN logic
  - freeze baseline during active concern
- Deferred:
  - advanced altitude adaptation
- Acceptance:
  - WARN only fires from established baseline conditions
  - baseline does not drift during desaturation runs

### Phase 4: Dispatch Hardening + UI Integration

- Objective: make operator behavior clean and predictable
- Scope:
  - final category mapping
  - alert text, severity policy, TAK/SMS policy
  - UI handling for new SPO2 categories
  - logging / debug visibility
- Deferred:
  - field-tuned thresholds or mission-specific variants
- Acceptance:
  - alerts clear correctly in registry, repo, and UI
  - categories render acceptably

### Phase 5: Field Validation and Tuning

- Objective: tune cadence, baseline behavior, and reduced-N policy from real use
- Scope:
  - battery impact measurement
  - invalid-attempt/backoff tuning
  - confirmation/recovery cadence tuning
  - baseline refinement if needed
- Deferred:
  - none; this is tuning
- Acceptance:
  - acceptable battery impact
  - acceptable false-positive / false-negative profile

## 7. Final Recommendation

The architecture to implement is:

- `SkinTempService` chooses **when** to measure
- `Spo2Sequencer` decides **what the measurements mean**
- CEP decides **how to dispatch the final tier transitions**

The battery-aware sampling model to implement is:

- sparse **Baseline mode** at `10-15 min`
- moderate **Watch mode** at `3-5 min`
- bounded **Confirmation mode** at `30-45 s`
- bounded **Recovery mode** at `60-90 s`
- conservative **Backoff mode** at `5-15 min`

The top unresolved questions that must be answered first are:

- do we truly have the required red-channel / 30 s signal gate on device
- what confirmation cadence is actually feasible on hardware
- what corroboration signals are truly available and trusted
- how final clear events will work end-to-end
- what exact baseline model is approved for first ship
- whether sequencer transitions go through CEP as stable events or bypass CEP for dispatch

The first implementation step to take next is:

- **Phase 0 validation spike**, specifically:
  1. verify real device SPO2 cadence feasibility
  2. verify red-vs-green PPG capability
  3. decide the single transition/clear contract between `Spo2Sequencer` and CEP

Until those three are answered, coding the full feature is premature.
