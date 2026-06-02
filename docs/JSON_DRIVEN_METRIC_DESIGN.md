# JSON-Driven Metric Design

Date: 2026-06-02
Scope: redesign baseline/trend metric handling so `cep_rules.json` is the declarative source of truth for metric behavior.

## Purpose

The current implementation uses per-metric Kotlin adapter classes to define source mapping, eligibility, baseline policy, trend policy, and selected output publication. That works, but it scales poorly as more metrics are added.

This design moves metric-specific behavior into `cep_rules.json` while keeping computation in reusable engine modules.

Goals:

- make metric behavior declarative
- keep computation modular and shared
- avoid one Kotlin adapter file per metric when the behavior is mostly configuration
- preserve `selected.*` as the rule-facing contract
- keep source-specific sensor interpretation isolated from baseline/trend math

Non-goals:

- no new feature expression language
- no rewrite of the existing rules engine
- no server-side redesign
- no requirement that every sensor can be expressed as plain JSON without hooks

## Core Principle

`cep_rules.json` should define what a metric is and how it behaves.

The engine should define how that metric is computed.

The source hook layer should define how raw sensor payloads are normalized into engine-friendly samples.

In short:

- JSON owns policy
- engine owns math
- source hooks own raw sensor translation

## Why This Change Is Needed

The current adapter-per-metric model is modular in the narrow sense that each metric is isolated, but it creates a growing number of Kotlin files that mostly encode constants and wiring.

That becomes a maintenance problem when the application grows to more metrics such as:

- HRV
- resting HR
- SpO2
- skin temperature
- respiratory rate
- sleep-related physiological scores

The right boundary is not "one class per metric."
The right boundary is:

- one shared computation pipeline
- one declarative metric spec per metric
- optional source hooks only when raw sensor format needs special handling

## Desired Architecture

The system should be split into three layers.

### 1. Metric Spec Layer

Lives in `cep_rules.json`.

Defines:

- metric id
- source key
- processor type
- baseline policy
- trend policy
- gating policy
- selected output mapping
- optional source hook identifier

This layer is the source of truth for metric behavior.

### 2. Source Hook Layer

Thin Kotlin hooks that translate raw source payloads into normalized samples.

Examples:

- Samsung heart-rate tracker payloads
- Samsung IBI list to HRV RMSSD
- vendor-specific sensor payloads

Responsibilities:

- parse raw payloads
- validate input shape
- compute a normalized scalar when needed
- attach minimal context needed for eligibility
- return `null` when the sample should not enter the pipeline

Responsibilities it should not have:

- baseline computation
- trend classification
- selected output publication
- rule evaluation

### 3. Shared Engine Layer

Reusable Kotlin modules that compute:

- baseline windows
- baseline values
- confidence and readiness
- trend deviation
- trend direction
- hysteresis
- stale/off-wrist invalidation
- selected output publication

## Typed Internal Models

The engine should not operate on raw untyped JSON maps after startup.

The JSON file is the source of truth, but it should be parsed into strongly typed internal models at startup so the rest of the engine remains testable and predictable.

Recommended internal shape:

- `MetricSpec`
- `MetricBaselinePolicy`
- `MetricTrendPolicy`
- `MetricGatingPolicy`
- `MetricSelectedOutputMapping`
- `MetricSourceHookBinding`
- `MetricGatingContext`

This lets the current calculator and baseline tests stay strongly typed while the configuration moves to JSON.

### JSON Defaults and Runtime Overrides

JSON is the default source of metric behavior.

Runtime settings from `DataRepository` may override JSON defaults at startup, but only before the metric registry is built.

Precedence:

1. JSON metric spec
2. user/runtime overrides from `DataRepository`
3. parsed typed internal config used by the engine

This preserves existing user settings while keeping JSON as the declarative baseline.

## Metric Spec Model

The proposed `metrics[]` section in `cep_rules.json` should describe each metric using a stable schema.

Example shape:

```json
{
  "id": "hrv.rmssd_ms",
  "source": "hrv.rmssd_ms",
  "processor": "baseline_trend",
  "source_hook": "samsung_hrv_rmssd",
  "gating": {
    "on_wrist": true,
    "still_or_sleeping": true,
    "fresh_within_sec": 10
  },
  "baseline": {
    "estimator": "trimmed_mean",
    "window_sec": 604800,
    "min_samples": 20,
    "max_staleness_sec": 43200,
    "confidence_thresholds": {
      "warming": 5,
      "ready_low": 10,
      "medium": 20,
      "high": 40
    }
  },
  "trend": {
    "short_window_sec": 3600,
    "long_window_sec": 86400,
    "enter_percent": 10,
    "return_to_stable_inner_percent": 7
  },
  "selected_outputs": {
    "trend_direction": "selected.trends.hrv.direction",
    "trend_short_term": "selected.trends.hrv.short_term",
    "trend_long_term": "selected.trends.hrv.long_term",
    "low_threshold": "selected.thresholds.hrv.low_rmssd_ms",
    "low_threshold_warn": "selected.thresholds.hrv.low_rmssd_ms_warn"
  }
}
```

### Canonical Output Slots

Each processor type must define the valid left-hand-side slots for `selected_outputs`.

For `baseline_trend`, the canonical slots are:

- `trend_direction`
- `trend_short_term`
- `trend_long_term`
- `trend_deviation`
- `trend_percent_deviation`
- `low_threshold`
- `low_threshold_warn`

The static slot schema is separate from the per-tick computation result.

Recommended naming:

- `MetricOutputContract` for the static slot schema
- `MetricComputationResult` for the dynamic processor result

Validation must fail fast if a metric spec uses an unknown slot such as `trend_dir`.

## Where `selected_outputs` Comes From

These keys are not invented arbitrarily.

They come from the current rule-facing contract already used by the codebase.

In the existing implementation:

- `HrvRmssdMetricAdapter` publishes `selected.trends.hrv.direction`
- it also publishes `selected.trends.hrv.short_term`
- it also publishes `selected.trends.hrv.long_term`
- it also publishes `selected.thresholds.hrv.low_rmssd_ms`
- it also publishes `selected.thresholds.hrv.low_rmssd_ms_warn`

The current HRV adapter is the precedent for the selected-output mapping. The redesign moves that mapping out of Kotlin and into JSON, but the names themselves remain the same so rules do not need to change.

In other words:

- the output names are taken from the current working HRV contract
- the JSON merely makes them declarative
- the engine still computes the values

## Processor Types

The design should support a small set of processor types.

### baseline_trend

Use when a metric needs:

- a rolling baseline
- a trend value against that baseline
- a direction classification
- selected outputs for rules

Produces internal outputs such as:

- `baseline.value`
- `baseline.sample_count`
- `baseline.status`
- `baseline.confidence`
- `trend.deviation_from_baseline`
- `trend.percent_deviation_from_baseline`
- `trend.short_term`
- `trend.long_term`
- `trend.direction`

This processor type should be the first implementation target.

Concrete runtime mapping:

- `MetricProcessorType.BASELINE_TREND` maps to `BaselineTrendProcessor`
- the mapping should be registered at startup through a factory or processor registry
- the engine should not hardcode `if/when` branches for processor selection once the registry exists

### window_only

Use when a metric needs rolling windows but not learned baseline state.

Status for phase 1:

- deferred unless a concrete metric needs it immediately
- if implemented later, it must define its own input/output contract and slot schema

### delta_only

Use when a metric only needs window-to-window differences.

Status for phase 1:

- deferred unless a concrete metric needs it immediately
- requires an explicit output contract before use

### slope_only

Use when a metric only needs a rolling slope.

Status for phase 1:

- deferred unless a concrete metric needs it immediately
- requires an explicit output contract before use

Each processor type must have a strict input contract and a strict output contract.

## Source Hook Model

The source hook is the only Kotlin extension point allowed to understand a raw vendor payload.

### Hook Input Shape

The hook input must preserve the original payload type while still letting the engine stay generic.

Recommended hook signature:

```kotlin
interface MetricSourceHook {
    val hookId: String
    fun normalize(payload: Any, context: MetricGatingContext): NormalizedMetricSample?
}
```

The hook receives `Any` and casts internally to its expected payload type.

This keeps the engine generic and makes the source-specific coupling explicit inside the hook, where it belongs.

### `MetricGatingContext`

`MetricGatingContext` is the typed runtime input used by hooks and the shared processor gate evaluation.

It is built by the runtime orchestrator from engine state and recent feature values, not by the hook itself.

Minimum recommended fields:

- `onWrist: Boolean`
- `isStill: Boolean`
- `isSleeping: Boolean`
- `isAwake: Boolean`
- `lastSampleAgeMs: Long?`
- `nowMs: Long`
- `isFresh: Boolean`
- `rawSourceKey: String`

Optional extension fields can be added later if a metric needs additional source-independent runtime flags.

### Hook Registry

Hooks must be registered by ID, not by hardcoded class name in the engine.

The startup orchestrator should build a registry of the form:

- `Map<String, MetricSourceHook>`

The metric spec loader resolves `source_hook` by looking up that registry.

Resolution rules:

- unknown hook id is a startup validation error
- missing hook is allowed only when the metric spec does not require one
- one hook can be reused by multiple metric specs if the raw source shape is shared

### Source Hooks in Phase 1

Not every metric needs its own hook.

If the raw value is already a usable scalar and the eligibility logic can be expressed in the shared gating policy, a dedicated hook may be unnecessary.

For example:

- HRV likely needs a Samsung-specific hook because it depends on IBI parsing and RMSSD derivation
- resting HR may not need a dedicated hook if the source payload is already the normalized scalar and the shared gating policy is sufficient

The design should not create empty hooks for symmetry alone.

### Dispatch Path

The dispatch path should be explicit:

1. raw payload arrives on a source key such as `hrv.rmssd_ms`
2. the engine finds the metric spec(s) subscribed to that source
3. if the metric spec declares a `source_hook`, the payload is handed to that hook
4. the hook emits a normalized sample
5. the shared processor consumes the normalized sample and computes baseline/trend state
6. the selected-output mapper publishes `selected.*` keys

This means:

- `source` is the subscription key
- `source_hook` is the normalization strategy
- the engine orchestrates the flow
- the hook does not own baseline or trend logic

### Typed Payload Bridging

The dispatch router must bridge existing typed CEP payloads to metric source keys.

This mapping must be explicit at startup, not implied by convention.

Acceptable strategies:

- a registry mapping payload class or topic to metric `source`
- a shared source-key convention that matches existing `WindowStore` keys

The chosen mapping must be documented and validated, because this is the entry point from the current CEP event path into the new metric runtime.

Example responsibilities for an HRV hook:

- read Samsung HR continuous payload fields
- read IBI list and status list
- filter invalid intervals
- compute RMSSD
- emit a normalized metric sample

Example responsibilities for a heart-rate hook:

- read raw heart-rate callback
- validate on-wrist / stillness / wake context
- emit normalized heart-rate samples

## Gating Model

The gating model should be split into two parts.

### JSON gating

Use JSON for simple declarative gates:

- `on_wrist`
- `still_or_sleeping`
- freshness guards

If `on_wrist` is required and the gate fails, the shared processor must apply hard invalidation behavior:

- clear active partial windows
- reset hysteresis state
- prevent learning from the off-wrist sample
- publish unknown/inactive selected outputs

That behavior is part of the shared processor contract, not an optional rule-level convenience.

### Gating Context

The gating policy should be evaluated against a typed runtime context that can include:

- on-wrist state
- stillness
- sleep state
- freshness age
- other source-independent eligibility flags

This context should not require the hook to own the whole state machine unless the gating is truly source-specific.

### Hook gating

Use source hooks for timing-aware or stateful gating:

- ignore the first period after reattachment
- require stillness for a minimum duration
- screen-off flush handling
- sensor-specific cooldowns

This keeps the JSON readable without pretending it can express every physiological timing condition.

### No-Hook Dispatch Path

Metrics without a `source_hook` still need a defined path.

In that case:

1. the dispatch router resolves the metric spec from the source key
2. the raw scalar value is converted into a `NormalizedMetricSample` by the shared runtime path
3. the shared processor consumes that normalized sample exactly as it would a hook-produced sample

This means the processor input contract is still a `NormalizedMetricSample`, even when no hook exists.

The shared runtime path is responsible for:

- constructing the normalized sample
- applying shared gating policy
- invoking the processor

## Trend Values and Rule Usage

Trend values are computed in the engine.

They are not computed by `cep_rules.json`.

The rule file should only reference published keys.

Recommended usage:

- internal engine keys:
  - `trend.<metric>.deviation_from_baseline`
  - `trend.<metric>.percent_deviation_from_baseline`
  - `trend.<metric>.short_term`
  - `trend.<metric>.long_term`
  - `trend.<metric>.direction`
- rule-facing keys:
  - `selected.trends.*`
  - `selected.thresholds.*`

If a numeric trend value needs to be used by rules, it should be promoted into `selected.*`.

If it is only useful internally or for debugging, keep it under `trend.*`.

## Validation

Metric loading must be strict.

Validation should reject:

- unknown processor types
- missing required fields
- invalid threshold values
- impossible confidence thresholds
- malformed selected output mappings
- unknown source hook identifiers
- invalid selected-output slot names for the chosen processor type
- source hook required but missing
- confidence thresholds must be strictly increasing
- trend enter percent must be greater than trend return-to-stable inner percent
- trend short window must be smaller than trend long window
- baseline window must be at least as large as the trend long window
- duplicate metric ids must fail startup
- freshness thresholds that are obviously incompatible with the metric cadence should log or fail fast depending on severity
- missing or duplicate source keys when the metric type requires a unique source
- invalid or missing processor-to-class registry mappings at startup

Startup policy:

- fail fast for invalid active metrics
- do not silently skip malformed metrics unless the metric is explicitly optional
- report validation errors by metric id and field path

This avoids hidden runtime failures in a health pipeline.

## Migration Plan

The redesign should be introduced without breaking the existing working path immediately.

### Phase 1

- add `metrics[]` parsing
- keep current adapters running
- validate metric specs in parallel

### Phase 2

- add source hook registration
- bind HRV to a source hook
- preserve current outputs

### Phase 3

- move baseline/trend policy selection into JSON
- move selected-output mapping into JSON
- keep the shared engine computation pipeline
- keep the existing calculator and trend tests by parsing JSON into typed internal models
- merge `DataRepository` overrides into parsed metric specs before registry construction

### Phase 4

- shadow compare old and new paths in tests
- confirm baseline status, trend direction, and selected outputs match
- run the existing HRV simulation scenarios end to end, not just static input/output snapshots
- compare behavior using JSON-parsed runtime specs, not test-constructed policy objects

### Phase 5

- remove adapter-specific metric wiring
- keep only source hooks where raw sensor parsing is genuinely unique

## Progress Checklist

Use this checklist to track implementation progress against the design.

### Design and Schema

- [ ] finalize the `metrics[]` JSON schema
- [ ] define the canonical output slots for every supported processor type
- [ ] define the internal typed config models used after JSON parsing
- [ ] define the runtime merge policy for `DataRepository` overrides

### Runtime Infrastructure

- [ ] implement `MetricHookRegistry`
- [ ] implement `ProcessorRegistry`
- [ ] implement `MetricRegistry`
- [ ] implement `MetricRuntimeOrchestrator`
- [ ] implement `MetricDispatchRouter`
- [ ] define and validate `MetricGatingContext`

### Processor Layer

- [ ] implement `BaselineTrendProcessor`
- [ ] implement the stateless processor contract
- [ ] implement per-metric runtime state storage keyed by metric id
- [ ] implement `MetricComputationResult`
- [ ] implement `MetricOutputMapper`

### Source Hooks

- [ ] implement `SamsungHrvRmssdHook`
- [ ] decide whether `HrSourceHook` is needed
- [ ] document the typed payload bridging strategy
- [ ] validate hook resolution by id at startup

### Validation

- [ ] validate duplicate metric ids
- [ ] validate unknown processor types
- [ ] validate unknown hook ids
- [ ] validate canonical selected-output slots
- [ ] validate confidence threshold ordering
- [ ] validate hysteresis ordering
- [ ] validate baseline and trend window relationships
- [ ] validate obvious freshness and cadence mismatches

### Migration

- [ ] add JSON metric parsing in parallel with current adapters
- [ ] keep current adapters working while the new registry is added
- [ ] route HRV through the new source hook and processor path
- [ ] route HR through the new source or no-hook path
- [ ] run HRV end-to-end simulation scenarios against the new runtime
- [ ] shadow compare old and new runtime behavior
- [ ] remove `HrvRmssdMetricAdapter.kt`
- [ ] remove `HrRestingMetricAdapter.kt`
- [ ] remove any remaining feature-specific metric adapter classes

### Completion Criteria

- [ ] all active metrics are driven by JSON specs
- [ ] no feature-specific metric adapter classes remain
- [ ] rules consume only published `selected.*` keys
- [ ] the new runtime passes the existing HRV simulation scenarios
- [ ] the new runtime passes baseline and trend unit tests
- [ ] the old adapter path is fully removed for migrated metrics

## Runtime Boundary

`CepEngine` should not own the metric-specific lifecycle once the new runtime exists.

The intended relationship is:

- `CepEngine` remains the top-level CEP host and rule evaluator
- `MetricRuntimeOrchestrator` owns metric loading, validation, hook binding, processor binding, and per-metric runtime state
- `CepEngine` delegates metric dispatch and metric-tick processing to the orchestrator

The migration must avoid running both the old adapter path and the new metric runtime for the same metric at the same time.

Per-metric activation should be explicit, not a hidden system-wide toggle.

## Metric Registry And State Ownership

The registries have different jobs:

- `MetricHookRegistry` owns `Map<String, MetricSourceHook>`
- `ProcessorRegistry` owns `Map<MetricProcessorType, MetricProcessor>`
- `MetricRegistry` owns the full runtime representation of each loaded metric

`MetricRegistry` should include:

- parsed metric spec
- bound source hook reference, if any
- selected processor type
- per-metric runtime state slot
- resolved output mapping

The shared processor should be stateless.

Per-metric state such as:

- active window accumulation
- last trend direction
- last accepted sample time
- qualified window history

should live in registry-owned state keyed by metric id, not inside the processor implementation itself.

## Processor Result Split

Keep the static contract separate from the per-tick result.

Recommended types:

- `MetricOutputContract` for canonical slots per processor type
- `MetricComputationResult` for dynamic values produced by one processor run

This avoids mixing schema with live data and makes output mapping easier to validate.

## MetricSourceHookBinding

`MetricSourceHookBinding` is the resolved runtime association between a metric spec and a hook instance.

It should not be treated as a config model.

Recommended placement:

- store it inside `MetricRegistry`
- derive it at startup from the parsed `source_hook` id and the `MetricHookRegistry`

What it should contain:

- the parsed `MetricSpec`
- the resolved `MetricSourceHook?`
- the bound `MetricProcessor`
- any resolved output mapping needed for runtime dispatch

This keeps the config layer free of runtime instances and makes the binding step explicit.

## Implementation Boundaries

What stays in Kotlin:

- source hooks
- shared processors
- validation
- orchestration
- typed internal config models
- startup registry construction

What moves to JSON:

- per-metric policy values
- selected output naming
- processor selection
- gating declarations
- source hook ids

What stays in `features[]` and `rules[]`:

- derived logic
- boolean conditions
- alert logic
- rule-facing consumption of `selected.*`

## Expected Outcome

After this redesign:

- a new metric usually requires only a JSON spec
- sensor-specific raw interpretation stays isolated
- baseline and trend logic remain shared
- rules stay stable
- the codebase stops accumulating near-identical metric adapter files

The end state is a declarative metric system with a small number of reusable engine primitives and only the minimum Kotlin code needed for raw sensor translation.
