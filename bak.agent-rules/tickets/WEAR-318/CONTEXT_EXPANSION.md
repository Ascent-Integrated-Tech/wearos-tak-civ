## Section 1: Ticket Intent Summary
- Ticket source intent (from `agent-rules/tickets/WEAR-318/WEAR-318.md`): make Ditto default to community EdgeSync behavior, while allowing users with Big Peer entitlement to enter license-equivalent settings.
- Objective: keep Ditto OFF by default but ensure its credential fallback path is community defaults, and provide a user-editable Big Peer app-id input path.
- Actors:
  - WearTAK-CIV end user using on-watch Settings.
  - Managed-config admin (existing MDM restriction path, unchanged by this ticket).
- Resolved source ticket path: `agent-rules/tickets/WEAR-318/WEAR-318.md`.

## Section 2: Current Repo Context
- Raw ticket fallback file is used (`TICKET.md` missing, `WEAR-318.md` present).
- Ditto enablement default is already OFF in [`app/src/main/java/com/aitech/weartak_civ/Constants.kt`](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/java/com/aitech/weartak_civ/Constants.kt:19).
- Ditto identity is currently initialized from repository values and uses `DittoIdentity.OnlinePlayground(...)` in [`app/src/main/java/com/aitech/weartak_civ/service/DittoService.kt`](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/java/com/aitech/weartak_civ/service/DittoService.kt:491).
- Ditto app ID/token persistence APIs already exist in [`app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt`](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt:1306).
- MDM restriction keys for Ditto values and Ditto toggle already exist in [`app/src/main/res/xml/app_restrictions.xml`](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/res/xml/app_restrictions.xml:33) and are applied in [`app/src/main/java/com/aitech/weartak_civ/presentation/MainActivity.kt`](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/java/com/aitech/weartak_civ/presentation/MainActivity.kt:1666).
- Current Settings UI exposes a Ditto ON/OFF toggle but no dedicated Big Peer app-id input chip in [`app/src/main/java/com/aitech/weartak_civ/presentation/SettingsActivity.kt`](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/java/com/aitech/weartak_civ/presentation/SettingsActivity.kt:4530).

## Section 3: Proposed Scope Boundaries (Will / Won't)
Will:
- Keep Ditto service default OFF (no change to enable toggle default).
- Implement a Settings input path for Big Peer license-equivalent value where license maps to Ditto App ID.
- Preserve current precedence/override behavior between local settings and MDM (no precedence redesign).
- Ensure blank/invalid user Big Peer entry falls back to community defaults at read/use time.

Won't:
- Won't alter Ditto service orchestration/conflict handling (`ReactiveServiceManager` behavior remains unchanged).
- Won't introduce new MDM keys or modify MDM precedence logic.
- Won't add online validation for Big Peer credentials.
- Won't change broader app licensing/gating flows unrelated to Ditto App ID entry.

## Section 4: Ambiguities and Clarifying Questions
Resolved clarifications from user:
- Community credentials exact values are currently unknown to requester; implementation should keep current built-in defaults as fallback source for this ticket.
- Ditto service default must remain OFF.
- Big Peer “license” setting should map to Ditto App ID.
- Precedence policy changes are out of scope; preserve existing app behavior.
- If Big Peer entry is blank/invalid, fallback must use defaults.

Remaining external dependency:
- Product/ops must provide authoritative community EdgeSync credential values if they differ from existing hardcoded defaults.

## Section 5: Implementation Readiness Checklist
- Ticket objective translated into concrete behavior: yes.
- Scope boundaries testable and explicit: yes.
- Critical decisions captured: yes.
- Blocking for code start: no (fallback source constrained to current built-in defaults).
- Acceptance criteria draft:
  - Ditto enable default remains OFF on clean install.
  - User can enter/edit Big Peer license-equivalent Ditto App ID from on-watch Settings.
  - Entered Big Peer app ID persists across app restart.
  - Blank app ID resolves to community fallback defaults when Ditto reads credentials.
  - Existing MDM override behavior for Ditto fields continues to function as-is.

## Section 6: Ticket Rewrite Summary
- Rewritten ticket will implement: (1) explicit community fallback behavior, (2) user-editable Big Peer app-id setting, (3) no change to Ditto ON/OFF default, and (4) no precedence redesign.
