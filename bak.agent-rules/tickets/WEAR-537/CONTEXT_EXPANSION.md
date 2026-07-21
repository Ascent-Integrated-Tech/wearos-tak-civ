# WEAR-537 Context Expansion

- Agent Rules Version: `30e3966`
- Agent Rules Entry Point: `AGENTS.md`
- Active Agent File: `agent-rules/TICKET_CONTEXT_EXPANSION_AGENTS.md`
- Resolved Raw Source Ticket: `agent-rules/tickets/WEAR-537/TICKET.md`
- Raw Ticket Provenance: Jira bootstrap via `details WEAR-537` on 2026-06-17

## Section 1: Ticket Intent Summary

### Problem statement
Release builds of WearTAK appear to break IPC with the ATOS watch plugin, while debug builds of WearTAK continue to interoperate with ATOS. The observed matrix from the raw ticket is:

- ATOS Debug + WearTAK Debug = works
- ATOS Debug + WearTAK Release = does not work
- ATOS Release + WearTAK Release = does not work
- ATOS Release + WearTAK Debug = works

### Clarified intent
- This ticket is scoped to ATOS only for now.
- Minimum acceptance is that `WearTAK Release + ATOS Release` works.
- Preferred stretch acceptance, if low lift, is that WearTAK debug and release both interoperate with ATOS debug and release.
- A later plugin-architecture hardening patch may generalize the fix pattern once root cause is known, but that is not part of this ticket.

### Trigger / actor / expected outcome
- Trigger: ATOS attempts to bind to WearTAK and send CoT into WearTAK's exported IPC service.
- Actors: WearTAK watch app, ATOS wearable plugin, and the watch runtime where both are installed.
- Expected outcome: ATOS can bind to WearTAK in release builds, submit CoT payloads, and those payloads flow through WearTAK's ATOS ingestion path and become visible in the map/render pipeline.

## Section 2: Current Repo Context

### Primary architecture touchpoints
- [app/src/main/java/com/aitech/weartak_civ/ipc/WearTakCotIpcService.kt](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/java/com/aitech/weartak_civ/ipc/WearTakCotIpcService.kt)
  - Exported Android `Service` that exposes the AIDL binder `IWearTakAppCotListener`.
  - Logs on `onCreate`, `onStartCommand`, `onBind`, rejected callers, payload receipt, parse results, and TAK forwarding.
  - Restricts callers to `com.toughstump.atoswearable` in release builds.
- [app/src/main/aidl/com/weartak/ipcservice/IWearTakAppCotListener.aidl](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/aidl/com/weartak/ipcservice/IWearTakAppCotListener.aidl)
  - Defines the cross-app binder contract used by ATOS to call WearTAK.
  - This interface lives in package `com.weartak.ipcservice`, outside the app namespace `com.aitech.weartak_civ`.
- [app/src/main/AndroidManifest.xml](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/AndroidManifest.xml:357)
  - Registers `.ipc.WearTakCotIpcService` as `android:exported="true"` with action `com.weartak.ipcservice.START`.
  - Declares a `<queries>` entry for `com.toughstump.atoswearable`.
- [app/src/main/java/com/aitech/weartak_civ/presentation/MainActivity.kt](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/java/com/aitech/weartak_civ/presentation/MainActivity.kt:1299)
  - Calls `WearTakCotIpcService.ensureStarted(applicationContext)` during app startup.
- [app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt:1608)
  - Stores accepted ATOS packets in `receivedAtosTagList`.
  - Emits `ATOS tag accepted` and related logs after successful ingestion.
- [app/src/main/java/com/aitech/weartak_civ/presentation/MapActivity.kt](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/java/com/aitech/weartak_civ/presentation/MapActivity.kt:638)
  - Renders ATOS markers from `receivedAtosTagList`.
  - Emits `ATOS local map render skipped` only after ATOS packets have already entered the repo pipeline.

### Release-build-specific repo evidence
- [app/build.gradle](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/build.gradle:82)
  - Release enables `minifyEnabled true` and `shrinkResources true`.
- [app/proguard-rules.pro](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/proguard-rules.pro:53)
  - Keeps `com.aitech.weartak_civ.**` but does not explicitly keep `com.weartak.ipcservice.**`.
  - Because the AIDL interface package is outside the app namespace, release-only shrinking/obfuscation is a plausible root-cause area that is not already obviously protected by current keep rules.

### User-provided runtime evidence
- On watch `R3AX300LQJT`, both release apps are installed: `com.aitech.weartak_civ` and `com.toughstump.atoswearable`.
- WearTAK `MainActivity` reaches foreground.
- Captured logcat contains no `WearTakAtosIpc` or `WearTakCotIpcService` lines.
- Captured logcat also lacks `Rejected ATOS bind`, `ATOS tag accepted`, and `ATOS local map render skipped`.
- Map logs show normal `MapUserItem` rendering only, with no ATOS-tag-specific render activity.

### Repo-grounded interpretation
- The missing ATOS IPC service log signatures suggest the failure likely occurs before normal payload handling and before the ATOS map/render path.
- That narrows the likely failure zone to service startup/discoverability, exported binder contract compatibility, or release-only build transformations affecting the IPC boundary.
- This is an inference from repo evidence and runtime observations, not a confirmed root cause.

## Section 3: Proposed Scope Boundaries (Will / Won't)

### Will do
- Diagnose and fix the WearTAK-side release regression that prevents ATOS watch IPC from working.
- Validate at minimum that `WearTAK Release + ATOS Release` functions end-to-end.
- Preserve the existing ATOS ingestion path:
  - bind to exported IPC service
  - receive CoT via AIDL
  - parse and accept ATOS payload
  - update `receivedAtosTagList`
  - render ATOS markers locally and forward CoT to TAK as already implemented
- If the fix is low lift, validate the broader debug/release matrix for WearTAK and ATOS.

### Won't do
- Generalize the plugin architecture in this ticket.
- Add support for non-ATOS plugins in this ticket.
- Redesign the ATOS data model, map rendering pipeline, or TAK forwarding behavior.
- Change caller-allowlist policy beyond what is required to restore existing ATOS interoperability.
- Add unrelated refactors in service orchestration, repositories, or UI layers.

## Section 4: Ambiguities and Clarifying Questions

### Answered in this round
- Scope is ATOS only for now.
- Minimum acceptance is `WearTAK Release + ATOS Release`.
- Best-effort broader matrix is optional if very low lift.
- Existing runtime evidence suggests the break happens before normal IPC logging and render ingestion.

### Remaining decisions to document during implementation/design
- Whether the final acceptance artifact should explicitly require all four build combinations once a likely fix is known and validation cost is clear.
- Whether a targeted release-only keep/configuration fix is sufficient, or whether manifest/service startup behavior also needs adjustment.

## Section 5: Implementation Readiness Checklist

- Raw ticket source is resolved locally.
- Scope boundaries are explicit and ATOS-only.
- Minimum acceptance target is concrete and observable.
- Primary affected components are identified with repo paths.
- Release-only build behavior is identified as a high-value investigation area.
- Runtime evidence is consistent with failure before normal ATOS IPC payload handling.
- No architecture redesign is required by the ticket.
- Ready for solution design after user review of the rewritten ticket.

## Section 6: Ticket Rewrite Summary

### Draft summary
Release builds of WearTAK currently fail to interoperate with the ATOS watch plugin even though the same ATOS flows work against WearTAK debug builds. The investigation should focus on the WearTAK-side exported IPC service and release-only build behavior so that ATOS can bind and deliver CoT successfully in release builds.

### Draft acceptance shape
- Required: `WearTAK Release + ATOS Release` works end-to-end.
- Preferred if low lift: WearTAK debug and release both work with ATOS debug and release.
