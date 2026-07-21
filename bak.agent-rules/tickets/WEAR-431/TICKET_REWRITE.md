# WEAR-431 - Backend for Editing Dropped Marker Title and Location

## Title
Backend for editing a dropped marker's title and location.

## Problem / Why
Operators can access edit actions for dropped markers, but backend behavior for propagating edits to outbound TAK/BLE marker events is not explicitly defined. Without a clear contract, title/location edits may remain local or behave inconsistently across connected endpoints.

## User Impact
Users need marker edits to reliably update the existing dropped marker representation (same marker `uid`) so collaborators and systems consuming CoT receive the updated title/location.

## In-Scope
- Backend handling for updating dropped marker title by marker `uid`.
- Backend handling for updating dropped marker location by marker `uid` (current watch location flow).
- Propagation of edited marker state into marker outbound messaging paths currently used by the app (TAK CoT and companion BLE marker envelope when enabled).
- Consistent marker identity retention (`uid`) across edit operations.

## Out-of-Scope
- New marker types or icon-set changes.
- New marker UI screens beyond existing edit actions.
- General refactor of marker architecture, event bus model, or dispatch scheduler.
- Non-marker features.

## Functional Requirements
1. Editing marker title updates stored dropped marker entry identified by `uid`.
2. Editing marker location updates stored dropped marker entry identified by `uid` using current location snapshot.
3. Edited marker retains original `uid`.
4. After each successful title or location edit, backend immediately emits updated marker data using existing marker send paths in `CotDispatchService`.
5. If send preconditions are not met (for example endpoint unavailable), edited marker events are queued and transmitted later when marker send preconditions are met, consistent with existing marker queue behavior.
6. Backend accepts title payload exactly as provided by frontend, including empty string.

## Non-Functional Constraints
- Follow existing repository/service patterns (`MarkerActivity` -> `MarkerViewModel` -> `DataRepository` -> `CotDispatchService`).
- No architectural redesign.
- Keep behavior deterministic and testable from logs/manual validation.

## Acceptance Criteria
1. Given an existing dropped marker, when title is edited and confirmed, then dropped marker list reflects new title for same `uid`.
2. Given an existing dropped marker, when move-to-current-location is confirmed, then dropped marker list reflects new location for same `uid`.
3. Given endpoint connectivity and enabled transport, marker edit sends updated marker data immediately using existing outbound pathways.
4. Marker edit does not create a new marker `uid`.
5. Given endpoint unavailability at edit time, edit events are queued and then sent once endpoint/location freshness conditions allow sending.
6. Given an empty title from frontend, backend stores and dispatches that empty title without backend-side rejection.

## Dependencies / Risks
- Endpoint availability conditions may affect real-time update confirmation.
- Queue semantics for edited markers must stay consistent with current marker queue/send preconditions to avoid regressions.

## Open Decisions Needed
None for context phase; decisions captured in this rewrite.
