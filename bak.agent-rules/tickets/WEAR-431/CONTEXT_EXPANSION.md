# WEAR-431 Context Expansion

## Section 1: Ticket Intent Summary
- Ticket: `WEAR-431`
- Resolved raw source ticket path: `agent-rules/tickets/WEAR-431/TICKET.md`
- Raw summary: Backend for editing a dropped marker's title and location.
- Problem statement (inferred from ticket + repo): dropped markers can be edited in UI, but backend behavior must clearly guarantee update propagation to CoT/BLE paths and stable marker identity (`uid`) so edits update the same map item rather than creating duplicates.

## Section 2: Current Repo Context
- Marker edit UI paths exist in [MarkerActivity.kt](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/java/com/aitech/weartak_civ/presentation/MarkerActivity.kt):
- `edit_marker_title/{index}` route calls `viewModel.requestChangeMarkerTitle(uid, newTitle)`.
- Edit marker screen supports `Move to Current Location` and calls `viewModel.requestUpdateMarkerLocation(uid)`.
- ViewModel passthrough exists in [MarkerViewModel.kt](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/java/com/aitech/weartak_civ/presentation/viewmodel/MarkerViewModel.kt).
- Backend mutation methods exist in [DataRepository.kt](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt):
- `requestChangeMarkerTitle(uid, title)` updates `_droppedMarkerList` in memory.
- `requestUpdateMarkerLocation(uid)` snapshots current location and updates `_droppedMarkerList` in memory.
- Marker outbound sending is centralized in [CotDispatchService.kt](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/java/com/aitech/weartak_civ/service/CotDispatchService.kt):
- New drops go through `markerDropRequests` -> `sendMarkerDropNow(req)` and produce CoT/BLE events.
- A `markerDropMap[uid]` snapshot cache tracks sent markers.
- `droppedMarkerList` collector currently logs updates (`logMarkerDropList`) but does not emit an explicit marker-update CoT when title/location edits occur.

## Section 3: Proposed Scope Boundaries (Will / Won't)
Will:
- Define backend contract for editing a dropped marker title and location by `uid`.
- Require edited marker data to immediately propagate to outbound paths (TAK CoT and BLE marker envelope when enabled) after a successful edit mutation.
- Queue marker edit sends when endpoints are unavailable, then flush queued edits when send preconditions are restored.
- Preserve marker `uid` on edit operations so edits target same marker identity.
- Accept title values exactly as provided by the frontend, including empty string.
- Keep implementation inside existing marker flow (`MarkerActivity` -> `MarkerViewModel` -> `DataRepository` -> `CotDispatchService`) without introducing new architecture.

Won't:
- Redesign marker subsystem, queueing model, or overall CoT dispatch architecture.
- Add new marker types, new UI screens, or non-marker feature work.
- Change unrelated service orchestration (`ReactiveServiceManager`) or non-marker data models.

## Section 4: Ambiguities and Clarifying Questions
Resolved clarifications from user:
1. Edit sends must occur immediately after edit mutation so other TAK devices receive updated title/location.
2. If endpoints are unavailable, edited marker events must queue similarly to dropped marker events and send later.
3. Title edits should accept whatever frontend sends, including empty string.
4. Location edit source remains existing current-watch-location flow for this ticket.

## Section 5: Implementation Readiness Checklist
- [x] Raw source ticket path recorded.
- [x] Marker edit architecture touchpoints identified with concrete repo files.
- [x] Scope boundaries drafted with explicit Will/Won't.
- [x] Blocking ambiguities captured as implementation-impacting questions.
- [x] User clarification received for edit send behavior and offline behavior.
- [x] User confirmation on title validation behavior.

## Section 6: Ticket Rewrite Summary
- Ticket rewrite has been updated in `agent-rules/tickets/WEAR-431/TICKET_REWRITE.md` with resolved dispatch, queueing, and title-validation decisions.
