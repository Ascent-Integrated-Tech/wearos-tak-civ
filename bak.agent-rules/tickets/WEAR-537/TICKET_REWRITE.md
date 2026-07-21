# WEAR-537 Ticket Rewrite

- Agent Rules Version: `30e3966`
- Agent Rules Entry Point: `AGENTS.md`
- Active Agent File: `agent-rules/TICKET_CONTEXT_EXPANSION_AGENTS.md`
- Resolved Raw Source Ticket: `agent-rules/tickets/WEAR-537/TICKET.md`

## Title
Restore ATOS IPC compatibility for WearTAK release builds

## Problem / Why
WearTAK release builds currently fail to interoperate with the ATOS wearable plugin, while WearTAK debug builds continue to work. This blocks release validation of the ATOS integration and indicates a WearTAK-side regression that is specific to release build behavior.

## User impact
Operators using release builds of WearTAK cannot rely on ATOS data reaching the app over the existing IPC path. ATOS-originated CoT does not enter the WearTAK ingestion pipeline, so expected ATOS markers and downstream TAK forwarding do not occur.

## In-scope
- Investigate the WearTAK-side release regression affecting ATOS IPC.
- Restore ATOS-to-WearTAK IPC behavior for release builds without redesigning the broader plugin architecture.
- Preserve the current ATOS ingestion path already implemented in WearTAK:
  - exported IPC service
  - AIDL binder calls
  - CoT parse and repository ingest
  - local ATOS marker rendering
  - forwarding to TAK services
- Validate at minimum that `WearTAK Release + ATOS Release` works end-to-end.
- If very low lift, also validate the broader WearTAK debug/release x ATOS debug/release matrix.

## Out-of-scope
- General plugin-architecture hardening for future plugins.
- Non-ATOS plugin compatibility work.
- Refactoring unrelated IPC, map, repository, or service-management code.
- Introducing new user-facing ATOS features beyond restoring the existing integration.

## Functional requirements
- WearTAK release builds must expose a working IPC boundary for the ATOS plugin using the existing binder contract.
- An ATOS release build must be able to bind to WearTAK release and submit CoT successfully.
- Once ATOS payloads arrive, WearTAK must continue to use the existing ATOS parse, repository, and render flow.
- The fix must remain compatible with the current expected ATOS package `com.toughstump.atoswearable`.

## Non-functional constraints
- Keep the fix tightly scoped to the ticket.
- Follow the existing service and repository patterns already used in the app.
- Avoid architecture redesign.
- Prefer the smallest change that restores release interoperability.

## Acceptance criteria
- `WearTAK Release + ATOS Release` works end-to-end on-device.
- WearTAK release logs or equivalent observable behavior confirm that the ATOS IPC path is active.
- ATOS-originated data reaches the existing WearTAK ingestion/render path instead of failing before bind or payload handling.
- If validation cost is low, document results for the broader matrix:
  - `WearTAK Debug + ATOS Debug`
  - `WearTAK Debug + ATOS Release`
  - `WearTAK Release + ATOS Debug`
  - `WearTAK Release + ATOS Release`

## Dependencies / risks
- Release builds enable minification and shrinking in [app/build.gradle](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/build.gradle:82), so release-only transformation of the IPC boundary is a likely risk area.
- The AIDL contract lives under package `com.weartak.ipcservice` in [IWearTakAppCotListener.aidl](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/aidl/com/weartak/ipcservice/IWearTakAppCotListener.aidl), while current keep rules in [app/proguard-rules.pro](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/proguard-rules.pro:53) explicitly preserve `com.aitech.weartak_civ.**` but not that external IPC package.
- Current runtime evidence shows no `WearTakAtosIpc` service logs in the failing release scenario, which suggests the failure may occur before normal payload handling.
