# WEAR-431 Solution Design

## Section 1: Problem and Success Definition
- Ticket: `WEAR-431`
- Resolved raw source ticket path: `agent-rules/tickets/WEAR-431/TICKET.md`
- Objective: implement backend support for editing dropped marker title and location such that edits preserve marker identity and propagate through existing outbound marker channels.
- User impact: edited markers must appear updated on other TAK consumers, not only in local watch UI state.
- Trigger conditions:
  - Title edit confirm in `MarkerActivity` calls `MarkerViewModel.requestChangeMarkerTitle(uid, newTitle)`.
  - Location edit confirm in `MarkerActivity` calls `MarkerViewModel.requestUpdateMarkerLocation(uid)`.
- Success signal:
  - Local dropped marker list updates for same `uid`.
  - Outbound marker update send occurs immediately when send preconditions are satisfied.
  - If preconditions are not satisfied, edit event is queued and later flushed automatically.

## Section 2: Current-State Repo Findings
- UI -> ViewModel wiring for edits already exists:
  - `presentation/MarkerActivity.kt` calls edit methods by `uid`.
  - `presentation/viewmodel/MarkerViewModel.kt` passes edit requests directly to `DataRepository`.
- Current data behavior:
  - `data/DataRepository.kt` `requestChangeMarkerTitle` and `requestUpdateMarkerLocation` update `_droppedMarkerList` only.
  - No edit request flow currently exists analogous to `markerDropRequests`.
- Current send/queue behavior:
  - `service/CotDispatchService.kt` sends markers from `markerDropRequests` only (`startMarkerDropCollector` -> `sendMarkerDropNow`).
  - Offline/unready marker drops are queued in `pendingMarkerDropQueue` and flushed by readiness checks.
  - `droppedMarkerList` collector currently logs list changes only (`startMarkerDropListCollector` -> `logMarkerDropList`), with no edit-send path.
- Marker identity and snapshots:
  - Marker send snapshots are retained in `markerDropMap[uid]`.
  - `snapshotMarker(req)` generates a UID only when `req.uid` is empty, so edit updates can preserve UID if request carries existing UID.

## Section 3: Key Decisions Captured
Decision log:
1. Immediate outbound edit send after successful local mutation.
- Status: `approved`
- Source: user clarification in context phase.

2. Queue edited marker sends when endpoint/location freshness preconditions fail; flush later.
- Status: `approved`
- Source: user clarification in context phase.

3. Accept title payload exactly as frontend provides, including empty string.
- Status: `approved`
- Source: user clarification in context phase.

4. Keep location edit source as existing "move to current location" flow only.
- Status: `approved`
- Source: user clarification in context phase.

5. Reuse existing marker send readiness logic (`readyToSendMarkerEvents`) and transport fan-out path.
- Status: `proposed`
- Rationale: minimizes risk and preserves current architecture and behavior consistency.

6. No schema/DataStore persistence addition for edit queue in this ticket.
- Status: `deferred`
- Rationale: current marker drop queue is in-memory; extending to persisted queue is broader than ticket scope.

## Section 4: Solution Boundaries (Will / Won't)
Will Do:
- Add backend edit-request pathway that carries marker UID plus updated fields needed for outbound update send.
- Update marker state in repository by UID, then trigger marker update send through `CotDispatchService`.
- Reuse existing marker send transports (TAK, BLE, SITX, Ditto, multicast as currently applicable) for edit updates.
- Queue edit sends when not ready and flush when ready, mirroring existing marker drop queue semantics.
- Preserve marker UID and update existing `markerDropMap` entry rather than creating new marker identity.

Won't Do:
- No architecture redesign or new subsystem for markers.
- No new UI screens or manual lat/lon entry flow.
- No change to broader dispatch scheduler strategy.
- No persistent/offline durable queue redesign for marker edits in this ticket.

## Section 5: Implementation Contract
Component touchpoints:
- `app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt`
  - Introduce edit request model/flow for marker updates by UID.
  - Extend edit APIs to emit edit requests after local list mutation.
  - Keep `droppedMarkerList` as source for UI list state.
- `app/src/main/java/com/aitech/weartak_civ/service/CotDispatchService.kt`
  - Add collector for marker edit requests.
  - Implement `sendMarkerUpdateNow(...)` that builds updated snapshot with existing UID and dispatches via existing transports.
  - Add in-memory pending edit queue and flush behavior using existing readiness checks.
  - Update `markerDropMap` entry for edited UID after successful send.
- `app/src/main/java/com/aitech/weartak_civ/presentation/viewmodel/MarkerViewModel.kt`
  - Keep ViewModel as UI boundary (no direct UI repository calls added).

Data/API behavior changes:
- Introduce explicit backend contract for edit events, likely containing:
  - `uid`
  - optional `title`
  - optional `location` update intent (current location snapshot path)
  - event time for ordering/logging.
- For title edit:
  - local list item updated by UID.
  - outbound update uses new title exactly as sent (including empty).
- For location edit:
  - local list item location replaced from current snapshot.
  - outbound update uses updated location and original UID.

Failure/queue behavior:
- If `readyToSendMarkerEvents()` is false, enqueue edit request and optionally request location refresh similarly to drop flow.
- When readiness returns true, flush pending edit queue FIFO.
- Maintain drop and edit queues independently to avoid semantic coupling regressions.

Permission/config impacts:
- No new permissions.
- Reuses existing endpoint enablement and availability checks.

Verification strategy (manual/log-based):
1. Edit title while connected and with fresh location:
- Observe same UID in outbound update and updated title in recipient TAK.
2. Edit location while connected:
- Observe same UID with changed point lat/lon.
3. Edit while disconnected/unready:
- Observe queueing log, then automatic send after readiness recovery.
4. Empty title edit:
- Confirm no backend rejection and outbound payload uses empty title value semantics.
5. Regression spot checks:
- Marker drop and clear flows still work unchanged.

Rollback/safe-disable strategy:
- Keep changes isolated to marker edit request pipeline.
- If regressions appear, disable edit-send trigger path while retaining local list edits through existing repository methods.

## Section 6: Open Risks and Follow-ups
- In-memory edit queue (non-persistent) may lose unsent edits on process death; deferred by scope.
- Existing `sendMarkerDropNow` logic appends dropped marker entries only after successful send; edit path must avoid duplicating list entries.
- Existing readiness requirement depends on fresh location; title-only edits will still queue when location is stale per current marker policy.

## Section 7: Approval Status
- Design status: `READY FOR REVIEW`
- Context gate: `CONTEXT_APPROVED` received.
- Required token to proceed to implementation phase: `APPROVED FOR IMPLEMENTATION`.
- Implementation gate: `APPROVED FOR IMPLEMENTATION` received from user on 2026-03-06.
