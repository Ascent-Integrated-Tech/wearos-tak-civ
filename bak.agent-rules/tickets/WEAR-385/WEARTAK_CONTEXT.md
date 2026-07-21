# WearTAK-CIV Context

## Project Summary
- WearTAK-CIV is a Wear OS watch app that acts as a TAK client and sensor hub.
- It runs as a standalone watch application (`com.google.android.wearable.standalone=true`) and can optionally bridge to a companion app over BLE.
- Primary mission: collect on-watch telemetry (location, physiologic, environment/activity), transform to CoT, and send to TAK ecosystem endpoints (TAK server, optional mesh/multicast/companion/SITX paths).

## Hardware / Platform Targets
- Intended hardware (per project docs): Samsung Galaxy Watch 7/8 and Galaxy Watch Ultra 7/8.
- Android target profile in code:
  - `minSdk 34`
  - `targetSdk 34`
  - `compileSdk 35`
  - Watch-only feature flag: `android.hardware.type.watch`
  - GPS required, BLE optional, telephony optional.
- Current APK split config is `armeabi-v7a` plus a universal APK.
- Uses Samsung-specific sensor AAR (`app/libs/samsung-health-sensor-api-1.4.1.aar`) and HAIL SDK (`app/libs/hail-sdk.aar`).

## Intended Behavior (What It Does)
- Maintains direct secure TAK connectivity via foreground services (`TAKConnectService`, `SitxTakService`, `CotDispatchService`).
- Publishes PLI and event CoT (location, marker drop, SOS/manual alerts, physio alerts, chat payloads).
- Continuously orchestrates many background capabilities through `ReactiveServiceManager` + `ServiceConfiguration`.
- Collects sensor data from multiple sources:
  - Health/activity/location services.
  - Samsung skin temp integration.
  - BLE beacon signals.
  - Optional audio capture + ONNX gunshot pipeline.
- Supports watch UX surfaces:
  - Activities under `presentation/`
  - Wear Tiles (`tile/`)
  - Complications (`complication/`)
- Includes CEP engine (`cep/`) with JSON-configured features/rules and alert dispatch.
- Supports managed configuration (MDM/Knox style restrictions flow) and many runtime toggles persisted in `DataRepository`.
- Supports optional companion BLE channel for data/settings exchange (`CompanionAppService` + `CompanionAppRepository`).

## Non-Goals / Current Boundaries (What It Does Not Do)
- No offline TAK chat queueing as a product feature; README explicitly states chat is online-only.
- Not a phone/tablet-first app: this repo is a single watch app module (`:app`) and is architected for on-watch operation.
- Not a thin UI-only client: most behavior is service-driven in background/foreground services.
- No established automated test suite is present in-repo (`app/src/test` and `app/src/androidTest` are absent).
- Not designed as a generic multi-device abstraction layer today; implementation is tightly aligned to Wear OS + current Samsung watch/sensor stack.

## App Architecture Patterns
- Single-module Android app, package root: `com.aitech.weartak_civ`.
- Service-centric runtime:
  - Many long-lived foreground services, each with `isRunning` flags (typically `AtomicBoolean` in companion objects).
  - Service start/stop orchestration centralized in `ReactiveServiceManager`.
  - Dependency and policy gating handled in `ServiceConfiguration`.
- Central state/persistence hub:
  - `DataRepository` is the main source of truth for settings/state/events using DataStore + Flows.
  - Additional specialized repositories: bloodhound, companion app, ONNX, secure SITX secrets.
- Eventing style:
  - Kotlin Flow/StateFlow for reactive state.
  - `org.greenrobot:eventbus` for cross-component event distribution (legacy event handling, now using flows for all new code.)
- CEP subsystem is relatively self-contained under `cep/`:
  - Event bus (`CepBus`)
  - Feature/window stores
  - Rule compiler/engine
  - Action dispatchers/handlers.
- App bootstrap:
  - `WearTakApplication` initializes repositories, app context, CEP provider/engine. Each new feature has its own data repository layer. 
  - Boot/update receivers restart/init key services.

## Coding Patterns and Conventions
- Heavy use of Kotlin coroutines + Flow collectors inside services.
- Foreground service startup goes through `Utils.startForegroundServiceCompat(...)` for standardization and boiler plate redeuction. 
- Many components lazily obtain repositories from `WearTakApplication` with fallback construction.
- Substantial use of companion objects for:
  - Shared constants
  - Cross-class static state
  - service `isRunning` flags
  - occasionally shared singleton-like references.
- Companion objects sharing constants, states, functions is supported legacy code we have yet to fully migrate from. 
- Configuration defaults are centralized in `Constants.kt` and mirrored by DataStore keys in `DataRepository`.
- CoT construction and protocol-specific serialization are mostly in service/data utility layers (not isolated into a dedicated protocol module).

## File Structure Patterns
- `app/src/main/java/com/aitech/weartak_civ/presentation/`
  - Activities and Compose UI flows (main screens, settings, map, chat, sos, marker, bloodhound).
- `app/src/main/java/com/aitech/weartak_civ/service/`
  - Core runtime behavior; comms, sensors, orchestration, CEP simulation hooks, endpoint integrations.
- `app/src/main/java/com/aitech/weartak_civ/data/`
  - Repository/state model, parsers, secure storage wrappers.
- `app/src/main/java/com/aitech/weartak_civ/cep/`
  - Rule engine/config/evaluator/action subsystem for complex alerting.
- `app/src/main/java/com/aitech/weartak_civ/events/`
  - Event payload classes for EventBus communication.
- `app/src/main/java/com/aitech/weartak_civ/complication/` and `tile/`
  - Wear-specific watchface and tile integrations.
- `app/src/main/res/raw/`
  - Runtime configs (including CEP rules JSON) and bundled cert/test artifacts used by current flows.
- `app/libs/`
  - Checked-in third-party AARs (Samsung sensor + HAIL SDK), indicating non-Maven binary dependencies.

## Practical Guidance for Agents
- Treat `DataRepository` and `ReactiveServiceManager` as architectural anchors; changes to service behavior should usually route through these.
- Respect foreground service constraints and permission gating already modeled in `ServiceConfiguration`.
- When adding features, follow existing package layering (presentation/service/data/cep/events) instead of adding ad-hoc top-level folders.
- Be careful with static cross-component state in companion objects; many parts of the app rely on it implicitly.
- Prefer incremental edits over broad refactors unless explicitly requested; service interactions are tightly coupled.