# WEAR-537 Solution Design

- Agent Rules Version: `30e3966`
- Agent Rules Entry Point: `AGENTS.md`
- Active Agent File: `agent-rules/SOLUTION_DESIGN_AGENTS.md`
- Resolved Raw Source Ticket: `agent-rules/tickets/WEAR-537/TICKET.md`

## Section 1: Problem and Success Definition

### Problem
WearTAK release builds currently fail to interoperate with the ATOS wearable plugin, while WearTAK debug builds work. The failure appears to occur before normal ATOS payload ingestion and map rendering, based on the absence of `WearTakAtosIpc`, `Rejected ATOS bind`, `ATOS tag accepted`, and `ATOS local map render skipped` logs in the failing release scenario.

### Success definition
- Required success signal:
  - `WearTAK Release + ATOS Release` works end-to-end on-device.
- Observable behavior for success:
  - ATOS can bind to WearTAK's exported IPC service.
  - WearTAK release logs show ATOS IPC activity again, or equivalent observable behavior confirms the IPC path is live.
  - ATOS-originated CoT reaches the existing repository and map-render path.
- Optional stretch validation if low lift:
  - Confirm the broader debug/release compatibility matrix for WearTAK and ATOS.

## Section 2: Current-State Repo Findings

### Current IPC path
- [app/src/main/java/com/aitech/weartak_civ/ipc/WearTakCotIpcService.kt](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/java/com/aitech/weartak_civ/ipc/WearTakCotIpcService.kt)
  - Implements the exported service boundary for ATOS.
  - Exposes `IWearTakAppCotListener.Stub()` and logs service lifecycle, bind attempts, and payload handling.
- [app/src/main/aidl/com/weartak/ipcservice/IWearTakAppCotListener.aidl](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/aidl/com/weartak/ipcservice/IWearTakAppCotListener.aidl)
  - Defines the cross-app AIDL interface package shared with ATOS.
- [app/src/main/AndroidManifest.xml](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/AndroidManifest.xml:357)
  - Publishes `.ipc.WearTakCotIpcService` as exported with action `com.weartak.ipcservice.START`.
- [app/src/main/java/com/aitech/weartak_civ/presentation/MainActivity.kt](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/java/com/aitech/weartak_civ/presentation/MainActivity.kt:1299)
  - Calls `WearTakCotIpcService.ensureStarted(applicationContext)` on app startup.
- [app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt:1608)
  - Owns `receivedAtosTagList`, which is populated after successful ATOS IPC ingestion.
- [app/src/main/java/com/aitech/weartak_civ/presentation/MapActivity.kt](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/java/com/aitech/weartak_civ/presentation/MapActivity.kt:638)
  - Renders ATOS markers from `receivedAtosTagList`.

### Release-specific finding
- [app/build.gradle](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/build.gradle:82)
  - Release enables `minifyEnabled true` and `shrinkResources true`.
- [app/proguard-rules.pro](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/proguard-rules.pro:53)
  - Keeps `com.aitech.weartak_civ.**`, but does not explicitly preserve the external AIDL package `com.weartak.ipcservice.**`.

### Design inference
- The most likely repo-local release regression is the AIDL IPC contract being renamed or optimized differently in release because it lives outside the app package currently covered by keep rules.
- The absence of normal ATOS IPC logs is consistent with a break at or before binder compatibility, though this remains an inference until release verification is rerun.

## Section 3: Key Decisions Captured

### Decision log
- `approved`: Scope is ATOS only for this ticket.
- `approved`: Minimum acceptance is `WearTAK Release + ATOS Release`.
- `approved`: Broader WearTAK/ATOS debug-release matrix validation is optional if low lift.
- `approved`: Do not redesign the plugin architecture in this ticket.
- `proposed`: First implementation pass should preserve the shared AIDL IPC contract in release by adding targeted R8/ProGuard keep rules for `com.weartak.ipcservice.**`, while preserving the existing service/repository/render flow.
- `proposed`: Only if the keep-rule fix does not restore release IPC behavior should the ticket expand within the same scope to inspect service discoverability or startup-path specifics.
- `deferred`: Any generalized plugin-architecture hardening work will be handled in a later ticket after the release regression root cause is confirmed.

## Section 4: Solution Boundaries (Will / Won't)

### Will Do
- Implement a minimal WearTAK-side release fix centered on preserving the shared ATOS AIDL contract through release shrinking/obfuscation.
- Keep the existing exported service, caller allowlist behavior, repository ingestion flow, and map render path intact unless a narrowly scoped follow-up adjustment is proven necessary.
- Verify the release build and confirm whether ATOS IPC logs and end-to-end behavior return.
- Document results for the minimum required validation path, and the broader matrix only if cheap to verify.

### Won't Do
- Rework the plugin architecture.
- Introduce a new IPC mechanism.
- Generalize support for other plugins.
- Refactor unrelated services, repositories, or map behavior.
- Widen security policy or relax caller validation except as strictly required to restore the existing ATOS integration.

## Section 5: Implementation Contract

### Planned code touchpoints
- [app/proguard-rules.pro](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/proguard-rules.pro)
  - Add targeted keep rules for the shared IPC package `com.weartak.ipcservice.**`.
  - Preserve the AIDL interface and generated binder types so the release build keeps the same cross-app contract expected by ATOS.
- Possible secondary touchpoint only if needed after verification:
  - [app/src/main/java/com/aitech/weartak_civ/ipc/WearTakCotIpcService.kt](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/java/com/aitech/weartak_civ/ipc/WearTakCotIpcService.kt)
  - [app/src/main/AndroidManifest.xml](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/AndroidManifest.xml)
  - This secondary path is limited to ensuring release discoverability/startup of the already existing service if the keep-rule fix alone proves insufficient.

### Planned behavior changes
- No intended functional behavior change in debug.
- Release builds should preserve the same AIDL boundary and descriptor semantics expected by ATOS.
- Existing ATOS data handling after IPC ingress remains unchanged.

### Verification strategy
- Build a WearTAK release artifact after the keep-rule change.
- Confirm the shared AIDL package remains preserved in the release output or mapping artifacts.
- On-device, verify at minimum:
  - `WearTAK Release + ATOS Release` restores observable IPC behavior.
  - WearTAK logs show the ATOS IPC path becoming active again.
  - ATOS payloads reach `receivedAtosTagList` and local ATOS map rendering.
- If low lift, record results for:
  - `WearTAK Debug + ATOS Debug`
  - `WearTAK Debug + ATOS Release`
  - `WearTAK Release + ATOS Debug`
  - `WearTAK Release + ATOS Release`

### Safe rollback / containment
- The primary change is configuration-only and narrowly scoped to release shrinking behavior.
- If the keep-rule change is ineffective or causes unexpected side effects, it can be reverted without altering the ATOS data path design.

## Section 6: Open Risks and Follow-ups

### Open risks
- The keep-rule hypothesis may not fully explain the failure; release service startup or manifest-level discoverability may still require a second narrow fix.
- Current evidence is based on missing logs rather than a confirmed binder exception trace, so verification needs to be explicit after the first implementation pass.

### Follow-ups outside this ticket
- If this root cause pattern is confirmed, create a separate plugin-architecture hardening ticket so future plugin IPC integrations do not rely on incidental release configuration coverage.

## Section 7: Approval Status

- Current status: approved for implementation
- User approval token received: `APPROVED FOR IMPLEMENTATION`
- Implementation authorized on: 2026-06-17
