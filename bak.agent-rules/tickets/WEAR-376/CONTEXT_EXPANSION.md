# Ticket Intent Summary
- Ticket: `WEAR-376`
- Resolved raw source ticket: `agent-rules/tickets/WEAR-376/TICKET.md`
- Objective: add automatic bloodhound chat replies and route the user into the issuer chat flow when acknowledging or completing a bloodhound.
- Core behaviors:
- First `RGR` on a bloodhound sends `Roger` to the issuer, seeds the issuer chat room locally if needed, dispatches the message outward, and navigates into the issuer's chat thread with the normal chat back stack.
- Later `RGR` presses used only to switch active bloodhounds do not send another `Roger`.
- `NPOS` sends `In Position`, dispatches it outward, and routes into the same issuer chat flow.
- A locally dropped marker that reflects back from TAK infrastructure must not be surfaced as a bloodhound item.
- Delivery priority: TAK-complete behavior is required; SITX should be supported too if straightforward within the same patterns.

# Current Repo Context
- Raw ticket source states: `On accepting bloodhound map item, send "Roger" via Chat to sender and on completing a bloodhound send "In Position" to sender` and `Make sure the chat replies show up on the sender chat room.`
- Bloodhound UI actions live in `app/src/main/java/com/aitech/weartak_civ/presentation/BloodhoundActivity.kt`.
- The `RGR` button currently calls `bhRepo.requestActivateBloodhound(item.uid)`.
- The `NPOS` button currently calls `bhRepo.requestInPosition(item.uid)`.
- Bloodhound state and issuer metadata live in `app/src/main/java/com/aitech/weartak_civ/data/BloodhoundRepository.kt`.
- `BloodhoundEvent` already carries `server`, `issuer`, `timeIssued`, and `timeCompleted`.
- `BloodhoundItem.Origin` derives `issuerUid`, which is the current best identity anchor for direct reply targeting.
- Bloodhound activation and completion flows are handled in `app/src/main/java/com/aitech/weartak_civ/service/BloodhoundService.kt`.
- That service currently sets active bloodhounds and removes in-position bloodhounds, but does not send chat or perform chat navigation.
- Chat send requests flow through `app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt` and `app/src/main/java/com/aitech/weartak_civ/service/ChatService.kt`.
- `ChatService` currently only sends if the room already exists in its in-memory `chatRoomMap`.
- Outbound chat transport goes through `app/src/main/java/com/aitech/weartak_civ/service/CotDispatchService.kt`.
- Bloodhound marker ingest currently occurs in both `app/src/main/java/com/aitech/weartak_civ/service/TAKConnectService.kt` and `app/src/main/java/com/aitech/weartak_civ/service/SitxTakService.kt`, where parsed markers are turned into `BloodhoundEvent` candidates.
- Local marker drops are already tracked by UID in `app/src/main/java/com/aitech/weartak_civ/service/CotDispatchService.kt` via `markerDropMap` and mirrored in repository-facing dropped marker state, which provides the current repo-native source for filtering reflected markers.
- Existing canned reply text already exists in `app/src/main/res/raw/chat_reply_text_buttons_config` with `Roger` and `In Position`.

# Proposed Scope Boundaries (Will / Won't)
## Will
- Send `Roger` only on first acknowledgement of a bloodhound.
- Suppress `Roger` when `RGR` is used later only to switch active bloodhounds.
- Send `In Position` on `NPOS`.
- Seed the issuer's direct chat room locally when missing so the outgoing reply is visible in that room.
- Dispatch the reply through the existing chat and COT pipeline.
- Route the user into the issuer chat screen after first `RGR` and after `NPOS`.
- Preserve the expected chat back stack so swiping back exits through the normal chat flow instead of an incorrect bloodhound path.
- Filter reflected local marker UIDs from bloodhound ingestion.
- Deliver TAK-complete behavior and include SITX support if it stays low-complexity.

## Won't
- Send repeated `Roger` messages on every bloodhound switch.
- Add configurable reply text or new reply types.
- Redesign the chat subsystem beyond the minimal room-seeding support required here.
- Redesign marker-processing architecture beyond the local reflection filter.

# Ambiguities and Clarifying Questions
- Clarification resolved: if the issuer chat room does not exist, the app should seed the room locally and still dispatch the message through the existing TAK/COT path.
- Clarification resolved: this behavior currently applies to bloodhound `RGR` and `NPOS` actions only, but the implementation should be placed so other bloodhound entry points can reuse it later.
- Clarification resolved: TAK support is mandatory; SITX support should be included if straightforward.
- Clarification resolved: first `RGR` sends and routes to chat, later `RGR` presses used only for switching do not send again, and `NPOS` also routes into the issuer chat flow.
- No blocking ambiguity remains for context expansion.

# Implementation Readiness Checklist
- Ticket objective is explicit and testable.
- Trigger conditions are explicit:
- First `RGR` sends `Roger`.
- Switch `RGR` does not send.
- `NPOS` sends `In Position`.
- Chat-routing behavior is explicit:
- Route to issuer chat after first `RGR`.
- Route to issuer chat after `NPOS`.
- Preserve normal chat back stack behavior.
- Local room seeding is explicitly approved.
- Reflected-marker filtering requirement is explicitly approved.
- Repo touchpoints are identified for bloodhound UI, bloodhound service/repository, chat service/repository, COT dispatch, and TAK/SITX ingest.
- Remaining risk is limited to whether SITX issuer/chat-room mapping can be handled without source-specific complexity.

# Ticket Rewrite Summary
- `TICKET_REWRITE.md` was prepared as the implementation-ready ticket contract for `WEAR-376`.
- The rewrite keeps scope tight to:
- first-ack `Roger`
- switch suppression
- `NPOS` send
- issuer chat routing and back stack behavior
- reflected-marker filtering
- TAK-complete delivery with opportunistic SITX parity
- No code changes were made in this phase.
- Next gate: user review and explicit approval token `CONTEXT_APPROVED`.
