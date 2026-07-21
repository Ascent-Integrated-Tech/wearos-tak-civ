# Problem and Success Definition
- Ticket: `WEAR-376`
- Resolved raw source ticket: `agent-rules/tickets/WEAR-376/TICKET.md`
- Objective: implement automatic bloodhound chat replies and the associated chat-navigation flow without broad architecture changes.
- Success signals:
- First `RGR` on a bloodhound sends exactly one `Roger`, shows it in the issuer chat room locally, dispatches it through the existing outbound chat pipeline, and opens that chat thread.
- Later `RGR` presses used only to switch active bloodhounds do not send another `Roger`.
- `NPOS` sends exactly one `In Position`, shows it in the issuer chat room locally, dispatches it outward, and opens that chat thread.
- Swiping back from the opened chat follows the normal chat flow rather than an incorrect bloodhound-detail path.
- Marker events whose UID matches a locally dropped marker do not create bloodhound items.
- TAK support is complete; SITX support is included if it can reuse the same issuer/chat-room mapping with low additional complexity.

# Current-State Repo Findings
- Bloodhound action entry points:
- `app/src/main/java/com/aitech/weartak_civ/presentation/BloodhoundActivity.kt` currently triggers `bhRepo.requestActivateBloodhound(item.uid)` from `RGR` and `bhRepo.requestInPosition(item.uid)` from `NPOS`.
- Bloodhound state:
- `app/src/main/java/com/aitech/weartak_civ/data/BloodhoundRepository.kt` already carries issuer metadata in `BloodhoundEvent.issuer`, `BloodhoundEvent.server`, and `BloodhoundItem.Origin.issuerUid`.
- `BloodhoundRepository` already owns activation and in-position state transitions, making it the natural place to distinguish first acknowledge vs switch behavior.
- Bloodhound execution path:
- `app/src/main/java/com/aitech/weartak_civ/service/BloodhoundService.kt` currently listens to request flows and applies `setActive()` / `setInPos()`.
- This is the best current repo-pattern location for reusable action-side effects because future bloodhound entry points can trigger the same repository flows.
- Chat send path:
- `app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt` exposes `requestChatMessageSendFromUI()` and `sendChatMessage()`.
- `app/src/main/java/com/aitech/weartak_civ/service/ChatService.kt` collects `chatRequestFromUI`, appends the outgoing message to an in-memory room, publishes the room list to `DataRepository`, then forwards to `CotDispatchService`.
- Current limitation: `ChatService` only sends when a room already exists in `chatRoomMap`.
- Chat navigation:
- `app/src/main/java/com/aitech/weartak_civ/presentation/ChatActivity.kt` displays rooms from `chatRoomsFlow`.
- Detailed chat navigation currently uses `chat_screen_detailed/{index}`, so room opening depends on the current sorted room list position.
- That index-based route is not stable enough for direct cross-feature navigation after room seeding unless a room lookup step or UID-based route is added.
- Reflected marker bug:
- `app/src/main/java/com/aitech/weartak_civ/service/TAKConnectService.kt` and `app/src/main/java/com/aitech/weartak_civ/service/SitxTakService.kt` currently convert any parsed marker into a `BloodhoundEvent`.
- `app/src/main/java/com/aitech/weartak_civ/service/CotDispatchService.kt` already tracks locally dropped marker UIDs in `markerDropMap`, and `DataRepository.droppedMarkerList` mirrors that state for consumers outside the dispatch service.

# Key Decisions Captured
- `approved`: First `RGR` sends `Roger`; later `RGR` used only to switch active bloodhounds does not send another `Roger`.
- `approved`: `NPOS` sends `In Position`.
- `approved`: If the issuer chat room does not exist locally, seed it locally and still dispatch the outgoing message through the existing chat/COT pipeline.
- `approved`: After first `RGR` and after `NPOS`, route the user into the issuer chat screen with the proper chat back stack behavior.
- `approved`: TAK support is required.
- `approved`: SITX should be supported only if it is straightforward inside the same implementation pattern.
- `approved`: Reflected locally dropped markers must be filtered out of bloodhound ingest.
- `proposed`: Distinguish first acknowledge vs switch in `BloodhoundRepository` / `BloodhoundService`, not in `BloodhoundActivity`, so future bloodhound entry points inherit the same behavior.
- `proposed`: Add a minimal chat-room seeding API in `ChatService` or `DataRepository` that can create/update a room by issuer UID and callsign before sending.
- `proposed`: Replace or supplement index-based direct-room opening in `ChatActivity` with a UID-based room-open path so bloodhound can navigate to the correct room deterministically after seeding.
- `deferred`: Full SITX parity is included only if issuer/chat-room targeting is available with the same metadata shape as TAK. If not, the implementation should ship TAK-complete and record SITX as a follow-up.

# Solution Boundaries (Will / Won't)
## Will
- Add first-ack detection so `Roger` is sent only when a bloodhound transitions from not-active/not-acknowledged into the initial acknowledged-active state.
- Preserve bloodhound switching without duplicate `Roger` sends.
- Send `In Position` when the active bloodhound is completed.
- Seed or update the issuer's direct chat room locally before appending the outgoing reply.
- Reuse the existing `DataRepository -> ChatService -> CotDispatchService` outbound chat path.
- Add a stable way to open the issuer chat thread after seeding and send.
- Filter bloodhound ingest when the marker UID already exists in locally dropped marker state.
- Apply the ingest filter in TAK and, if straightforward, SITX.

## Won't
- Introduce a new chat architecture or rewrite inbox storage.
- Add configurable bloodhound reply text, new reply actions, or broader workflow redesign.
- Redesign all marker parsing; only local reflection filtering is in scope.
- Block TAK implementation on SITX-specific complexity.

# Implementation Contract
- Bloodhound state changes:
- Extend `BloodhoundRepository` state tracking so the service can determine whether a given `requestActivateBloodhound(uid)` is a first acknowledgement or just a switch.
- Practical options that fit current patterns:
- persist `acknowledged` on the `BloodhoundItem.state` already present in the repository model, or
- derive first-ack from existing active/acknowledged state before calling `setActive`.
- Preferred direction: update `BloodhoundRepository` APIs so the service receives enough state to perform `activateIfNeededAndReturnTransition`.
- Bloodhound side effects:
- In `BloodhoundService`, augment activation and in-position collectors:
- activation path:
- if first acknowledgement, seed/send `Roger`, then publish a navigation request for issuer chat.
- if only switching active target, update active state without sending chat.
- in-position path:
- send `In Position`, then publish a navigation request for issuer chat, then clear/remove the bloodhound.
- Keep the UI button handlers thin so the business rule is owned by service/repository state, not Compose code.
- Chat room seeding:
- Add a minimal room upsert path reachable from bloodhound-side code.
- Required behavior:
- create a room keyed by issuer UID if absent.
- use issuer callsign/title from bloodhound metadata.
- append the outgoing local message so the room visibly contains `Roger` / `In Position`.
- publish the updated room list to `DataRepository`.
- Avoid duplicating send logic in multiple places; keep actual outward send in the existing chat pipeline.
- Chat dispatch:
- Reuse `DataRepository.requestChatMessageSendFromUI()` and existing `ChatService` dispatch behavior where possible.
- If `ChatService` remains the owner of local room append + outward dispatch, add a public/internal entry point that can seed the room before the standard send request is processed.
- Ensure only one local message append occurs per user action.
- Chat navigation and back stack:
- Add a stable room-open mechanism in `ChatActivity`.
- Preferred direction:
- support a route or launch extra keyed by room UID rather than list index.
- on activity launch, resolve the room by UID from `chatRoomsFlow` and navigate first to `chat_screen`, then to the matching detailed room.
- This keeps the opened thread aligned with the real room even if inbox sorting changes after seeding/sending.
- Bloodhound can then launch `ChatActivity` with the issuer room UID after first `RGR` and after `NPOS`.
- Back behavior should then fall through the existing chat navigation stack instead of back into a stale bloodhound-detail route.
- Reflected marker filtering:
- In `TAKConnectService`, before converting a parsed marker into `BloodhoundEvent`, check whether its UID exists in `DataRepository.droppedMarkerList` and skip bloodhound creation if it does.
- Apply the same guard in `SitxTakService` only if the same UID and repository access pattern is available without extra complexity.
- Use repository-visible dropped-marker state instead of reaching into `CotDispatchService.markerDropMap` directly, because the service-owned map is not a shared API and `DataRepository.droppedMarkerList` already mirrors the required UID set.
- Failure handling:
- If issuer UID or title metadata is missing for a bloodhound, do not send an auto-reply. Log and continue with bloodhound activation/completion.
- If chat-room seeding succeeds locally but no transport is connected, rely on the existing pending/outbound behavior in `CotDispatchService`; do not invent a second queue.
- If stable room-open cannot resolve the room immediately on launch, land in the chat inbox screen instead of failing the activity.
- Verification strategy:
- Manual verification on TAK path:
- receive a bloodhound and tap first `RGR`: confirm one `Roger`, correct room, correct chat launch.
- switch to another bloodhound via `RGR`: confirm no duplicate `Roger`.
- tap `NPOS`: confirm one `In Position`, correct room, correct chat launch.
- swipe back from the opened thread: confirm normal chat back stack.
- drop a local marker and observe reflected traffic: confirm no false bloodhound entry is created.
- Manual verification on SITX path only if the issuer metadata resolves through the same path without extra branching.

# Open Risks and Follow-ups
- SITX parity may require source-specific issuer/chat-room extraction. If that proves non-trivial, defer SITX-specific reply routing while keeping TAK-complete behavior.
- `ChatActivity` is currently index-based for room detail routing, which is brittle for cross-feature deep links. The implementation should keep the fix minimal and scoped to stable room opening by UID.
- There may already be partial local `WEAR-376` work in the user worktree. During implementation, those changes need to be reviewed carefully rather than overwritten.

# Approval Status
- Design status: ready for user review.
- Unresolved items:
- no blocking item for TAK implementation.
- SITX remains intentionally conditional: implement if straightforward, otherwise defer explicitly.
- Awaiting explicit approval token: `APPROVED FOR IMPLEMENTATION`
