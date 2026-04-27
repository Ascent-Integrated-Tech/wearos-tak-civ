# WEAR-510 — CEP ManagedConfiguration & Plugin Rule/Feature Override

**Technical Design Document (no implementation)**

| | |
|---|---|
| Ticket | WEAR-510 — *CEP toggle rule* / Ability to modify or add CEP rules and features via ManagedConfiguration |
| Author | Matt (wanjiamartin@gmail.com) |
| Status | Draft for review |
| Date | 2026-04-25 |
| Audience | WearTAK platform engineers, MDM integrators, Ascent IT product owners |
| Companion docs | `docs/CEP_Migration_Plan_And_Test_Strategy.md`, `docs/SIMULATION.md`, `docs/ongoing_changes.md` |

---

## 0. Executive summary

The current CEP engine in WearTAK is a single-source, build-time-bound system. Rules and features are loaded exactly once at process start from `R.raw.cep_rules` (`RuleCompiler.loadFromRaw`) into a `CepConfig` that is never replaced for the lifetime of the process. There is no way for an MDM, a customer-side Knox/Workspace ONE/Intune profile, or a plugin to add, replace, or disable rules at runtime — except for the narrow set of pre-baked toggles already wired through `app_restrictions.xml` (e.g. `physiological_*_enabled`, `enable_immersion_alerts`, `enable_low_atm_pressure_alert`) and the per-rule sustain overrides stored in `cep_sustained_for_sec_overrides_json`.

This document proposes:

1. A **layered configuration model** with explicit **source attribution** (`BUILT_IN`, `MANAGED_CONFIG`, `PLUGIN`, `RUNTIME`) and a deterministic merge order.
2. New **ManagedConfiguration keys** (`cep_rules_overlay_json`, `cep_features_overlay_json`, `cep_clear_rules`, `cep_clear_features`, `cep_reset_configuration`, `cep_schema_version`, `cep_merge_strategy`) added to `app_restrictions.xml` and parsed in `MainActivity.handleManagedConfigUpdate`.
3. A new **`CepConfigRegistry`** that holds layered overlays, validates them, merges them deterministically, and exposes the resulting `CepConfig` to `CepEngine`.
4. A **reload-without-restart** path on `CepEngine` so MDM-pushed overlays apply within seconds of `ACTION_APPLICATION_RESTRICTIONS_CHANGED` without forcing an app restart.
5. A first-class **plugin contract** for sensor/feature/rule contributions that does NOT require WearTAK to be rebuilt — distinct from the existing BLE companion (`CompanionAppService`), which is a *consumer* of CEP output rather than a contributor.
6. Resolutions for the open TODOs around `clearCepRules`, `clearCepFeatures`, and `resetCepConfiguration`, with explicit semantics for built-in vs. plugin-supplied entries.

No implementation work is included here. Each design decision marked with **`[OPEN-Q-n]`** still requires product/engineering sign-off before code is written.

---

## 1. Inspection of the current implementation

### 1.1 Rule and feature data model

Defined in `cep/engine/CepConfig.kt`:

* `CepConfig(tickHz: Int, features: List<FeatureDef>, rules: List<RuleDef>, ruleEvalIntervalMs: Long)`
* `FeatureDef` is a sealed interface with eight concrete shapes — `Window`, `MultiWindow`, `SlopeMulti`, `Delta`, `Derived`, `SampleWindow`, `SampleDelta`, `SampleSlope`. Every variant carries `id: String`, `updateIntervalMs: Long`, plus the topic / window / aggregator metadata it needs.
* `RuleDef(id, severity, condition: JSONObject, sustained_for_sec, exit_condition, exit_sustained_for_sec, action: RuleAction, warn?: WarnSpec, ...)`. `condition` is the raw JsonLogic-style sub-tree. There is no compiled IR; evaluation walks the JSON tree on every tick (`JsonLogic.kt`).

Both classes are **immutable Kotlin data classes**. There is no equality-by-id key, no source-tag, no enabled flag — `id` collisions are silently first-wins in the current loader.

### 1.2 Loading

`cep/engine/RuleCompiler.kt` is the only loader. It exposes:

```
fun loadFromRaw(context: Context, rawResId: Int = R.raw.cep_rules): CepConfig
fun parse(json: JSONObject): CepConfig
```

`parse` walks the document, calls private `parseFeatures(JSONArray)` and `parseRules(JSONArray)`, and per-entry `try { ... } catch (e) { Log.w(...); null }` — i.e. **a single bad feature does not nuke the whole config**, but it also does not surface the error anywhere observable.

`R.raw.cep_rules` (`app/src/main/res/raw/cep_rules.json`) is the single source of truth: schema_version `"1.0"`, a top-level `merge_instructions` block (advisory only — there is no merge code yet, only documentation aimed at future authors), `engine{ tick_hz, rule_eval_interval_ms }`, `features[…]` (≈75 entries), and `rules[…]` (≈30 entries).

### 1.3 ManagedConfiguration parsing

Wired in `presentation/MainActivity.kt`:

* Lines 1240-1251 register a `RestrictionsManager` and a `BroadcastReceiver` for `Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED`.
* `handleManagedConfigUpdate(calledOnCreate: Boolean)` (≈ lines 1572-2400) walks every key declared in `app_restrictions.xml`, reading `appRestrictions.getXxx(key, default)` and calling repository setters.
* The pattern that matters most for CEP is `custom_developer_config_json` (string defaultValue `"{}"`). When changed, the value is persisted via `dataRepository.storeCustomDeveloperConfigJson(...)` and an `EventBus` message `MessageEvent(CUSTOM_DEVELOPER_CONFIG_JSON_CHANGED, 0)` is posted. **No subscriber currently consumes that bus event** in the CEP pipeline; this is the natural insertion point for our new overlay keys (Section 2.6).
* `app_restrictions.xml` (613 lines) declares **no CEP keys today** beyond per-physio toggles (`physiological_heart_rate_enabled`, `physiological_pulse_ox_enabled`, …) and `cep_sustained_for_sec_overrides_json` (a string-shaped DataStore key, not yet exposed in `app_restrictions.xml`). The `custom_developer_config_json` key is the only *generic* JSON tunnel.

### 1.4 Plugin/companion interaction

There is no sensor-plugin SPI today. What the codebase calls the "companion" is `service/CompanionAppService.kt` + `service/CompanionAppRepository.kt`: a BLE GATT server hosted on the watch (UUIDs `0xA11A` service, `0xA11B` TX, `0xA11C` RX) that exchanges JSON envelopes with a paired phone for CoT/TAK messaging. It is enabled via the `enable_companion_plugin` ManagedConfig key and consumes alert state — it does not contribute rules or features.

The `Topics`/`TopicRegistry` pair is **closed**: 17 hardcoded topic strings (`HEART_RATE`, `HRV`, `SKIN_TEMP`, `AMBIENT`, `WIND`, `ACCEL`, `GYRO`, `ACTIVITY`, `SPO2`, `PPG`, `IMMERSION`, `HUMIDITY`, `WATER`, `LIGHT`, `SLEEP`, `OFFBODY`, `PRESSURE`) and a static `Map<String, KClass<out CepPayload>>`. Any plugin-provided sensor would have to register a new topic/payload mapping; this is a new SPI surface.

`CepActionDispatcher.register(h: ActionHandler)` already supports pluggable *output* sinks (each handler receives every dispatched action subject to severity throttle: INFO 0s, WARN 5s, ALERT 10s, EMERGENCY 15s), so the action-side plugin contract is largely solved — what is missing is the *input* (sensor) and *config* (rule/feature) sides.

### 1.5 Persistence

`data/DataRepository.kt` (DataStore Preferences) holds the CEP-relevant state today:

* `CEP_SUSTAINED_FOR_SEC_OVERRIDES_JSON` (constant; underlying DataStore string key `cep_sustain_overrides_json`) — read by `getCepSustainOverridesOnce()`, supports both legacy flat `{ruleId: secs}` and the dual-keyed `{main:{}, warn:{}}` shape (see `parseCepSustainOverrides`).
* `custom_developer_config_json` (string) — generic JSON tunnel, read on every ManagedConfig change.
* Per-physio enabled flags (one DataStore Boolean each).

The CEP engine itself is **not** persisted. `FeatureStore`, `WindowStore`, `RingStore`, `AlertRegistry`, `TriggerTimer` state is in-process only and is rebuilt each cold start from the static `R.raw.cep_rules`.

### 1.6 Lifecycle

`WearTakApplication.onCreate()` calls `CepProvider.init(this)` and `CepProvider.getOrCreateEngine(this).start()`. `CepProvider` (`cep/di/CepProvider.kt`) is a singleton DI shim: `getOrCreateEngine` constructs the `CepEngine` from `RuleCompiler.loadFromRaw(...)` exactly once. **There is no `reload()`, `replaceConfig(...)`, or `restart()` method on `CepEngine` today.** Every per-feature coroutine launched in `CepEngine.start()` runs for the lifetime of the process.

### 1.7 Summary of what blocks WEAR-510

1. Single static config source (`R.raw.cep_rules`). **No overlay loader.**
2. Engine has no reload API; coroutines and feature stores are bound to the original `CepConfig` instance.
3. `RuleDef` / `FeatureDef` carry no source attribution and no enabled flag.
4. ManagedConfig parser has no CEP-specific branch and no schema version handshake.
5. `Topics` is closed; plugin-contributed topics cannot register today.
6. No conflict-resolution policy when the same `id` appears twice.
7. No persisted snapshot — every restart rebuilds from `R.raw.cep_rules`, which means MDM-pushed overlays would have to be re-applied each cold start.

---

## 2. ManagedConfiguration support for CEP

### 2.1 Key catalogue (additions to `app_restrictions.xml`)

| Key | Type | Default | Purpose |
|---|---|---|---|
| `cep_schema_version` | string | `"1.0"` | Asserted by MDM; mismatch triggers fallback (Section 2.5) |
| `cep_merge_strategy` | choice (`replace` / `merge` / `patch`) | `merge` | Controls how `cep_rules_overlay_json` and `cep_features_overlay_json` combine with built-in (Section 2.4) |
| `cep_rules_overlay_json` | string (JSON) | `""` | Array of `RuleDef` JSON objects; empty string = no overlay |
| `cep_features_overlay_json` | string (JSON) | `""` | Array of `FeatureDef` JSON objects |
| `cep_disabled_rule_ids` | string (CSV) or `bundle_array` | `""` | Hard-disable specific rule ids without redefining them |
| `cep_disabled_feature_ids` | string (CSV) or `bundle_array` | `""` | Hard-disable specific feature ids |
| `cep_clear_rules` | choice (`none` / `plugin_only` / `managed_only` / `non_builtin` / `all_except_builtin` / `everything`) | `none` | One-shot clear directive (see Section 3) |
| `cep_clear_features` | choice (same as above) | `none` | Same, for features |
| `cep_reset_configuration` | bool | `false` | Master reset to built-in only; takes precedence over the two `clear_*` keys |
| `cep_overlay_signature` | string (base64) | `""` | Optional Ed25519 signature over the concatenated `cep_*_json` strings; verified against a build-time public key (see Section 2.5) |

The existing key `cep_sustained_for_sec_overrides_json` keeps its current semantics (per-rule sustain timer overrides) and is folded into the `RUNTIME` layer rather than the `MANAGED_CONFIG` layer — see Section 2.3.

### 2.2 Schema (overlay JSON shape)

Both `cep_rules_overlay_json` and `cep_features_overlay_json` use the **same syntax** as `cep_rules.json` so that MDM admins can copy/paste from the canonical file. Top-level shape:

```json
{
  "schema_version": "1.0",
  "merge_strategy": "merge",                       // optional, overrides cep_merge_strategy if present
  "engine": { "tick_hz": 1, "rule_eval_interval_ms": 1000 }, // optional, MANAGED_CONFIG layer only
  "features": [ /* FeatureDef JSON, same as built-in */ ],
  "rules": [ /* RuleDef JSON, same as built-in */ ],
  "disabled_rule_ids": ["rule_hr_high_v1"],         // optional convenience, equivalent to cep_disabled_rule_ids
  "disabled_feature_ids": ["feat_hr_window_30s"],   // optional convenience
  "metadata": { "source": "mdm", "issued_at": "2026-04-20T12:00:00Z", "tenant": "acme-corp" }
}
```

Two-key split (`cep_rules_overlay_json` vs. `cep_features_overlay_json`) exists because Android `RestrictionsManager` enforces a **per-key size budget** (~32 KB string in practice on Knox/AE); large rule packs blow that budget if combined. MDM admins MAY use either key with the unified shape above, or split features and rules across the two keys. The parser merges them before validation.

### 2.3 Layer model and merge order

There are four layers, applied **bottom-up** (later layers override earlier ones for the same `id`):

```
[BUILT_IN]    — R.raw.cep_rules (always present)
[MANAGED_CONFIG] — cep_rules_overlay_json + cep_features_overlay_json + disabled_*
[PLUGIN]      — contributions from installed plugins (Section 4)
[RUNTIME]     — cep_sustained_for_sec_overrides_json + per-feature toggles (physiological_*_enabled)
```

**Why this order?** ManagedConfig represents enterprise policy and must beat factory defaults; plugins represent device-specific extensions that should be allowed to add (but not silently override) MDM policy — hence we recommend that `PLUGIN` cannot replace a `MANAGED_CONFIG` id by default (see `[OPEN-Q-3]`). `RUNTIME` is the user/operator's own per-rule timing tweak and is intentionally narrow (sustain only); it never adds or removes rules.

### 2.4 Merge strategies

For a layer L overlaying the lower-precedence merged config M, with same-id collision on entry id `X`:

| Strategy | Behavior |
|---|---|
| `replace` | The entire output (`features`+`rules`) is L's. M is discarded. Used when an MDM wants a hermetic policy package. |
| `merge` (default) | Union by id. On collision, L's entry wins entirely (no field-level deep merge). M's entries with no L counterpart pass through unchanged. |
| `patch` | Field-level deep merge per id. L provides only the changed fields; missing fields fall back to M. Mutually exclusive with structural changes (e.g. you cannot change a `Window` feature to a `MultiWindow` via patch — that requires `merge` or `replace`). |

`patch` is the trickiest and is the most likely to surface subtle bugs; it is recommended but not required for v1 — see `[OPEN-Q-1]`.

### 2.5 Versioning, validation, and error handling

* **Schema version handshake.** If `cep_schema_version` is present and ≠ the loader's supported version (currently `"1.0"`), the entire MANAGED_CONFIG layer is **rejected** and the fallback is BUILT_IN-only. A `MessageEvent(CEP_OVERLAY_REJECTED, reason)` is posted and surfaced in the diagnostics screen. This prevents an MDM that ships a `2.0` overlay from corrupting a watch still on `1.0`.
* **Per-entry validation.** Reuse the existing `try/catch` around each entry in `RuleCompiler.parse*`, but in the registry path, escalate failures into a per-id `OverlayDiagnostic(id, sourceLayer, reason)` collection that is exposed via `CepDiagnosticsRepository` (new) for in-app inspection and CoT diagnostic reporting.
* **Whole-document validation.** Before merging, the registry runs cross-cutting checks: every rule's referenced feature id must resolve in the merged feature set after merge; severity must be one of `INFO|WARN|ALERT|EMERGENCY`; numeric fields must be finite; window sizes must be positive. Failures cause that *entry* to be dropped (not the whole layer) and a diagnostic is recorded.
* **Signature.** Optional `cep_overlay_signature` is verified against a build-time-embedded Ed25519 public key. If the key is present in the build but the signature is missing or invalid, the overlay is rejected. If the build has no public key embedded (community/dev builds), the signature is ignored. This lets enterprise builds enforce signed overlays without breaking dev workflows. — `[OPEN-Q-4]` covers the key-distribution lifecycle.
* **Backward compatibility.** Any pre-WEAR-510 build ignores the new keys (Android `RestrictionsManager` returns the default for unknown keys). New builds with empty/missing overlay keys behave exactly like today (BUILT_IN only). The legacy `cep_sustained_for_sec_overrides_json` is preserved verbatim and continues to feed `CepEngine.updateSustainOverrides(...)`.

### 2.6 Trigger flow

```
RestrictionsManager → ACTION_APPLICATION_RESTRICTIONS_CHANGED
   → MainActivity.handleManagedConfigUpdate(false)
       → repo.storeCepRulesOverlayJson(...)            (new)
       → repo.storeCepFeaturesOverlayJson(...)         (new)
       → repo.storeCepDisabledRuleIds(...)             (new)
       → repo.storeCepDisabledFeatureIds(...)          (new)
       → repo.storeCepClearDirectives(...)             (new)
       → EventBus.post(MessageEvent(CEP_OVERLAY_CHANGED, 0))   (new)
   → CepEngine subscribes to CEP_OVERLAY_CHANGED
       → CepConfigRegistry.recompose()
           → re-parse MANAGED_CONFIG layer
           → re-merge all four layers
           → produce new CepConfig
       → CepEngine.applyConfig(newConfig)              (new)
           → see Section 5 for the runtime semantics
```

The existing `custom_developer_config_json` path is a viable **alternative** transport if MDM admins prefer a single-blob developer-config tunnel; the new dedicated keys are preferable because (a) they have explicit defaults and (b) Android Enterprise UEMs render them as discrete fields rather than a free-form blob.

---

## 3. Resolutions for the `clear*` / `reset*` TODOs

The codebase carries TODO-shaped placeholders for `clearCepRules`, `clearCepFeatures`, and `resetCepConfiguration`. Their semantics need to be made explicit because plugin-contributed and built-in entries should not be treated alike.

### 3.1 Definitions

* `BUILT_IN` — entries that came from `R.raw.cep_rules`. They are the warranted defaults that ship with the app version and **cannot be deleted** in v1; they can only be **disabled** via `cep_disabled_rule_ids` / `cep_disabled_feature_ids` or **replaced** by an overlay.
* `MANAGED_CONFIG` — entries originating from MDM overlays.
* `PLUGIN` — entries contributed by an installed plugin (Section 4).
* `RUNTIME` — runtime-injected entries via `CepEngine.injectFeatureForTest(...)` style hooks (test-only) or future user-initiated tuning UI.

### 3.2 `cep_clear_rules` / `cep_clear_features` enum semantics

| Value | What it does |
|---|---|
| `none` | No clearing. Default. |
| `plugin_only` | Drop all `PLUGIN` rules/features. Plugin can re-contribute on next handshake. |
| `managed_only` | Drop all `MANAGED_CONFIG` rules/features. Equivalent to MDM pushing empty overlays. |
| `non_builtin` | Drop both `MANAGED_CONFIG` and `PLUGIN`. Engine returns to BUILT_IN ∪ RUNTIME. |
| `all_except_builtin` | Same as `non_builtin` (alias) — kept for naming clarity. |
| `everything` | Drops ManagedConfig, Plugin, and Runtime; **also resets the `cep_disabled_*_ids` lists to empty**, so any built-ins that were disabled come back. **Does not delete built-ins.** |

**Design decision.** Built-ins are *never* removable through these directives. The reasoning: the cep_rules.json file ships with the APK and represents the safety-critical baseline (cold stress, brady, hyperthermia, dunk). MDM admins who don't want a built-in rule firing should disable it via `cep_disabled_rule_ids` rather than delete it — which leaves the rule visible in diagnostics with an explicit "disabled by MDM" tag. — `[OPEN-Q-2]`

### 3.3 `cep_reset_configuration: true`

This is a master switch — it implies `cep_clear_rules: everything` AND `cep_clear_features: everything` AND clears `cep_sustained_for_sec_overrides_json`. After a reset the engine state is bit-identical to a freshly-installed APK with empty MDM. The next ManagedConfig push (which may arrive in the same RestrictionsManager broadcast cycle) is then layered on top.

**Implementation note.** `cep_reset_configuration` is a one-shot: `MainActivity` reads it, applies it, and immediately writes `false` back into the DataRepository's mirror copy. The MDM key remains `true` in the profile until the admin clears it, but the engine has already consumed it. This avoids reset-loops on every restart.

### 3.4 Source-attribution required on `RuleDef` / `FeatureDef`

For `clear_*` directives to discriminate, every `RuleDef` and `FeatureDef` needs a `source: RuleSource` field added (in v1 of WEAR-510). Proposed:

```kotlin
enum class RuleSource { BUILT_IN, MANAGED_CONFIG, PLUGIN, RUNTIME }
```

This is the **only** schema change required to current `RuleDef`/`FeatureDef` data classes. It defaults to `BUILT_IN` so existing JSON does not need editing. The registry stamps it on parse based on which layer the entry came from.

---

## 4. Plugin interaction design

### 4.1 What "plugin" means in v1

A plugin is **another Android package**, installed alongside WearTAK (typically by the same MDM), that contributes one or more of:

1. Sensor topic streams (raw events into `CepBus`).
2. Feature definitions (additional aggregations / derived signals).
3. Rule definitions (additional alerting logic).
4. Action handlers (downstream sinks) — already supported via `CepActionDispatcher.register`.

Plugins are **not** dynamically-loaded JARs — that is forbidden on Wear OS by Play policy and is a security minefield. Instead each plugin is a separately-signed APK that talks to WearTAK over an Android IPC surface (see 4.3).

The existing `CompanionAppService` (BLE GATT to phone) is **not** a plugin in this sense; it is a downstream consumer. v1 keeps it untouched.

### 4.2 What plugins can and cannot contribute

| Contribution | Allowed in v1 | Notes |
|---|---|---|
| New sensor topic + payload class | Yes | Plugin declares topic schema in its manifest metadata; WearTAK extends `TopicRegistry` via a new `TopicRegistryExtensions` map (Section 7) |
| New `FeatureDef` referring to a new or built-in topic | Yes | Subject to id-uniqueness / merge-strategy rules |
| New `RuleDef` referring to any feature | Yes | Subject to merge-strategy rules |
| Replace a `BUILT_IN` rule | No (default) | Disallowed unless MDM explicitly grants `cep_plugins_can_override_builtin: true` — `[OPEN-Q-3]` |
| Replace a `MANAGED_CONFIG` rule | No | Always disallowed; MDM policy is sovereign over plugins |
| Register an `ActionHandler` | Yes | Same surface as today |
| Mutate `engine.tick_hz` or `rule_eval_interval_ms` | No | Engine cadence is owned by BUILT_IN ± MANAGED_CONFIG only |

### 4.3 IPC surface

Three options were considered. Recommended: **(B) Bound `Service` + AIDL**. A short comparison so the rationale is captured:

* **(A) `BroadcastReceiver` + `Intent` payload**
  * Pros: Loose coupling, no service binding lifecycle, easy to test.
  * Cons: 1MB intent payload limit, no per-call response, no streaming events. Topic data is high-frequency (≥1Hz HR, accel/gyro can exceed 50Hz) — broadcasts are wrong.
* **(B) Bound `Service` + AIDL** ← **recommended**
  * Pros: Low-latency two-way calls, supports streaming via callback interface, integrates with `RemoteCallbackList` for binder-death-aware unregistration. Matches Wear OS battery model when bound from a foreground service.
  * Cons: Schema lock-in via AIDL — every change requires plugin SDK update.
* **(C) `ContentProvider` URIs**
  * Pros: Uses Android's permission model.
  * Cons: Pull model — cannot push events. Plugins have to poll. Wrong primitive.

Proposed AIDL surface (high-level — actual definitions are out of scope for this design):

```
ICepPluginService (exported by WearTAK; plugins bind to this):
  ContributionResult registerPlugin(PluginManifest)
  void unregisterPlugin(String pluginId)
  void emitSensorEvent(String pluginId, String topic, byte[] payloadProto, long tsMs)
  ContributionResult contributeOverlay(String pluginId, String overlayJson)
  void registerActionListener(String pluginId, ICepActionCallback cb)

ICepActionCallback (exported by plugin; WearTAK calls it):
  void onAction(CepActionParcelable action)
  void onPluginInvalidated(String reason)
```

`PluginManifest` carries: plugin id, version, declared topics + payload schemas, declared feature/rule ids, signature digest. WearTAK validates the manifest before accepting any subsequent calls.

### 4.4 Conflict resolution and priority

For a single id `X`, the deterministic resolution is:

```
disabled_*_ids match ? → entry is dropped (regardless of layer)
else last-non-empty-wins(BUILT_IN < MANAGED_CONFIG < PLUGIN < RUNTIME)
   subject to:
     PLUGIN may NOT override MANAGED_CONFIG          (always)
     PLUGIN may NOT override BUILT_IN                (default; opt-in via cep_plugins_can_override_builtin)
     RUNTIME (sustain overrides only) applies on top of whatever rule survived
```

If two plugins both contribute id `X`, the **first** plugin to register wins; the second is logged as a diagnostic and rejected. This avoids non-determinism from install order races. `[OPEN-Q-5]` covers whether to expose a `pluginPriority` field instead.

### 4.5 Sensor metadata vs. feature defs vs. rule defs (separation of concerns)

A common mistake is to let plugins ship "fused" bundles where one entity covers all three roles. Keeping these separate keeps the merge model tractable:

* **Sensor topic** = raw event channel + payload class. Plugin **must** declare these to use any of the others. Ids are namespaced (`com.acme.spo2_v2`).
* **Feature def** = a `FeatureDef` that aggregates one or more topic streams. Plugin **must** declare these if it ships rules that reference plugin-provided topics.
* **Rule def** = a `RuleDef` that fires actions. May reference any feature id (built-in or plugin-provided) but must specify it in the `metadata.required_features` array of its manifest so the registry can pre-validate before it accepts the contribution.

---

## 5. Runtime behavior

### 5.1 Reload semantics

When `CepConfigRegistry.recompose()` produces a new `CepConfig`, the engine's `applyConfig(newConfig)` method (new) performs the following:

1. **Diff** the new config against the current. Categorize each id as `ADDED`, `REMOVED`, `REPLACED`, or `UNCHANGED`. Hash inputs are: feature/rule id, full JSON of the entry post-merge, source layer.
2. For **`UNCHANGED`** entries, do nothing. Their per-feature coroutines, ring buffers, and trigger timers continue uninterrupted.
3. For **`REMOVED`** entries, cancel the per-feature coroutine, drop their state from `FeatureStore` / `WindowStore`, and clear any active alerts they own via `AlertRegistry.clearByUid(...)`.
4. For **`ADDED`** entries, launch new per-feature coroutines and prime ring buffers from any in-memory recent samples (best-effort — a freshly-added 5-minute window will need 5 minutes to stabilize). Rules become eligible on the next rule-eval tick.
5. For **`REPLACED`** entries, treat as `REMOVED` then `ADDED` **unless** the only delta is in `sustained_for_sec` / `exit_sustained_for_sec` (the existing soft-update path) — in which case call `CepEngine.updateSustainOverrides(...)` and leave state alone.
6. After diff is applied, atomically swap the registry's exposed `CepConfig` and post `MessageEvent(CEP_CONFIG_APPLIED, addedCount + removedCount + replacedCount)`.

### 5.2 Threading

* The recompose+apply pipeline runs on a single dedicated coroutine `Dispatchers.Default.limitedParallelism(1)` to serialize multiple back-to-back ManagedConfig pushes.
* `applyConfig` holds a `Mutex` shared with `CepEngine.processTick` so a tick cannot read a half-mutated rule list. Tick latency budget is preserved because the mutex is only held while pointers are swapped — the diff is computed outside the lock.
* Per-feature coroutines are children of a `SupervisorJob` so a misbehaving plugin feature cannot kill the engine.

### 5.3 Rollback and safe-mode

If `applyConfig` throws **any** exception during diff/swap, the engine reverts to the previous `CepConfig` (held via `AtomicReference<CepConfig>`) and posts `MessageEvent(CEP_OVERLAY_REJECTED, reason)`. The exception is logged with the offending entry id. The next ManagedConfig push will retry.

If `applyConfig` succeeds but a per-feature coroutine then crashes within 5 seconds of being launched **for the third consecutive recompose**, the engine enters "safe mode" — it forcibly clears all `MANAGED_CONFIG` and `PLUGIN` layers and runs BUILT_IN-only until the next reboot. This guards against an MDM pushing a poison-pill overlay that an attacker could exploit to silence alerts. — `[OPEN-Q-6]`

### 5.4 Observability

A new `CepDiagnosticsRepository` exposes:

* Current source-attributed config: list of `(id, sourceLayer, enabled)` for features and rules.
* Last 100 overlay events: `(timestampMs, sourceLayer, action, reason)`.
* Drop-counts per layer per recompose cycle.
* Active alert ids with originating rule id and source layer.

This repo is bound into the Settings/Diagnostics screen and to a CoT diagnostic message that can be requested by an operator on the connected phone.

---

## 6. Persistence

### 6.1 What gets persisted

| Data | Where | Rationale |
|---|---|---|
| Last-applied MANAGED_CONFIG overlay JSON | DataStore key `cep_managed_overlay_json` | Required so the engine can re-apply MDM policy on next cold start without waiting for the RestrictionsManager broadcast (which can lag the foreground service start by 1-3 seconds) |
| Plugin contribution snapshots | DataStore key `cep_plugin_overlays_json` (object keyed by plugin id) | Same — re-apply on cold start, then validate against currently-installed plugins on first plugin handshake |
| `cep_disabled_rule_ids`, `cep_disabled_feature_ids` | DataStore keys, mirrored from MDM | Survives reboot; cleared by `cep_reset_configuration` |
| Source attribution per id | Recomputed at registry build time | Not persisted — derived from the layer JSONs |
| Diagnostic event ring | In-memory only, optionally backed by a circular file in `cep_diagnostics_log` | Useful for triage, low priority for v1 |

The registry's resolved `CepConfig` is **not** persisted. It is always reconstructed from the layer JSONs (BUILT_IN comes from `R.raw.cep_rules`, the rest from DataStore). This keeps a single source of truth and avoids stale-cache bugs.

### 6.2 Cold-start sequence

```
WearTakApplication.onCreate()
  → CepProvider.init(ctx)
  → CepConfigRegistry.bootstrap(ctx):
       1. Parse R.raw.cep_rules → BUILT_IN layer
       2. Read DataStore cep_managed_overlay_json → MANAGED_CONFIG layer (may be empty)
       3. Read DataStore cep_plugin_overlays_json → PLUGIN layer (may be empty)
       4. Read DataStore cep_sustained_for_sec_overrides_json + per-physio toggles → RUNTIME layer
       5. Validate, merge, store as registry.current
  → CepEngine constructed with registry.current
  → CepEngine.start()
```

When `MainActivity` later receives `ACTION_APPLICATION_RESTRICTIONS_CHANGED`, the engine is already running with the last-known overlay. The recompose is then a no-op or a small diff, not a full restart.

### 6.3 Privacy and PII

Overlay JSONs may contain rule names that reference biometric thresholds. They do not contain PII. We continue to store them in DataStore without encryption (consistent with current `cep_sustained_for_sec_overrides_json`). If the build requires at-rest encryption, the existing EncryptedSharedPreferences pattern can be applied uniformly. — `[OPEN-Q-7]`

---

## 7. Proposed architecture

### 7.1 New classes (no implementation yet)

| Class / File | Package | Responsibility |
|---|---|---|
| `RuleSource` (enum) | `cep.engine` | `BUILT_IN`, `MANAGED_CONFIG`, `PLUGIN`, `RUNTIME` |
| `CepLayerInputs` (data class) | `cep.engine.config` | Bundles raw inputs: builtinJson, managedJson, pluginJsons, runtimeOverridesJson, disabledIds |
| `CepConfigRegistry` | `cep.engine.config` | Owns `AtomicReference<CepConfig>`, `recompose()`, `bootstrap()`, `applyMerged(...)` |
| `CepConfigMerger` | `cep.engine.config` | Pure function: `(layers, strategy) → MergedConfigResult(config, diagnostics)` |
| `ManagedCepOverlayParser` | `cep.engine.config` | Wraps `RuleCompiler.parse` but tags every entry with `MANAGED_CONFIG` and validates schema_version |
| `PluginCepOverlayParser` | `cep.engine.config` | Same idea, tags entries with `PLUGIN`, namespaces ids by plugin id when configured |
| `CepDisabledIdSet` | `cep.engine.config` | Set<String> for rules + Set<String> for features, derived from MDM and merged across plugins |
| `CepDiagnosticsRepository` | `data` | Exposes flows of overlay events, drops, source attribution |
| `CepPluginGateway` | `cep.plugin` | Hosts the bound `Service` + AIDL exposed to plugins |
| `PluginManifest` (parcelable) | `cep.plugin` | Plugin handshake metadata |
| `TopicRegistryExtensions` | `cep.model` | Plugin-contributed topic + payload class registrations |
| `MessageEvent` constants | `events` | New: `CEP_OVERLAY_CHANGED`, `CEP_OVERLAY_REJECTED`, `CEP_CONFIG_APPLIED` |
| `app_restrictions.xml` keys | `res/xml` | New ManagedConfig keys (Section 2.1) |
| `DataRepository` keys | `data` | New DataStore keys for overlay caches and disabled-id mirrors |

### 7.2 Modified classes (minimal surface)

| Class / File | Change |
|---|---|
| `CepConfig.kt` | Add `source: RuleSource` to `FeatureDef` (every variant) and `RuleDef`. Default `BUILT_IN` for backward compat. |
| `RuleCompiler.kt` | No behavior change. `parse(...)` becomes a pure parser; wrapping with source attribution moves to the new parsers. |
| `CepEngine.kt` | Add `applyConfig(new: CepConfig)`. Add subscription to `CEP_OVERLAY_CHANGED`. Per-feature coroutines registered in a map keyed by feature id so they can be individually canceled. Existing `updateSustainOverrides` stays as the soft-update path. |
| `CepProvider.kt` | `getOrCreateEngine(ctx)` now also returns a registry-bound engine; expose `getRegistry()`. |
| `MainActivity.handleManagedConfigUpdate` | New branch: read the new keys, persist to DataStore, post `CEP_OVERLAY_CHANGED`. Keep all existing branches untouched. |
| `Topics.kt` / `TopicRegistry.kt` | Add `register(topic, payloadClass)` + `unregister(topic)` for plugin contributions. Built-in entries stay hardcoded. |

### 7.3 Data flow diagram

```
                   ┌──────────────────────┐
                   │ R.raw.cep_rules.json │  (BUILT_IN)
                   └──────────┬───────────┘
                              │
   MDM ──► RestrictionsManager ──► MainActivity.handleManagedConfigUpdate
                              │             │
                              │             ▼
                              │     DataRepository
                              │             │
                              ▼             ▼
   Plugin APK ──► CepPluginGateway ──► CepConfigRegistry ◄── per-physio toggles
                                       │  bootstrap()
                                       │  recompose()
                                       │  ┌──────────────────┐
                                       │  │ CepConfigMerger  │
                                       │  └──────────────────┘
                                       ▼
                                  CepConfig
                                       │
                                       ▼
                                  CepEngine.applyConfig
                                       │
                              ┌────────┴────────┐
                              ▼                 ▼
                        FeatureStore       RuleEngine
                        WindowStore        (uses JsonLogic)
                        TimedRing          TriggerTimer
                              │                 │
                              ▼                 ▼
                                CepActionDispatcher
                                       │
                              ┌────────┴────────┐
                              ▼                 ▼
                       AlertRegistry    Plugin ActionHandlers
```

### 7.4 Package layout

```
cep/
├── bus/
│   └── CepBus.kt                  (unchanged)
├── di/
│   └── CepProvider.kt             (modified)
├── engine/
│   ├── CepConfig.kt               (modified — add RuleSource)
│   ├── CepEngine.kt               (modified — add applyConfig)
│   ├── RuleEngine.kt              (unchanged)
│   ├── RuleCompiler.kt            (unchanged)
│   ├── FeatureStore.kt            (unchanged)
│   ├── WindowStore.kt             (unchanged)
│   ├── JsonLogic.kt               (unchanged)
│   ├── ProfileStore.kt            (unchanged)
│   ├── action/                    (unchanged)
│   └── config/                    (NEW)
│       ├── CepConfigRegistry.kt
│       ├── CepConfigMerger.kt
│       ├── CepLayerInputs.kt
│       ├── CepDisabledIdSet.kt
│       ├── ManagedCepOverlayParser.kt
│       └── PluginCepOverlayParser.kt
├── model/
│   ├── Topics.kt                  (modified — opens registry)
│   └── TopicRegistry.kt           (modified — add extensions table)
└── plugin/                         (NEW)
    ├── CepPluginGateway.kt
    ├── PluginManifest.kt
    └── aidl/                      (AIDL definitions)
```

---

## 8. Deliverables — assembled view

This section binds together the deliverables called out in the WEAR-510 ask. Most have been described above; this section provides the concrete examples and edge-case checklist.

### 8.1 High-level architecture

See Section 7.3.

### 8.2 Class/interface design

See Section 7.1 for the new classes. The interfaces with the most external surface area are:

```kotlin
interface CepConfigSource {        // implemented by registry
    val current: StateFlow<CepConfig>
    val diagnostics: StateFlow<List<OverlayDiagnostic>>
}

interface CepConfigRegistry : CepConfigSource {
    fun bootstrap()
    fun recompose(reason: String): RecomposeResult     // called from MainActivity / plugin gateway
    fun snapshot(): CepLayerInputs                     // for diagnostics export
}

interface CepEngineLifecycle {     // implemented by CepEngine
    fun start()
    fun applyConfig(newConfig: CepConfig): ApplyResult
    fun stop()
}
```

### 8.3 ManagedConfiguration JSON / XML examples

**`app_restrictions.xml` additions** (illustrative XML — final names per Section 2.1):

```xml
<restriction
    android:key="cep_schema_version"
    android:title="@string/restrict_cep_schema_version_title"
    android:restrictionType="string"
    android:defaultValue="1.0" />

<restriction
    android:key="cep_merge_strategy"
    android:title="@string/restrict_cep_merge_strategy_title"
    android:restrictionType="choice"
    android:entries="@array/cep_merge_strategy_entries"
    android:entryValues="@array/cep_merge_strategy_values"
    android:defaultValue="merge" />

<restriction
    android:key="cep_rules_overlay_json"
    android:title="@string/restrict_cep_rules_overlay_title"
    android:description="@string/restrict_cep_rules_overlay_desc"
    android:restrictionType="string"
    android:defaultValue="" />

<restriction
    android:key="cep_features_overlay_json"
    android:title="@string/restrict_cep_features_overlay_title"
    android:restrictionType="string"
    android:defaultValue="" />

<restriction
    android:key="cep_disabled_rule_ids"
    android:title="@string/restrict_cep_disabled_rule_ids_title"
    android:restrictionType="string"
    android:defaultValue="" />

<restriction
    android:key="cep_disabled_feature_ids"
    android:restrictionType="string"
    android:defaultValue="" />

<restriction
    android:key="cep_clear_rules"
    android:restrictionType="choice"
    android:entries="@array/cep_clear_entries"
    android:entryValues="@array/cep_clear_values"
    android:defaultValue="none" />

<restriction
    android:key="cep_clear_features"
    android:restrictionType="choice"
    android:defaultValue="none" />

<restriction
    android:key="cep_reset_configuration"
    android:restrictionType="bool"
    android:defaultValue="false" />

<restriction
    android:key="cep_overlay_signature"
    android:restrictionType="string"
    android:defaultValue="" />
```

**Sample MDM-pushed overlay** (would be set as the `cep_rules_overlay_json` and `cep_features_overlay_json` strings):

```json
{
  "schema_version": "1.0",
  "merge_strategy": "merge",
  "metadata": {
    "tenant": "acme-corp",
    "issued_at": "2026-04-25T10:30:00Z",
    "issuer": "policy-bot@acme"
  },
  "features": [
    {
      "id": "feat_hr_window_60s_acme",
      "type": "window",
      "topic": "HEART_RATE",
      "field": "bpm",
      "window_sec": 60,
      "agg": "mean",
      "update_interval_ms": 1000
    }
  ],
  "rules": [
    {
      "id": "rule_hr_high_acme_v2",
      "severity": "ALERT",
      "sustained_for_sec": 30,
      "exit_sustained_for_sec": 60,
      "condition": {
        "and": [
          { "ref": "features.feat_hr_window_60s_acme", "is": { "gte_key_fresh": [180.0, 5] } }
        ]
      },
      "exit_condition": {
        "and": [
          { "ref": "features.feat_hr_window_60s_acme", "is": { "lt_key": 170.0 } }
        ]
      },
      "action": {
        "category": "CAT_PHYSIOLOGICAL_ALERT",
        "title": "Heart Rate High (Acme policy)",
        "description": "Sustained HR above 180 bpm for 30s"
      }
    }
  ],
  "disabled_rule_ids": [
    "rule_hr_high_v1"
  ]
}
```

**Sample reset** (admin pushes this to bring a watch back to factory CEP):

```
cep_reset_configuration = true
cep_rules_overlay_json = ""
cep_features_overlay_json = ""
cep_disabled_rule_ids = ""
cep_disabled_feature_ids = ""
```

### 8.4 Plugin integration contract

The contract is the AIDL surface in Section 4.3 plus the `PluginManifest`. The contract guarantees (in v1):

* WearTAK publishes its supported AIDL version. Plugins built against an older minor version continue to work.
* WearTAK is the sole arbiter of merge strategy. Plugins cannot push an `engine` block.
* WearTAK rejects any contribution whose ids collide with `BUILT_IN` or `MANAGED_CONFIG` (unless the override flag is set per Section 4.2).
* Plugins receive a callback if their contributions are subsequently invalidated (e.g. MDM pushed a `cep_clear_rules: plugin_only`).
* Plugins MUST keep their sensor emission rate at or below the rate they declared in the manifest. WearTAK enforces this with a leaky bucket per topic and emits a diagnostic if the budget is exceeded; persistent abuse triggers `onPluginInvalidated`.

### 8.5 Lifecycle / state-transition flow

```
[BOOT]
  └─► WearTakApplication.onCreate
        ├─► CepProvider.init
        ├─► CepConfigRegistry.bootstrap            (BUILT_IN + cached MANAGED_CONFIG + cached PLUGIN + RUNTIME)
        ├─► CepEngine constructed with registry.current
        └─► CepEngine.start                         (per-feature coroutines, ruleEval coroutine)

[MDM PUSH]
  └─► RestrictionsManager broadcast
        └─► MainActivity.handleManagedConfigUpdate
              ├─► persist new overlay keys to DataStore
              └─► EventBus.post(CEP_OVERLAY_CHANGED)
                   └─► CepConfigRegistry.recompose
                         └─► CepEngine.applyConfig(diff)

[PLUGIN INSTALL]
  └─► Plugin binds to CepPluginGateway
        └─► gateway.registerPlugin(manifest)
              ├─► validate manifest + signature
              ├─► CepConfigRegistry.recompose
              └─► CepEngine.applyConfig(diff)

[PLUGIN UNINSTALL / UNBIND / DEATH]
  └─► gateway.onBinderDied or unregisterPlugin
        ├─► drop plugin's PLUGIN-layer contributions
        ├─► CepConfigRegistry.recompose
        └─► CepEngine.applyConfig(diff)

[CLEAR / RESET]
  └─► MDM sets cep_clear_* or cep_reset_configuration
        └─► same flow as MDM PUSH

[FAILURE]
  └─► applyConfig throws ─► revert to previous CepConfig
                          └─► EventBus.post(CEP_OVERLAY_REJECTED)
  └─► three consecutive crashed coroutines
        └─► safe-mode (BUILT_IN-only) until reboot
```

### 8.6 Edge cases checklist

1. **Same id appears in BUILT_IN and MANAGED_CONFIG.** MANAGED_CONFIG wins. Diagnostic: `id_collision_overridden`.
2. **Same id appears in MANAGED_CONFIG and PLUGIN.** MANAGED_CONFIG wins; plugin contribution dropped. Diagnostic: `plugin_blocked_by_mdm`.
3. **Two plugins contribute the same id.** First-registered wins. Second is dropped with `plugin_id_collision`.
4. **Malformed JSON.** Whole layer rejected; engine continues on previous good config; diagnostic with `json_parse_error` and the offending substring.
5. **Schema version mismatch.** Layer rejected; diagnostic `schema_version_mismatch(required=1.0, got=2.0)`.
6. **Rule references a feature id that is not in the merged feature set.** Rule dropped with `unresolved_feature_ref`. Other rules continue.
7. **Plugin uninstalls mid-session.** Binder death → all PLUGIN entries from that plugin are removed; alerts cleared via `AlertRegistry.clearByUid`.
8. **MDM pushes empty strings for the new keys after a non-empty profile.** Equivalent to `cep_clear_rules: managed_only` + `cep_clear_features: managed_only`.
9. **Plugin emits to a topic it did not declare.** Drop the event, increment diagnostic counter. After N drops in a window, post an `onPluginInvalidated`.
10. **MDM pushes `cep_reset_configuration: true` repeatedly.** Idempotent; each push schedules at most one reset because the local mirror is cleared after consumption.
11. **Cold start with cached MANAGED_CONFIG that is now stale (admin removed the key).** RestrictionsManager broadcast on first foreground will surface the empty value; recompose will drop the cached layer. Until that broadcast arrives (typically <2s), the watch may run on a profile the admin has just revoked. Acceptable for v1.
12. **`patch` strategy applied to a feature whose `type` differs.** Rejected with `patch_type_mismatch`. Caller should use `merge` or `replace`.
13. **Disabled id referenced by a still-enabled rule.** The rule's `condition` will evaluate against a missing feature; per `JsonLogic` Phase 4 semantics this evaluates to `false`. The rule never fires. Diagnostic: `rule_dependency_disabled`.
14. **Active alert is owned by a rule that just got removed by recompose.** `applyConfig` calls `AlertRegistry.clearByUid` for that rule's UID; the action dispatcher is informed so downstream sinks can clear their UI state.
15. **Sustain-override JSON references a rule id that no longer exists post-merge.** Override is silently ignored (matches today's behavior). Diagnostic: `sustain_override_orphan`.
16. **Plugin tries to call AIDL methods before `registerPlugin`.** Gateway rejects with `PluginNotRegisteredException`.
17. **Two different MDM keys carry conflicting merge strategies.** `cep_merge_strategy` is the global; per-overlay `merge_strategy` field overrides per-overlay. If both overlay JSONs disagree, the later-applied wins; diagnostic logs the conflict.

### 8.7 Test plan

**Unit tests (no Android dependencies, JVM-only):**

* `CepConfigMergerTest`
  * merge strategy `replace`/`merge`/`patch` produce the documented output for synthetic 3-id inputs across all four layers.
  * id collision precedence matches Section 4.4.
  * disabled-id removal happens after merge, not before.
* `ManagedCepOverlayParserTest`
  * schema_version mismatch rejects whole layer.
  * malformed JSON rejects whole layer.
  * per-entry parse errors drop only that entry.
  * each parsed entry is tagged `MANAGED_CONFIG`.
* `PluginCepOverlayParserTest`
  * each parsed entry is tagged `PLUGIN`.
  * plugin id namespacing works when configured.
  * disallowed override of BUILT_IN is rejected when override flag is false.
* `CepDisabledIdSetTest`
  * union semantics; explicit clear; unknown ids are no-ops.
* `RuleEngineWithLayeredConfigTest`
  * rules from each layer fire identically; alerts carry source attribution metadata.

**Instrumented tests (Android, on watch or emulator):**

* `ManagedConfigPushTest`
  * Use `RestrictionsManager` test broadcast; assert `CepConfigRegistry.current` updates within 2s.
  * Round-trip a 20KB overlay JSON; assert correct rules fire.
* `CepEngineApplyConfigTest`
  * Add a feature → confirm coroutine spins up.
  * Remove a feature → confirm coroutine cancellation and `FeatureStore` cleanup.
  * Replace a feature with sustain-only delta → confirm soft-update path (no coroutine churn).
* `RecomposeRollbackTest`
  * Force `applyConfig` to throw → confirm engine reverts to previous config.
  * Force three consecutive failures → confirm safe-mode entered.
* `PluginHandshakeTest`
  * Stub plugin APK signs and registers; confirm `PLUGIN` entries appear.
  * Plugin process killed; confirm contributions are dropped within 2s.

**Simulation tests (extending the existing CEP simulation framework documented in `SIMULATION.md`):**

* Replay a recorded sensor session against the BUILT_IN config.
* Inject a MANAGED_CONFIG overlay halfway through; assert rule transitions match expected diff.
* Inject a `cep_reset_configuration: true` halfway through; assert engine state matches "fresh start".

**Manual / on-device QA matrix:**

* Test with at least one Knox profile push, one Android Enterprise UEM, and one Workspace ONE profile to confirm cross-MDM behavior.

### 8.8 Migration / backward compatibility notes

* **No data migration required.** The new DataStore keys default to empty; an upgrading watch sees BUILT_IN-only behavior on first boot, which is identical to today.
* **`cep_sustained_for_sec_overrides_json` is preserved verbatim.** Both shapes (legacy flat and dual main/warn) continue to be parsed; the registry feeds them into the RUNTIME layer rather than directly into `CepEngine.updateSustainOverrides`. The engine's existing sustain-override API stays as a legal back-door — the registry's RUNTIME parser calls it.
* **`custom_developer_config_json` continues to work** as a generic developer tunnel. We recommend it not be used to push CEP config in production builds; the dedicated keys are clearer for MDM admins. We do NOT remove or deprecate it.
* **No change to existing sensor services** (`HeartrateService`, etc.). They keep emitting to `CepBus` directly. Plugin sensors use the new gateway.
* **No change to `CepActionDispatcher` or `AlertRegistry`** beyond passing through the new `source` attribution.
* **Older builds installed with newer MDM profiles** ignore the new keys — Android `RestrictionsManager` returns defaults for unknown keys. The MDM admin sees no error from the watch; the new policy simply does not take effect.

### 8.9 Open questions

| ID | Question | Decision needed by |
|---|---|---|
| `[OPEN-Q-1]` | Should `patch` strategy be implemented in v1 or deferred to v2? It is the most useful for small policy tweaks but also the most error-prone. | Pre-implementation |
| `[OPEN-Q-2]` | Should built-in rules ever be deletable (vs. only disable-able)? Some MDM admins will request true deletion to reduce attack surface. | Pre-implementation |
| `[OPEN-Q-3]` | Should plugins ever be allowed to override `BUILT_IN` (gated by an MDM opt-in `cep_plugins_can_override_builtin`) or never? | Pre-implementation |
| `[OPEN-Q-4]` | Signature/key distribution: build-time-pinned public key per tenant, KMS-backed key fetched on first boot, or no signature at all in v1? | Security review |
| `[OPEN-Q-5]` | Plugin priority: first-registered-wins, manifest-declared `pluginPriority`, or MDM-controlled allowlist with explicit ordering? | Product |
| `[OPEN-Q-6]` | Is "safe-mode after 3 consecutive crashed coroutines" the right threshold and behavior, or should the engine instead surface a high-priority alert and let the operator decide? | Product / SRE |
| `[OPEN-Q-7]` | Do overlay JSONs ever need at-rest encryption? They are not PII but may carry tenant policy that the customer regards as sensitive. | Security |
| `[OPEN-Q-8]` | Should `engine.tick_hz` and `rule_eval_interval_ms` be tunable via MANAGED_CONFIG, or hardcoded? Tuning these affects battery; admins might want to slow the engine on long missions. | Product / Power |
| `[OPEN-Q-9]` | Plugin AIDL versioning: how do we evolve the AIDL surface without forcing every plugin SDK to recompile? Recommend a two-version compatibility window (current + previous minor). | Platform |
| `[OPEN-Q-10]` | Does the operator-facing UI need a "view active CEP policy" screen that lists rules with source attribution? Useful for triage but adds UI scope. | UX |
| `[OPEN-Q-11]` | What is the canonical **shape** for `disabled_rule_ids` in `app_restrictions.xml` — a CSV string (simpler) or a `bundle_array` (more idiomatic but harder for some MDMs to author)? Different UEMs render these differently. | Field validation |
| `[OPEN-Q-12]` | Should the `RUNTIME` layer expand beyond sustain overrides in v1 (e.g. operator-side rule disable in the watch UI) or stay narrow? | Product |

---

## Appendix A — Mapping to existing code

Every reference in this design corresponds to the file at the listed path in the WEAR-510 branch as of 2026-04-25:

| Reference | Path |
|---|---|
| `R.raw.cep_rules` | `app/src/main/res/raw/cep_rules.json` |
| `RuleCompiler.loadFromRaw` | `app/src/main/java/com/aitech/weartak_civ/cep/engine/RuleCompiler.kt` |
| `CepConfig`, `FeatureDef`, `RuleDef` | `app/src/main/java/com/aitech/weartak_civ/cep/engine/CepConfig.kt` |
| `CepEngine.start`, `updateSustainOverrides`, `ingest` | `app/src/main/java/com/aitech/weartak_civ/cep/engine/CepEngine.kt` |
| `RuleEngine.processTick` | `app/src/main/java/com/aitech/weartak_civ/cep/engine/RuleEngine.kt` |
| `FeatureStore`, `WindowStore`, `RingConfig` | `app/src/main/java/com/aitech/weartak_civ/cep/engine/{FeatureStore,WindowStore,RingConfig}.kt` |
| `CepProvider.init`, `getOrCreateEngine` | `app/src/main/java/com/aitech/weartak_civ/cep/di/CepProvider.kt` |
| `CepBus` | `app/src/main/java/com/aitech/weartak_civ/cep/bus/CepBus.kt` |
| `Topics`, `TopicRegistry` | `app/src/main/java/com/aitech/weartak_civ/cep/model/{Topics,TopicRegistry}.kt` |
| `CepActionDispatcher.register`, severity throttle | `app/src/main/java/com/aitech/weartak_civ/cep/engine/action/CepActionDispatcher.kt` |
| `AlertRegistry.clearAll`, `clearByUid` | `app/src/main/java/com/aitech/weartak_civ/cep/engine/action/AlertRegistry.kt` |
| `JsonLogic` operators (Phase 4 null→false) | `app/src/main/java/com/aitech/weartak_civ/cep/engine/JsonLogic.kt` |
| `WearTakApplication.onCreate` | `app/src/main/java/com/aitech/weartak_civ/WearTakApplication.kt` |
| `MainActivity.handleManagedConfigUpdate` | `app/src/main/java/com/aitech/weartak_civ/presentation/MainActivity.kt` (≈ lines 1572-2400) |
| `app_restrictions.xml` | `app/src/main/res/xml/app_restrictions.xml` |
| `DataRepository.getCepSustainOverridesOnce` etc. | `app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt` |
| `MessageEvent.CUSTOM_DEVELOPER_CONFIG_JSON_CHANGED` | `app/src/main/java/com/aitech/weartak_civ/events/MessageEvent.kt` |
| `CompanionAppService` (BLE GATT, not a CEP plugin) | `app/src/main/java/com/aitech/weartak_civ/service/CompanionAppService.kt` |
| `HeartrateService` (example sensor → CepBus) | `app/src/main/java/com/aitech/weartak_civ/service/HeartrateService.kt` |
| Phase migration plan | `docs/CEP_Migration_Plan_And_Test_Strategy.md` |
| Simulation framework | `docs/SIMULATION.md` |

## Appendix B — What this design does **not** do

Out of scope for WEAR-510:

* No new CEP rules, no changes to existing rule logic, no changes to `JsonLogic` operators.
* No refactor of `CepConfig` beyond adding a `source` enum.
* No changes to `CepActionDispatcher` throttle policy or `AlertRegistry` semantics.
* No new sensor services. Existing sensor services keep emitting to `CepBus` directly.
* No changes to `CompanionAppService` BLE protocol.
* No UI work beyond a possible diagnostics screen (gated by `[OPEN-Q-10]`).
* No CoT/TAK protocol changes.
* No changes to the simulation framework other than the additive scenarios in Section 8.7.
