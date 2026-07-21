# Title
Auto-send bloodhound chat replies, route into issuer chat, and ignore reflected local markers

# Problem / Why
Bloodhound acceptance and completion do not currently send the expected status chat back to the issuer, and the workflow does not transition into the related chat conversation. In addition, markers dropped locally can reflect back from TAK infrastructure and be misparsed as bloodhounds.

# User impact
On first acknowledgement of a bloodhound, the user should send `Roger` to the issuer and be taken directly into that issuer's chat thread. If the user later switches to another bloodhound using `RGR`, the app should not send another `Roger`. When the user taps `NPOS`, the app should send `In Position` and route into the same issuer chat flow. Reflected local markers must not create false bloodhound entries.

# In-scope
- First `RGR` sends `Roger`, seeds the room if needed, dispatches outward, and routes into issuer chat.
- Later `RGR` presses used only to switch active bloodhounds do not send another reply.
- `NPOS` sends `In Position`, dispatches outward, and routes into issuer chat.
- Chat navigation preserves the expected back stack behavior from that chat flow.
- Filter locally dropped marker UIDs from bloodhound ingestion.
- Ship TAK-complete behavior and support SITX too if straightforward within existing patterns.

# Out-of-scope
- Configurable reply text.
- Additional bloodhound reply types.
- Broad chat subsystem redesign.
- General marker-processing redesign beyond reflected-local-marker filtering.

# Functional requirements
- First acknowledgement of a bloodhound produces exactly one `Roger` reply to that issuer.
- Switching bloodhounds via later `RGR` actions does not send duplicate `Roger` messages.
- `NPOS` produces exactly one `In Position` reply to that issuer.
- If the issuer chat room does not yet exist locally, the app seeds it so the outgoing message is visible there.
- After first `RGR`, the app navigates to the issuer's chat screen using the normal chat back stack.
- After `NPOS`, the app navigates to the issuer's chat screen using the normal chat back stack.
- Marker events whose UID matches a locally dropped marker are not surfaced as bloodhound items.

# Non-functional constraints
- Follow existing `BloodhoundRepository`, `BloodhoundService`, `DataRepository`, `ChatService`, `TAKConnectService`, `SitxTakService`, and `CotDispatchService` patterns.
- Keep the change narrowly scoped and avoid architectural redesign.
- Prefer service/repository-level trigger wiring so future bloodhound entry points can reuse the same behavior.
- TAK support is required for this ticket; SITX support should be included if it remains low-complexity and does not destabilize the TAK path.

# Acceptance criteria
- Given a newly received bloodhound, when the user taps `RGR` for the first time, the app sends `Roger`, shows it in the issuer chat room, dispatches it outward, and opens that chat thread.
- Given an already-acknowledged bloodhound switch, when the user taps `RGR`, the active bloodhound changes without sending another `Roger`.
- Given an active bloodhound, when the user taps `NPOS`, the app sends `In Position`, shows it in the issuer chat room, dispatches it outward, and opens that chat thread.
- Swiping back from the opened chat follows the normal chat back stack rather than returning through an incorrect bloodhound detail path.
- A locally dropped marker reflected back from TAK infrastructure is not added as a bloodhound item.
- SITX-originated bloodhounds follow the same reply behavior if issuer/chat-room targeting can be handled with the same repo patterns without material extra complexity.

# Dependencies / risks
- `ChatService` currently only sends when a chat room already exists in its in-memory room map, so local room seeding is required for this ticket.
- Bloodhound ingestion currently treats parsed markers as bloodhound candidates in both `TAKConnectService` and `SitxTakService`, so the reflected-marker filter must be applied at ingest.
- SITX support may require source-specific issuer/chat-room mapping; if that path proves non-trivial, TAK-complete behavior takes priority.
