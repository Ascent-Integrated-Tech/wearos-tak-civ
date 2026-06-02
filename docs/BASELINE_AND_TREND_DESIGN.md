# Baseline And Trend Design

Date: 2026-04-30
Scope: authoritative design for WEAR-419 and WEAR-470

## Implementation Progress

- [x] Generalize `ProfileStore` to metric-keyed baseline snapshots  
  - Implemented metric-keyed persistence helpers and snapshots in `app/src/main/java/com/aitech/weartak_civ/cep/engine/ProfileStore.kt`.
- [x] Formalize normalized sample / computed baseline models  
  - Added normalized sample, computed baseline, confidence/status, and trend result models in `app/src/main/java/com/aitech/weartak_civ/cep/engine/BaselineModels.kt`.
- [x] Add metric adapter abstraction  
  - Added `BaselineMetricAdapter` contract in `app/src/main/java/com/aitech/weartak_civ/cep/engine/BaselineModels.kt` and extracted concrete adapters into `app/src/main/java/com/aitech/weartak_civ/cep/engine/metrics/HrRestingMetricAdapter.kt` and `app/src/main/java/com/aitech/weartak_civ/cep/engine/metrics/HrvRmssdMetricAdapter.kt`.
- [x] Refactor HR baseline logic through generic pathway  
  - Replaced HR-only baseline update path with adapter-driven generic baseline/trend update flow in `app/src/main/java/com/aitech/weartak_civ/cep/engine/CepEngine.kt`.
- [x] Integrate HRV RMSSD adapter/path  
  - Added HRV RMSSD adapter class in `app/src/main/java/com/aitech/weartak_civ/cep/engine/metrics/HrvRmssdMetricAdapter.kt` and engine integration in `app/src/main/java/com/aitech/weartak_civ/cep/engine/CepEngine.kt`.
- [x] Add HRV baseline computation from qualified windows  
  - Added 60-second qualified-window aggregation before baseline updates for HRV in `app/src/main/java/com/aitech/weartak_civ/cep/engine/CepEngine.kt`.
- [x] Add HRV trend outputs  
  - Published HRV deviation, percent deviation, direction, and short/long outputs in `app/src/main/java/com/aitech/weartak_civ/cep/engine/CepEngine.kt`.
- [x] Add selected dynamic/static output publication for HRV  
  - Added selected HRV threshold/trend output keys in `app/src/main/java/com/aitech/weartak_civ/cep/engine/CepEngine.kt`.
- [x] Expand tests/simulation coverage  
  - Added focused baseline/trend unit tests in `app/src/test/java/com/aitech/weartak_civ/cep/engine/MetricBaselineCalculatorTest.kt` and adapter tests in `app/src/test/java/com/aitech/weartak_civ/cep/engine/metrics/MetricAdaptersTest.kt`.
  - Added HRV baseline/trend simulation scenarios (Phase 8) in `app/src/main/java/com/aitech/weartak_civ/service/CEPSimulationService.kt`.
  - Added selected-key rule-path bridge features for HRV in `app/src/main/res/raw/cep_rules.json`.
- [x] Generic persisted baseline warm-start and stale-state handling  
  - Added startup warm-start publication for all registered metric adapters in `app/src/main/java/com/aitech/weartak_civ/cep/engine/CepEngine.kt` with metric-policy staleness evaluation in `app/src/main/java/com/aitech/weartak_civ/cep/engine/MetricBaselineCalculator.kt`.
- [x] Replace engine-level HRV stale/unknown branching with adapter-driven output mapping  
  - Added generic unknown-trend output mapping in `app/src/main/java/com/aitech/weartak_civ/cep/engine/MetricFeaturePublisher.kt` and adapter hook usage in `app/src/main/java/com/aitech/weartak_civ/cep/engine/CepEngine.kt`.
- [x] Make baseline/trend calculator policy metric/config-driven  
  - Added `MetricBaselinePolicy` and typed `MetricRuntimeContext` in `app/src/main/java/com/aitech/weartak_civ/cep/engine/BaselineModels.kt`; calculator now uses adapter policy for estimator, thresholds, and staleness in `app/src/main/java/com/aitech/weartak_civ/cep/engine/MetricBaselineCalculator.kt`.
- [x] Add CEP-side off-wrist hard invalidation guards  
  - Added explicit off-wrist invalidation branch in `app/src/main/java/com/aitech/weartak_civ/cep/engine/CepEngine.kt` that clears active partial qualified windows, prevents learning/cadence updates, and publishes adapter-driven unknown/inactive outputs.
  - Added generic unknown-output clear-list support via `app/src/main/java/com/aitech/weartak_civ/cep/engine/MetricFeaturePublisher.kt` and adapter hooks in `app/src/main/java/com/aitech/weartak_civ/cep/engine/BaselineModels.kt`.
  - Added deterministic off-wrist state/reset tests in `app/src/test/java/com/aitech/weartak_civ/cep/engine/MetricAggregationStateOpsTest.kt`.
- [x] Wire core HRV policy knobs through `DataRepository`  
  - Added typed flows and `getHrvCepConfigOnce()` in `app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt` (keys `hrv_baseline_window_days`, `hrv_baseline_min_samples`, `hrv_trend_short_window_minutes`, `hrv_low_deviation_warn_percent`, `hrv_low_deviation_alert_percent`) with defaults in `app/src/main/java/com/aitech/weartak_civ/data/HrvCepConfig.kt`.  
  - `CepProvider` builds `HrvRmssdMetricAdapter` from repository snapshot; adapter maps window days, min samples, short trend window, and warn/alert percents into long retention, readiness bands, and selected thresholds (`selected.thresholds.hrv.low_rmssd_ms` / `low_rmssd_ms_warn`).
- [x] Trend classification hysteresis (generic)  
  - `MetricBaselinePolicy` carries `trendEnterPercent` and `trendReturnToStableInnerPercent`; `MetricBaselineCalculator` applies threshold-band hysteresis using `lastTrendDirection` on `MetricAggregationStateData`, reset when unknown/off-wrist publishes (`MetricBaselineTick.kt`).
- [x] `READY_LOW_CONFIDENCE` in calculator path  
  - `MetricBaselinePolicy.readyLowConfidenceMinSamples` gates the band after warming and before medium readiness; `MetricBaselineCalculator.computeBaseline` emits the status accordingly.
- [x] Estimator support and fail-fast  
  - Implemented `BaselineEstimator.Ema` (time-ordered) and `Percentile` (0–1, PERCENTILE.INC-style interpolation); unsupported `AnchoredSessionMean` throws `IllegalArgumentException` (no silent median fallback).
- [x] JVM `CepEngine` integration coverage for HRV  
  - `app/src/test/java/com/aitech/weartak_civ/cep/engine/CepEngineHrvBaselineIntegrationTest.kt` drives the engine’s metric tick path (warmup → ready → decline → off-wrist → transient clears) with Robolectric.
- [x] ProfileStore legacy cleanup  
  - Removed unused float `hr_rest_bpm` / max-HR helper paths; documented `upsertLegacyHrBaselineKeysForCompatibility()` as intentional legacy mirror for external readers of `cep_profile`.

### Post-Refactor Modularity Notes

- Core `CepEngine` orchestrates generic metric baseline/trend flow and no longer hardcodes HRV-specific unknown selected-output behavior.
- Metric adapters own metric-specific normalization and selected-output mappings; generic trend direction uses adapter policy bands (`trendEnterPercent` / `trendReturnToStableInnerPercent`) in `MetricBaselineCalculator` with per-metric hysteresis state on `MetricAggregationStateData`.
- Persisted baselines warm-start any metric registered via adapters, with metric-specific stale-status handling driven by adapter policy.

### Off-Wrist Guarding

- CEP treats off-wrist as a hard invalidation condition in the generic metric path.
- Off-wrist prevents normalization/learning and sample-cadence advancement for metric baseline/trend updates.
- Off-wrist clears active partial qualified windows immediately while retaining completed qualified historical windows.
- Off-wrist publishes unknown/inactive trend outputs using generic adapter-driven output mapping and clears transient selected trend outputs that should not remain active.
- Source-side gating and CEP-side gating are complementary: source gating blocks bad values from entering, while CEP gating prevents stale/leaked/off-wrist values from being interpreted as valid trend state.

## Linked Tickets

- `WEAR-419`: design and framework for generic per-user baseline and trend detection
- `WEAR-470`: add HRV and establish HRV baseline using the generic framework
- `WEAR-470` depends on `WEAR-419`

## Reader Guidance

This document is for engineers implementing or reviewing the next baseline/trend phase for Wear OS physiology features.

It assumes:

- familiarity with the current CEP pipeline
- basic Wear OS service lifecycle concepts
- basic HRV concepts such as RR/IBI and RMSSD
- awareness that Samsung Health Tracking is a source-specific integration detail

Recommended reading order:

- read the top sections first if you want the big picture
- read the architecture and domain model sections next if you are implementing framework pieces
- read the HRV-specific section if you are implementing WEAR-470
- use the later sections as reference for storage, lifecycle, risks, and rollout

If a reader is new to the topic, the document should be read top-down. If a reader already knows the architecture, the HRV example and adapter sections are the fastest entry points.

## Mental Model / Quick Overview

At runtime, the system is trying to answer a simple question: "is this user's current signal normal for them, or meaningfully different from their own recent history?"

Raw wearable data is not enough on its own. A single HRV number might be low because the user is moving, the watch just came on-wrist, signal quality is poor, or the user is in a genuinely different physiological state. Static raw thresholds miss that context.

The design therefore separates the pipeline into clear stages:

`Raw -> Normalized -> Baseline -> Trend -> Selected`

- `Raw`: source-specific sensor output such as RR intervals or heart-rate callbacks
- `Normalized`: a cleaned, metric-specific sample with quality and eligibility context
- `Baseline`: a per-user estimate of what is normal for that metric
- `Trend`: how current behavior compares to that personal normal over short and long windows
- `Selected`: the final rule-facing output that CEP rules consume

Baseline is needed because wearable signals vary widely across users. Trend is needed because even a valid personal baseline is not enough without understanding whether the current value is drifting, stable, or sharply changed. Together they let the system reason about "different from personal normal" instead of "above or below one raw number."

## Problem Statement

The app already ingests multiple wearable signals and has partial baseline groundwork in CEP, but baseline logic is currently narrow, metric-specific, and centered on first-pass HR threshold experimentation. WEAR-419 needs a reusable per-user baseline and trend framework that can support many physiological and environmental metrics. WEAR-470 then needs to plug HRV into that framework using the existing RR/IBI-based Samsung implementation as the concrete first adapter.

Wearable data is noisy, sparse, vendor-specific, and highly context dependent. A single raw sample is not sufficient to represent a user's normal state. We need a design that:

- computes baselines from historical samples per user
- distinguishes raw samples from normalized/eligible samples
- handles insufficient data explicitly
- produces short-term and long-term trend outputs
- avoids duplicate static-vs-dynamic rule families
- keeps HRV implementation grounded in the existing Samsung Health Tracking path

## Goals

- Establish a generic metric pipeline for raw sample ingestion, normalization, baseline computation, and trend detection.
- Support per-user isolation for learned metric state.
- Keep one rule path per alert behavior; rules should read selected outputs, not care whether values are static or baseline-derived.
- Support rolling windows, minimum sample thresholds, freshness requirements, and confidence levels.
- Use HRV as the primary concrete example for the first implementation.
- Reuse existing current-branch groundwork where possible:
  - [`BaselineModels.kt`](/home/ghost/Documents/Ascent/WearOS/WEAR-470-419_Base_HRV/app/src/main/java/com/aitech/weartak_civ/cep/engine/BaselineModels.kt)
  - [`ProfileStore.kt`](/home/ghost/Documents/Ascent/WearOS/WEAR-470-419_Base_HRV/app/src/main/java/com/aitech/weartak_civ/cep/engine/ProfileStore.kt)
  - [`WindowStore.kt`](/home/ghost/Documents/Ascent/WearOS/WEAR-470-419_Base_HRV/app/src/main/java/com/aitech/weartak_civ/cep/engine/WindowStore.kt)
  - existing `Topics.HRV` / `HrvPayload`

## Non-Goals

- No production code changes in this ticket phase.
- No medical diagnosis or medical-risk scoring.
- No server-side analytics redesign.
- No migrations, test implementations, or settings UI implementation in this document.
- No commitment to a single baseline algorithm for every metric; configuration remains metric specific.

## Current Design Direction To Preserve

The current branch already made the correct architectural decision for alerting:

1. Keep one alert rule per behavior.
2. Compute static threshold values and learned/baseline-derived values independently.
3. Publish one selected value per metric based on per-metric mode/configuration.
4. Have rules consume selected keys only.

This remains the design. What changes is the scope: the design now generalizes beyond HR and makes HRV the next concrete metric.

Examples of selected outputs:

- `selected.thresholds.hr.high_bpm`
- `selected.thresholds.hr.low_bpm`
- `selected.thresholds.hrv.low_rmssd_ms`
- `selected.trends.hrv.short_term`
- `selected.trends.hrv.long_term`

## Existing Architecture And Groundwork

Relevant current-branch architecture:

- [`SkinTempService.kt`](/home/ghost/Documents/Ascent/WearOS/WEAR-470-419_Base_HRV/app/src/main/java/com/aitech/weartak_civ/service/SkinTempService.kt) already owns Samsung Health Tracking integration for skin temp, SpO2, PPG, and HR continuous data.
- [`HeartrateService.kt`](/home/ghost/Documents/Ascent/WearOS/WEAR-470-419_Base_HRV/app/src/main/java/com/aitech/weartak_civ/service/HeartrateService.kt) emits raw HR to CEP.
- [`Topics.kt`](/home/ghost/Documents/Ascent/WearOS/WEAR-470-419_Base_HRV/app/src/main/java/com/aitech/weartak_civ/cep/model/Topics.kt) already defines `HRV`.
- [`HrvPayload.kt`](/home/ghost/Documents/Ascent/WearOS/WEAR-470-419_Base_HRV/app/src/main/java/com/aitech/weartak_civ/cep/model/payloads/HrvPayload.kt) already exists.
- [`WindowStore.kt`](/home/ghost/Documents/Ascent/WearOS/WEAR-470-419_Base_HRV/app/src/main/java/com/aitech/weartak_civ/cep/engine/WindowStore.kt) already stores `hrv.rmssd_ms`.
- [`CepEngine.kt`](/home/ghost/Documents/Ascent/WearOS/WEAR-470-419_Base_HRV/app/src/main/java/com/aitech/weartak_civ/cep/engine/CepEngine.kt) already has first-pass HR resting-baseline learning and trend publication.
- [`ProfileStore.kt`](/home/ghost/Documents/Ascent/WearOS/WEAR-470-419_Base_HRV/app/src/main/java/com/aitech/weartak_civ/cep/engine/ProfileStore.kt) already persists one numeric baseline snapshot for HR.

This design expands those concepts into a generic framework instead of adding isolated HRV-only logic.

## Reference HRV Implementation Summary

The HRV reference branch is:

- `/home/ghost/Documents/Ascent/WearOS/WEAR-383-RR-IBI-HRV`

The most important reference class is:

- `/home/ghost/Documents/Ascent/WearOS/WEAR-383-RR-IBI-HRV/app/src/main/java/com/aitech/weartak_civ/service/SkinTempService.kt`

What that reference implementation already proves:

- HR continuous data comes from Samsung Health Tracking `HealthTrackerType.HEART_RATE_CONTINUOUS`.
- RR/IBI data is read from `ValueKey.HeartRateSet.IBI_LIST` and `ValueKey.HeartRateSet.IBI_STATUS_LIST`.
- Invalid intervals are filtered before HRV calculation.
- HRV is derived as RMSSD from valid NN intervals.
- HR capture is continuous, with screen-off flush handling to force delivery while the watch is non-interactive.
- Stillness and on-wrist context already exist elsewhere in the app and can be reused for baseline eligibility gating.

Short reference snippet for RR/IBI ingestion:

The following snippet shows where raw RR/IBI values enter the pipeline from Samsung's heart-rate tracker. This matters because WEAR-470 should reuse this exact sensor-side source of truth.

```kotlin
val ibiList = dp.getValue(ValueKey.HeartRateSet.IBI_LIST)
val ibiStatusList = dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST)
if (ibiList != null && ibiList.isNotEmpty()) {
    val lastIbi = ibiList.last()
    if (lastIbi > 0) lastNonZeroIbi = lastIbi
}
```

Short reference snippet for valid NN filtering:

This snippet is the important quality gate. It turns "raw IBI values happened to arrive" into "these intervals are safe to use for HRV math."

```kotlin
val statusOk = if (hasStatus) (ibiStatusList!![i] == 0) else true
if (!statusOk) continue
if (ibi < NN_MIN_MS || ibi > NN_MAX_MS) continue
nnBuffer.addLast(ibi)
while (nnBuffer.size > MAX_NN_BUFFER) nnBuffer.removeFirst()
```

Short reference snippet for RMSSD derivation:

This snippet is the actual HRV derivation step. Once valid NN intervals exist, RMSSD is computed from beat-to-beat differences rather than using HR directly.

```kotlin
if (nnBuffer.size < MIN_NN_FOR_RMSSD) return null
var sumSq = 0.0
for (i in 1 until nnBuffer.size) {
    val diff = (nnBuffer.elementAt(i) - nnBuffer.elementAt(i - 1)).toDouble()
    sumSq += diff * diff
}
return sqrt(sumSq / (nnBuffer.size - 1))
```

These snippets should be treated as the canonical HRV sensor-side behavior for WEAR-470.

## Proposed Architecture

### Overview

The proposed flow is:

1. Sensor/service layer collects raw metric signals.
2. Metric adapter validates and normalizes those signals into baseline-eligible samples.
3. Baseline engine maintains rolling history and computes per-user baseline snapshots.
4. Trend engine compares recent windows against baseline and recent history.
5. Selection layer publishes rule-facing outputs and UI-facing status.

Conceptually:

`Raw sample -> Normalized sample -> Baseline window -> Computed baseline -> Trend/anomaly result -> Selected outputs`

Simple runtime flow:

`Sensor -> Adapter -> WindowStore -> Baseline Engine -> Trend Engine -> Selection Layer -> CEP Rules`

This is the most useful mental picture to keep while reading the rest of the document. Sensor code gathers facts. Adapter code interprets those facts for one metric. Baseline and trend logic reason over history. Selection publishes the final values the rest of CEP uses.

### Module Boundaries

#### 1. Sensor Collection Layer

Responsibilities:

- hardware/vendor integration
- permissions and tracker lifecycle
- raw sample capture
- source-specific quality/status handling

Current classes that remain source owners:

- `SkinTempService` for Samsung Health Tracking signals including HR, IBI, PPG, SpO2
- `HeartrateService` for Android `Sensor.TYPE_HEART_RATE`
- `ActivityRecognitionService` for activity/stillness context
- off-body and sleep topics already used by CEP

#### 2. Metric Adapter Layer

The metric adapter sits between source collection and generic baseline/trend logic.

Responsibilities:

- transform source-specific raw data into metric-specific normalized samples
- attach quality, provenance, and eligibility metadata
- apply metric-specific validity rules

This layer is proposed. It should wrap current source-specific logic, not replace it.

In runtime terms:

- a service receives source data
- that source data is packaged as a raw metric sample
- `normalize()` is called for the specific metric adapter
- if normalization succeeds, the adapter emits a normalized metric sample
- that normalized sample is what goes into history, baseline, and trend processing

For HRV, the adapter would sit directly after the RR/IBI parsing already happening in `SkinTempService`.

Small HRV pseudo-flow:

```kotlin
// Proposed runtime flow, preserving the current source-side RMSSD logic.
rawHrCallback
 -> read HEART_RATE / IBI_LIST / IBI_STATUS_LIST
 -> filter valid NN intervals
 -> compute RMSSD
 -> build NormalizedMetricSample(metric="hrv.rmssd_ms", value=rmssd, quality=..., baselineEligible=...)
 -> emit HrvPayload / store in WindowStore / feed baseline-trend pipeline
```

Interaction with CEP and `WindowStore`:

- the adapter does not replace CEP
- it prepares the metric-specific sample CEP can trust
- CEP / `WindowStore` remain the storage and feature-compute path for recent history
- the baseline and trend logic should consume those normalized values rather than reinterpreting raw sensor callbacks repeatedly

#### 3. Baseline Engine

Responsibilities:

- maintain rolling historical windows
- decide whether a normalized sample is baseline eligible
- compute baseline values using metric-specific estimator configuration
- persist baseline snapshots and metadata per metric per user

Current groundwork to reuse:

- `BaselineModels.kt`
- `WindowStore`
- `ProfileStore`

#### 4. Trend Engine

Responsibilities:

- derive short-term and longer-term trend outputs
- compare recent samples against baseline
- classify direction and severity
- suppress noisy oscillation

#### 5. Selection/Publishing Layer

Responsibilities:

- expose selected thresholds, trend states, confidence, and fallback status
- hide static-vs-dynamic internals from CEP rules
- provide rule-facing keys and UI-facing metadata

## Domain Model

The design must distinguish these entities explicitly.

### Raw Metric Sample

Untouched observation from a source.

Examples:

- HR continuous callback containing `HEART_RATE`, `IBI_LIST`, `IBI_STATUS_LIST`
- Android heart-rate sensor event
- SpO2 completion callback

Proposed shape:

```kotlin
data class RawMetricSample(
    val metric: MetricId,
    val source: MetricSource,
    val ts: Long,
    val values: Map<String, Any?>,
    val deviceState: DeviceStateSnapshot
)
```

### Normalized Metric Sample

Metric-specific, comparable, quality-scored sample suitable for history, trends, and possibly baseline learning.

Proposed shape:

```kotlin
data class NormalizedMetricSample(
    val metric: MetricId,
    val ts: Long,
    val value: Double,
    val unit: String,
    val quality: SampleQuality,
    val baselineEligible: Boolean,
    val context: Map<String, Any?> = emptyMap()
)
```

### Baseline Window

The rolling historical slice used to compute the next baseline.

Proposed shape:

```kotlin
data class BaselineWindow(
    val metric: MetricId,
    val startTs: Long,
    val endTs: Long,
    val sampleCount: Int,
    val eligibleSampleCount: Int,
    val values: List<Double>
)
```

### Computed Baseline

The persisted summary for a user and metric.

Proposed shape:

```kotlin
data class ComputedBaseline(
    val metric: MetricId,
    val value: Double?,
    val windowStartTs: Long?,
    val windowEndTs: Long?,
    val sampleCount: Int,
    val confidence: BaselineConfidence,
    val quality: BaselineQuality,
    val lastUpdatedTs: Long?,
    val status: BaselineStatus
)
```

### Trend / Anomaly Result

Derived interpretation of recent behavior relative to baseline and recent history.

Proposed shape:

```kotlin
data class TrendResult(
    val metric: MetricId,
    val direction: TrendDirection,
    val deviationFromBaseline: Double?,
    val percentDeviationFromBaseline: Double?,
    val shortTermSlope: Double?,
    val longTermSlope: Double?,
    val anomalyState: AnomalyState,
    val confidence: TrendConfidence,
    val ts: Long
)
```

## Metric Abstraction

The framework should be metric driven, not hardcoded by `if metric == HRV`.

Proposed interface:

```kotlin
interface BaselineMetricAdapter {
    val metricId: String
    fun normalize(raw: RawMetricSample): NormalizedMetricSample?
    fun isBaselineEligible(sample: NormalizedMetricSample): Boolean
    fun baselineConfig(): MetricBaselineConfig
    fun trendConfig(): MetricTrendConfig
}
```

Supporting configuration:

```kotlin
data class MetricBaselineConfig(
    val estimator: BaselineEstimator,
    val rollingWindowSec: Long,
    val minSamples: Int,
    val maxSampleAgeSec: Long?,
    val allowedQualities: Set<SampleQuality>,
    val minConfidenceForUse: BaselineConfidence
)
```

The current `BaselineModels.kt` already contains reusable primitives:

- `WindowSpec`
- `BaselineEstimator`
- `EligibilitySpec`
- `PersistenceSpec`
- `BaselineDefinition`
- `TrendDefinition`
- `ThresholdPolicyDefinition`

The recommended implementation direction is to extend those types rather than introduce a separate parallel model.

## Baseline Calculation Strategy

### Generic Strategy

Each metric baseline is computed from historical normalized samples for one user and one metric only.

Design rationale:

- baselines use windows instead of raw point-by-point decisions because wearable samples are noisy and context dependent
- a window captures repeated evidence over time instead of overreacting to a single callback
- this is especially important for HRV, where adjacent second-level values are correlated and can otherwise create false confidence

Required properties:

- rolling historical window
- minimum eligible sample count
- freshness guard
- metric-specific eligibility
- quality/confidence state
- explicit insufficient-data state

Recommended baseline status values:

- `UNAVAILABLE`
- `INSUFFICIENT_DATA`
- `WARMING`
- `READY_LOW_CONFIDENCE`
- `READY_MEDIUM_CONFIDENCE`
- `READY_HIGH_CONFIDENCE`
- `STALE`

### Generic Algorithm

1. Ingest normalized samples into rolling history.
2. Filter to baseline-eligible samples only.
3. Reject stale or low-quality samples.
4. Compute baseline from configured estimator.
5. Publish baseline with sample count and confidence.
6. If minimum thresholds are not met, publish status but do not promote to selected dynamic output.

### Recommended Confidence Heuristic

For implementation guidance:

- `LOW`: just crossed `minSamples`
- `MEDIUM`: enough samples and acceptable recency
- `HIGH`: enough samples, stable variance, recent update

Confidence should be metric specific. HRV should require stricter quality than raw HR.

### Estimators

Preferred default choices:

- HRV RMSSD baseline: median or trimmed mean over valid nightly/resting sessions
- Resting HR baseline: EMA or trimmed mean over still, awake, on-wrist samples
- Pressure baseline: anchored session mean or recent median depending use case

Median or trimmed mean is preferred for HRV over simple mean because wearable HRV is sensitive to noise and outliers.

Design rationale:

- median is robust when a few bad readings slip through
- trimmed mean keeps more information than median while still reducing outlier influence
- simple mean is often too sensitive for physiology data collected under imperfect real-world conditions

## Trend Detection Strategy

Two trend horizons are required.

### Short-Term Trend

Purpose:

- capture acute deviation over minutes to hours
- identify "today vs recent personal normal" behavior

Inputs:

- recent normalized samples
- recent smoothed aggregate
- computed baseline

Outputs:

- `IMPROVING`
- `DECLINING`
- `STABLE`
- `UNKNOWN`

### Longer-Term Trend

Purpose:

- capture multi-day directional change
- detect gradual baseline drift or persistent suppression/elevation

Inputs:

- daily aggregates or session aggregates, not raw second-by-second data

Outputs:

- long-term direction
- sustained deviation severity

### Recommended Generic Trend Logic

- compute deviation from baseline
- compute smoothed short-term average
- compare short-term average to long-term baseline
- require persistence before classifying anomaly
- suppress state flips with hysteresis and cooldown

### Anomaly / Alert State Guidance

Suggested generic anomaly states:

- `NONE`
- `WATCH`
- `WARN`
- `ALERT`
- `UNKNOWN`

Trend/anomaly outputs are inputs to alerting, not medical claims.

## HRV-Specific Design

### Metric Definition

Primary metric for WEAR-470:

- `HRV_RMSSD_MS`

Secondary future-compatible metrics:

- `HRV_SDNN_MS`
- `HRV_QUALITY`
- `HRV_SESSION_COUNT`

Current repo payload already fits this direction:

```kotlin
data class HrvPayload(
    val rmssd_ms: Double? = null,
    val sdnn_ms: Double? = null,
    val ts: Long = System.currentTimeMillis()
) : CepPayload
```

### Source Of Truth For HRV Collection

WEAR-470 should reuse the reference Samsung path:

- `HealthTrackingService`
- `HealthTrackerType.HEART_RATE_CONTINUOUS`
- `ValueKey.HeartRateSet.HEART_RATE`
- `ValueKey.HeartRateSet.IBI_LIST`
- `ValueKey.HeartRateSet.IBI_STATUS_LIST`

This is Samsung-specific sensor integration. The baseline/trend framework must treat that as one source adapter, not as the generic framework itself.

### Platform Dependencies And Permissions

Current project/platform dependencies that inform the design:

- Samsung Health Tracking library via `app/libs/samsung-health-sensor-api-1.4.1.aar`
- Samsung `HealthTrackingService` / `HealthTracker`
- Android permissions already present in the current manifest:
  - `android.permission.BODY_SENSORS`
  - `android.permission.BODY_SENSORS_BACKGROUND`
  - `android.permission.ACTIVITY_RECOGNITION`

Design implications:

- HRV baseline learning depends on sensor access being granted and the Samsung tracker being available.
- Stillness-aware HRV eligibility depends on activity-recognition context being available.
- If Samsung Health Tracking is unavailable on a device, the generic framework should report the HRV metric as unsupported or unavailable rather than degrade other metrics.

### HRV Sample Validity Rules

A raw HRV measurement should not become a baseline sample unless all required validity criteria pass.

Design rationale:

- HRV is derived, not directly sensed
- every upstream issue in RR/IBI quality can distort RMSSD
- strict eligibility rules reduce the chance that baseline learns motion noise or off-wrist artifacts as if they were true physiology

Recommended WEAR-470 validity criteria:

- device is on wrist
- HR tracker is active
- at least `MIN_NN_FOR_RMSSD` valid NN intervals are available
- each interval passes status filtering
- each interval falls inside configured physiological bounds
- computed RMSSD is finite and greater than zero
- sample is recent

Recommended baseline-eligibility criteria for HRV:

- normalized HRV sample is valid
- sample was collected during restful context
- preferred contexts are nightly sleep or still/resting periods
- avoid high-motion or exertion windows
- avoid immediately off-wrist / stale flush artifacts

### Why HRV Baseline Must Use Valid Measurement Windows

HRV is much more context sensitive than raw HR. Arbitrary daytime samples can be dominated by movement, stress, respiration changes, and optical artifacts. Therefore WEAR-470 should baseline only from:

- nightly windows during sleep when available
- still, on-wrist, low-motion windows when sleep is unavailable
- optionally screen-off/low-interaction periods if quality gates pass

This document intentionally avoids claiming that low HRV means illness or overtraining. The design only supports deviation from personal normal plus conservative interpretation.

## End-to-End Example (HRV)

This section walks one HRV reading through the entire design in concrete terms.

1. Raw source data arrives from Samsung `HEART_RATE_CONTINUOUS`.
   Example RR/IBI values: `810 ms, 790 ms, 805 ms, 780 ms, 800 ms, 795 ms`

2. The HRV source path reads:
   - `IBI_LIST`
   - `IBI_STATUS_LIST`
   - current watch context such as on-wrist, stillness, and sleep state

3. Invalid intervals are filtered out.
   Example:
   - one interval marked invalid in `IBI_STATUS_LIST` is dropped
   - remaining valid NN intervals stay in the rolling NN buffer

4. RMSSD is computed from beat-to-beat differences.
   Example result:
   - derived `rmssd_ms = 32.4`

5. The metric adapter normalizes that into a comparable sample.
   Example normalized sample:
   - `metric = hrv.rmssd_ms`
   - `value = 32.4`
   - `quality = GOOD`
   - `baselineEligible = true`
   - context includes `on_wrist=true`, `activity.state=STILL`, `sleep.is_sleeping=false`

6. The normalized sample is stored in recent history through CEP / `WindowStore`.

7. The baseline engine evaluates whether it belongs in the rolling baseline window.
   Example:
   - the user has 6 previous qualified HRV windows this week
   - this new sample becomes the 7th qualified contribution

8. The baseline is recomputed from the eligible window, not from one sample alone.
   Example:
   - previous qualified HRV values: `36.0, 34.8, 35.5, 33.9, 35.1, 34.6`
   - with the new `32.4`, a median baseline might remain around `34.8 ms`

9. The trend engine compares the recent value/window against that personal baseline.
   Example:
   - baseline `34.8 ms`
   - recent value `32.4 ms`
   - deviation `-2.4 ms`
   - short-term direction may still be `STABLE` if this is a one-off point
   - direction becomes `DECLINING` only if lower values persist over the configured short-term window

10. The selection layer publishes the rule-facing result.
    Example:
   - `baseline.hrv.rmssd_ms = 34.8`
   - `trend.hrv.deviation_from_baseline_ms = -2.4`
   - `trend.hrv.direction = STABLE`
   - if dynamic threshold mode is enabled and confidence is sufficient, `selected.thresholds.hrv.low_rmssd_ms` is published for rules

This example is the main reason the design separates raw, normalized, baseline, and trend stages. A single `32.4 ms` HRV point is not enough to alert on by itself, but it becomes meaningful when interpreted against personal baseline and recent direction.

### HRV Adapter Proposal

Proposed adapter:

```kotlin
class HrvRmssdAdapter : BaselineMetricAdapter {
    override val metricId = "hrv.rmssd_ms"

    override fun normalize(raw: RawMetricSample): NormalizedMetricSample? {
        // Proposed wrapper around existing SkinTempService RR/IBI -> RMSSD logic
        TODO("derive RMSSD from valid NN intervals and attach quality/context")
    }
}
```

Responsibilities of the adapter:

- receive raw RR/IBI-derived measurement
- expose `rmssd_ms`
- include context such as `on_wrist`, `activity_state`, `sleep_state`, `source=samsung_hr_continuous`
- set `baselineEligible` only when context is restful enough

Reader note:

The adapter is not a second sensor pipeline. It is the translation layer that turns source-specific HRV computation into the generic metric language used by the rest of the system.

### HRV Flow Into Existing Wear OS Pipeline

Recommended path:

1. `SkinTempService` continues to own Samsung HR/IBI collection.
2. Existing NN filtering and RMSSD calculation is retained.
3. HRV output is wrapped as a normalized metric sample.
4. CEP receives `Topics.HRV` as it already does today.
5. `WindowStore` continues to ingest `hrv.rmssd_ms`.
6. Baseline/trend engine consumes `hrv.rmssd_ms` plus contextual features:
   - `activity.state`
   - `sleep.is_sleeping`
   - `offbody.on_wrist`
7. Selection layer publishes:
   - baseline value
   - baseline confidence
   - trend direction
   - selected threshold/trend state

This snippet shows the handoff point from metric output into CEP history. It matters because `WindowStore` is where recent HRV history becomes available for baseline and trend calculations.

Existing current-branch CEP ingestion already aligns with this direction:

```kotlin
is HrvPayload -> {
    val h = p.rmssd_ms
    if (h != null) {
        num.getOrPut("hrv.rmssd_ms") { TimedDoubleRing(numCap()) }.add(h, tsMs)
        fs.put("hrv.rmssd_ms", h, tsMs)
    }
}
```

### HRV Baseline Definition

Recommended initial WEAR-470 baseline:

- metric: `hrv.rmssd_ms`
- estimator: `Median` or `TrimmedMean(0.1)`
- window: rolling 7 days of eligible samples
- minimum eligible samples: start with `>= 5` sessions or `>= 20` qualified windows
- preferred aggregation unit: session/window aggregate, not every raw callback

Recommended first implementation behavior:

- store per-sample RMSSD in CEP/window store
- derive a coarser "qualified HRV window" aggregate before baseline calculation
- baseline from those qualified windows

This is preferable to baselining every 1-second emitted RMSSD because adjacent second-level samples are highly correlated and would overstate confidence.

Design rationale:

- qualified windows reduce noise
- they prevent a burst of closely spaced readings from looking like many independent observations
- they better match the real question: "what was this user's HRV during a valid resting period?"

### HRV Trend Definition

Short-term trend:

- compare recent qualified-window HRV average against personal baseline
- publish direction: improving / declining / stable / unknown

Longer-term trend:

- compare recent 3-day or 7-day aggregate against previous rolling baseline

Initial directionality guidance:

- `DECLINING`: recent HRV materially below personal baseline for a sustained period
- `IMPROVING`: recent HRV materially above recent suppressed state or above baseline
- `STABLE`: within configured band around baseline
- `UNKNOWN`: insufficient data or low confidence

Suggested first alert-oriented derived outputs:

- `baseline.hrv.rmssd_ms`
- `baseline.hrv.rmssd_ms.sample_count`
- `baseline.hrv.rmssd_ms.confidence`
- `trend.hrv.deviation_from_baseline_ms`
- `trend.hrv.percent_deviation_from_baseline`
- `trend.hrv.direction`
- `features.hrv.below_selected_low`

### HRV Threshold Interpretation

Threshold interpretation must be conservative.

- A low HRV relative to personal baseline is a contextual signal, not a diagnosis.
- The system should prefer `WATCH` or `WARN` style states before hard alerts.
- Sustained deviation and confidence should both be required.
- If baseline confidence is low, selected outputs should fall back to static or publish `UNKNOWN`.

Design rationale:

- rules consume selected outputs only so alert logic stays single-path
- this prevents duplicate rule families for static vs dynamic behavior
- it also keeps metric-specific baseline complexity out of CEP rule definitions

## Storage Strategy

### Per-User Isolation

All learned state must be namespaced per user/profile/device identity. No baseline may be shared across users.

The current `ProfileStore` proves persistence mechanics but stores only a single HR baseline key. The design should evolve it into a generic baseline store keyed by:

- `userId`
- `metricId`
- `baselineType`

Proposed storage record:

```kotlin
data class PersistedBaselineRecord(
    val userId: String,
    val metricId: String,
    val baselineValue: Double?,
    val sampleCount: Int,
    val confidence: String,
    val status: String,
    val windowStartTs: Long?,
    val windowEndTs: Long?,
    val updatedTs: Long
)
```

### Recommended Storage Split

- `WindowStore`: short-to-medium in-memory history for CEP computations
- `ProfileStore` or successor store: persisted baseline snapshots and metadata
- `DataRepository`: settings/configuration source for threshold modes and metric enablement

### Retention

Recommended retention policy:

- retain rolling in-memory raw/numeric history only as long as needed for local computations
- persist summarized baseline snapshots, not full raw RR intervals
- if future requirements need longer HRV history, persist session aggregates rather than second-level emissions

## Scheduling And Background Processing

### Generic Scheduling Model

- sample collection happens in source services
- baseline/trend computation runs inside CEP tick/evaluation flow or a scheduled computation pass
- baseline recomputation should be rate limited per metric

### HRV-Specific Scheduling

Reference behavior already includes:

- continuous HR tracking through Samsung Health Tracking
- screen-off flush loop to force callback delivery while non-interactive

This logic should be reused, not redesigned. It is already an important edge-case mitigation for missing background HR/IBI delivery.

Recommended WEAR-470 design choice:

- keep HRV capture continuous at source level
- compute baseline/trend from qualified aggregates at a lower cadence
- avoid recomputing HRV baseline on every second-level emission

Example:

- raw HRV emission cadence: about 1 second
- qualifying/aggregation cadence: 1 minute or session boundary
- baseline recomputation cadence: every 5 to 15 minutes, or when enough new qualified samples arrive

## Privacy And Data Handling

- HRV baseline data is personal physiological state and must remain per user.
- Persist only what is required for local baseline/trend behavior.
- Do not persist raw RR/IBI history longer than needed if summarized aggregates suffice.
- Do not expose learned baselines across accounts, roles, or device reassignments.
- Any user reset / reprovisioning path should clear per-user learned baseline state.

## Lifecycle And Data Retention Considerations

- On cold start, baseline should warm-start from persisted snapshots when available.
- Baseline state should age out if no qualifying data arrives for too long.
- If a user is off-wrist or sensor permissions are revoked, trend state should degrade to `UNKNOWN` rather than continue as if data were fresh.
- Reinstallation or profile reset should clear learned baselines unless product explicitly requires migration.

## Error Handling And Missing Data

Required behaviors:

- missing baseline -> publish `INSUFFICIENT_DATA` or fallback-to-static status
- stale baseline -> do not present as high confidence
- invalid HRV sample -> drop sample, do not poison baseline
- missing `IBI_STATUS_LIST` -> permit fallback only if source is known reliable and configuration allows it
- too few NN intervals -> no HRV normalized sample
- sensor disconnect / tracker failure -> trend becomes `UNKNOWN`
- off-wrist transitions -> invalidate current collection window

Recommended HRV edge cases from the reference/current implementation to preserve:

- skip invalid IBI statuses
- skip intervals outside `NN_MIN_MS..NN_MAX_MS`
- require minimum NN count for RMSSD
- keep tracker lifecycle and flush logic robust during screen-off periods

## Configuration Knobs

Each metric should have explicit configuration rather than hidden constants.

### Generic Knobs

- enable/disable metric baseline learning
- threshold mode: `STATIC`, `DYNAMIC`, `HYBRID`
- rolling window duration
- min eligible samples
- max allowed sample age
- estimator type
- confidence thresholds
- anomaly hysteresis
- cooldown period

### HRV Knobs

- `hrv.nn_min_ms`
- `hrv.nn_max_ms`
- `hrv.min_nn_for_rmssd`
- `hrv.baseline.window_days`
- `hrv.baseline.min_samples`
- `hrv.valid_context.require_on_wrist`
- `hrv.valid_context.require_still_or_sleep`
- `hrv.trend.short_window_minutes`
- `hrv.trend.long_window_days`
- `hrv.low_deviation_warn_percent`
- `hrv.low_deviation_alert_percent`

## Extensibility For Future Metrics

The same framework should support:

- resting heart rate
- HRR / exertion reserve
- SpO2
- respiratory rate
- sleep score / duration
- stress-derived metrics
- activity load
- skin temperature
- pressure-derived environmental baselines

Metric-specific differences are expected in:

- collection source
- normalization logic
- baseline eligibility criteria
- preferred estimator
- trend thresholds

Those differences belong in adapters/configuration, not in the core baseline engine.

## Testing Strategy

This document does not add tests, but the implementation should be testable at these layers.

### Unit-Level

- metric adapter normalization
- HRV NN filtering
- RMSSD computation
- baseline estimator correctness
- confidence/status transitions
- trend direction classification

### Integration-Level

- Samsung HR/IBI callbacks produce normalized HRV samples
- `Topics.HRV` enters CEP correctly
- `WindowStore` retains HRV history correctly
- persisted baseline warm-start repopulates runtime state
- threshold selection falls back correctly when baseline confidence is too low

### Simulation / CEP Scenarios

The existing Phase 7 work should be expanded from dynamic-HR-only checks into generic baseline/trend validation. Add scenarios for:

- HRV baseline warm-up with insufficient data
- HRV baseline ready state after enough qualified windows
- HRV short-term decline below personal baseline
- HRV recovery back to stable
- off-wrist / stale-data transition to `UNKNOWN`

## Rollout Plan

### Phase 1: Framework Refactor

- generalize current HR baseline plumbing into metric-agnostic baseline/trend services
- keep current HR behavior working

### Phase 2: HRV Integration

- wrap existing RR/IBI -> RMSSD path as HRV adapter
- publish HRV baseline/trend outputs

### Phase 3: Threshold Selection

- add selected threshold/trend publication for HRV
- keep rules reading selected keys

### Phase 4: Additional Metrics

- extend same pattern to HRR, resting HR, pressure, SpO2, sleep-derived metrics

## Implementation Sequence

Recommended implementation order after design approval:

1. Generalize `ProfileStore` from one-off HR keys to metric-keyed baseline snapshots.
2. Formalize normalized sample and computed baseline models using or extending `BaselineModels.kt`.
3. Add metric adapter abstraction.
4. Refactor current HR resting baseline logic in `CepEngine` to run through the generic pathway.
5. Wrap HRV sensor-side logic from `SkinTempService` into the HRV adapter contract.
6. Add HRV baseline computation using qualified resting/nightly windows.
7. Add HRV trend outputs and selected dynamic/static outputs.
8. Expand CEP simulation coverage to include HRV and generic mode-selection behavior.

## Risks And Mitigations

### Risk: HRV Baseline Built From Noisy Samples

Mitigation:

- baseline only from qualified restful/nightly windows
- use median/trimmed mean
- require minimum sample thresholds

### Risk: Over-Alerting From Wearable Noise

Mitigation:

- use smoothing and persistence requirements
- require trend confidence
- prefer `WATCH/WARN` before hard alert states

### Risk: Vendor Lock-In

Mitigation:

- isolate Samsung Health Tracking logic inside source/adapter layer
- keep generic baseline/trend interfaces vendor neutral

### Risk: False Confidence From Dense Second-Level Samples

Mitigation:

- baseline from aggregated qualified windows rather than every emitted second-level RMSSD point

### Risk: Data Staleness After Service Failure

Mitigation:

- freshness guards on trend/baseline use
- explicit `STALE` / `UNKNOWN` status

## Open Questions

- What stable per-user identity should namespace persisted baselines in `ProfileStore`? (Current implementation still uses local app profile storage namespace.)
- Should HRV baseline be built from nightly sleep windows only, or allow still/awake windows as fallback from day one?
- Is `sdnn_ms` planned for near-term use, or should WEAR-470 scope remain RMSSD only?
- Should dynamic mode without sufficient baseline always fall back to static, or should some metrics block dynamic mode activation entirely? (Current implementation falls back to existing HR static profile defaults when HR baseline is unavailable.)
- Do we want trend outputs exposed only as CEP features first, or also surfaced in UI/status cards in the same phase?
- Is long-term historical storage beyond `WindowStore` needed, or are session/daily aggregates sufficient?

## Existing Work To Keep

The following current-branch work remains useful and should not be discarded conceptually:

- reusable model contracts in `BaselineModels.kt`
- first-pass persisted HR resting baseline support in `ProfileStore`
- first-pass HR baseline learning in `CepEngine`
- first-pass trend publication for HR deviation from baseline
- existing `Topics.HRV` and `HrvPayload`
- `WindowStore` ingestion of `hrv.rmssd_ms`

These are lower-level building blocks for the generalized design rather than the final public contract.

## Final Design Summary

WEAR-419 establishes a generic per-user baseline and trend framework that separates raw collection, normalization, baseline learning, trend analysis, and selected output publishing. WEAR-470 uses that framework to integrate HRV through the already-proven Samsung RR/IBI collection path, deriving RMSSD from valid NN intervals and baselining only qualified restful/nightly measurements.

The system should remain metric agnostic, per-user isolated, and conservative in interpretation. HRV is the first concrete example, not a special-case architecture.
