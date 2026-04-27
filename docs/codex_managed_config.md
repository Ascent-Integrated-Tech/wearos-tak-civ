• Current State
  CEP is built from a single bundled JSON file, parsed at startup, and started once from the application object: app/src/main/java/com/aitech/weartak_civ/WearTakApplication.kt:86, app/src/main/java/com/aitech/
  weartak_civ/cep/di/CepProvider.kt:28, app/src/main/java/com/aitech/weartak_civ/cep/engine/RuleCompiler.kt:10, app/src/main/res/raw/cep_rules.json:1. The rule/feature model is already fairly rich: app/src/main/
  java/com/aitech/weartak_civ/cep/engine/CepConfig.kt:3, app/src/main/java/com/aitech/weartak_civ/cep/engine/JsonLogic.kt:11, app/src/main/java/com/aitech/weartak_civ/cep/engine/DerivedEval.kt:31.

  ManagedConfiguration is currently parsed only in MainActivity, not in an app-wide config service/receiver: app/src/main/java/com/aitech/weartak_civ/presentation/MainActivity.kt:1240, app/src/main/java/com/
  aitech/weartak_civ/presentation/MainActivity.kt:1572. Today it updates scalar settings and a generic custom_developer_config_json string, but not CEP rule/feature definitions: app/src/main/res/xml/
  app_restrictions.xml:607, app/src/main/java/com/aitech/weartak_civ/presentation/MainActivity.kt:1828, app/src/main/java/com/aitech/weartak_civ/presentation/MainActivity.kt:2232, app/src/main/java/com/aitech/
  weartak_civ/data/DataRepository.kt:1690. That generic JSON is already consumed by BLE/CotDispatch knobs, so it should not become the CEP contract: app/src/main/java/com/aitech/weartak_civ/service/
  BleBeaconService.kt:181, app/src/main/java/com/aitech/weartak_civ/service/CotDispatchService.kt:2239.

  Plugins/companion integration is currently limited to settings sync over BLE, not CEP schema registration: app/src/main/java/com/aitech/weartak_civ/data/CompanionAppRepository.kt:404, app/src/main/java/com/
  aitech/weartak_civ/service/CompanionAppService.kt:1066. CEP sensor ingress today is through fixed topics and payloads plus direct dotted-key ingest: app/src/main/java/com/aitech/weartak_civ/cep/model/
  Topics.kt:3, app/src/main/java/com/aitech/weartak_civ/cep/model/TopicRegistry.kt:5, app/src/main/java/com/aitech/weartak_civ/cep/engine/WindowStore.kt:63, app/src/main/java/com/aitech/weartak_civ/cep/engine/
  CepEngine.kt:335.

  Design
  Add a dedicated ManagedConfiguration key cep_configuration_json in app_restrictions.xml. Do not reuse custom_developer_config_json. Keep the current scalar managed keys unchanged.

  Introduce a small CEP definition stack:

  - CepDefinitionSource interface: BuiltInCepSource, ManagedConfigCepSource, PluginCepSource, RuntimeCepSource.
  - CepDefinitionEnvelope: schema version, source metadata, merge mode, clear/reset flags, feature defs, rule defs.
  - CepDefinitionRegistry: stores normalized definitions by id, tracks source, version, hash, and enabled state.
  - CepConfigValidator: strict structural and semantic validation before apply.
  - CepRuntimeManager: sole owner of the active CEP session and reload lifecycle.
  - CepSession: compiled immutable config plus rule engine state for one active generation.

  Recommended packages:

  - cep/config/* for schema/parser/validator.
  - cep/registry/* for merged definition storage.
  - cep/runtime/* for reload/apply/session management.
  - integration/managedconfig/* for restrictions parsing.
  - integration/plugin/* for plugin contract/registration.

  ManagedConfiguration schema, JSON string payload:

  {
    "schemaVersion": 1,
    "rulesMode": "MERGE",
    "featuresMode": "MERGE",
    "clearCepRules": false,
    "clearCepFeatures": false,
    "resetCepConfiguration": false,
    "features": [
      {
        "id": "features.plugin.helmet_temp.mean_30s",
        "type": "window",
        "source": "helmet_temp",
        "op": "avg",
        "window_sec": 30,
        "enabled": true
      }
    ],
    "rules": [
      {
        "id": "helmet_temp_high_warn",
        "when": { "gte_key": ["features.plugin.helmet_temp.mean_30s", 38.0] },
        "sustained_for_sec": 10,
        "hold_down_sec": 60,
        "action": {
          "type": "warn",
          "severity": "WARN",
          "message": "Helmet temperature elevated"
        },
        "enabled": true
      }
    ],
    "disableRuleIds": ["pressure_shallow_dunk"],
    "disableFeatureIds": []
  }

  Merge semantics:

  - Built-in is the immutable base.
  - Plugin and ManagedConfiguration definitions are overlays.
  - Default mode is MERGE by id.
  - MERGE means upsert within that source layer, not full replacement of built-ins.
  - REPLACE should replace only the prior payload from the same source layer.
  - Admin disable lists should be explicit; silent deletion by omission is too risky.

  Validation rules:

  - Reject duplicate ids within one payload.
  - Reject rule references to missing features unless the referenced feature exists in the merged candidate.
  - Reject unknown feature types, unsupported operators, malformed monitor clauses, invalid sustain ranges, and invalid action types/severities.
  - Reject plugin/managed definitions that collide with built-in ids unless override is explicitly allowed.
  - Validation is transactional: if candidate config fails, keep current CEP session active.

  Explicit TODO Answers
  Yes, support clearing CEP overlays, but not by deleting built-in defaults.

  Use three separate flags:

  - clearCepRules: clears only ManagedConfiguration rule overlay.
  - clearCepFeatures: clears only ManagedConfiguration feature overlay.
  - resetCepConfiguration: clears both ManagedConfiguration overlays and any persisted runtime CEP overlay state.

  Built-in/default rules should never be physically removed. After clear/reset, effective CEP returns to built-in plus currently active plugin registrations.

  Plugin-provided rules/features should not be cleared by clearCepRules or clearCepFeatures. They should only disappear when the plugin unregisters, disconnects, expires, or resetCepConfiguration is explicitly
  defined to clear plugin cache too. I recommend resetCepConfiguration clears managed/runtime only, not active plugin registrations.

  Plugin Interaction
  Plugins that detect sensors should provide sensor metadata always, and may optionally provide feature definitions and rule definitions.

  Recommended contract:

  - PluginSensorMetadata: pluginId, sensorId, topic/key namespace, units, sampling characteristics, trust/version info.
  - PluginCepDefinitionEnvelope: same schema family as managed config, plus pluginId and pluginVersion.
  - PluginSampleEvent: raw sensor sample events into CEP.

  Conflict priority, highest wins:

  - MANAGED_CONFIG
  - RUNTIME if future local CEP edits are introduced
  - PLUGIN
  - BUILT_IN

  ManagedConfiguration should be allowed to disable plugin-provided features/rules by id. Plugins should not be allowed to override built-in or admin-managed definitions unless explicitly whitelisted.

  Given the current code, plugin-defined new typed topics would require expanding fixed payload/topic handling. The lower-friction design is:

  - plugin sensors publish dotted numeric/boolean keys through a generic ingest contract first
  - typed topic extension remains optional later

  Runtime, Persistence, Lifecycle
  ManagedConfiguration change should trigger immediate background validation and hot reload. Restart-only behavior is too weak for MDM.

  Reload flow:

  1. Parse managed config.
  2. Build merged candidate from built-in + plugins + managed + runtime.
  3. Validate candidate.
  4. Compile candidate.
  5. Atomically swap CepSession in CepRuntimeManager.
  6. Keep old session until new session is live, then cancel old jobs.

  To avoid disrupting active processing, CepRuntimeManager should own the sole CepBus subscription and feed only the active session. The current model starts one engine that directly subscribes forever, so hot-
  reload needs a manager layer rather than a second parallel engine.

  Persistence:

  - Persist raw managed CEP payload, normalized managed overlay, hash, applied timestamp, and last validation result.
  - Cache plugin registrations with pluginId, instanceId, version, lastSeenAt, and leaseExpiry.
  - Track source on each definition: BUILT_IN, MANAGED_CONFIG, PLUGIN, RUNTIME.
  - Remove stale plugin definitions when plugin lease expires or plugin disconnect/unregister occurs.

  Observability:

  - structured logs for parse, validate, merge, compile, apply, rollback
  - counters for accepted/rejected definitions by source
  - active config generation/hash in logs
  - admin-visible status surface for “last CEP config apply result”

  Migration, Edge Cases, Tests
  Backward compatibility:

  - no change to existing scalar managed keys
  - no change to built-in cep_rules.json
  - no change to current sustain override persistence format; keep legacy flat JSON support: app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt:3092
  - if cep_configuration_json is absent, behavior stays as today

  Edge cases to cover:

  - malformed managed JSON
  - valid JSON but unknown operator/type
  - rule references disabled feature
  - plugin disconnect during active alert
  - managed config disables a currently active rule
  - concurrent plugin registration and managed config update
  - stale cached plugin defs on reboot
  - config received while MainActivity is not running

  Test plan:

  - parser/validator unit tests for schema and operator support
  - merge precedence tests across all four sources
  - hot-reload tests proving no duplicate collectors, no lost events, and rollback on failure
  - persistence tests for clear/reset and stale plugin eviction
  - plugin conflict tests
  - end-to-end managed config change tests from application restrictions event to active CEP generation swap

  Open Questions

  - ManagedConfiguration is currently applied from MainActivity; if the app is backgrounded, does the product require CEP policy application without UI launch?
  - “Plugins” in scope appear to mean the BLE companion/plugin path; if there are also in-process WearTAK plugins, they are not evident in this branch.
  - The desired trust model for plugin-provided CEP rules is unclear. Should any bonded plugin be allowed to provide rules, or only approved plugin IDs/certs?
  - Whether plugin cache should survive reboot if the plugin is not yet reconnected needs product input.
  - Whether MDM admins want bundle-array style restrictions instead of one JSON string remains open, but the codebase today is better aligned with a dedicated JSON string payload.
