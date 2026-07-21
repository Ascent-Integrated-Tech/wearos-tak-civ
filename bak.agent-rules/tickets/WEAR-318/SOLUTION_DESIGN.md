## Section 1: Problem and Success Definition
- Problem: WEAR-318 requires Ditto to use community EdgeSync defaults by default while allowing users with Big Peer entitlement to enter a license-equivalent override.
- Success definition:
  - Ditto service default enable state remains OFF.
  - User can edit Big Peer app ID from Settings.
  - Ditto app-id read path falls back to community defaults when blank/missing.
  - Existing MDM behavior remains unchanged.
- Resolved raw ticket path: `agent-rules/tickets/WEAR-318/WEAR-318.md`.

## Section 2: Current-State Repo Findings
- Ditto ON/OFF default currently controlled by `DEFAULT_ENABLE_DITTO_SERVICE=false` in [`app/src/main/java/com/aitech/weartak_civ/Constants.kt`](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/java/com/aitech/weartak_civ/Constants.kt:19).
- Ditto identity startup uses repository `getDittoAppId` and `getDittoAuthToken` in [`app/src/main/java/com/aitech/weartak_civ/service/DittoService.kt`](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/java/com/aitech/weartak_civ/service/DittoService.kt:491).
- Ditto app-id/token persistence already exists in [`app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt`](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt:1306).
- Settings currently has Ditto toggle but no dedicated Big Peer app-id editor in [`app/src/main/java/com/aitech/weartak_civ/presentation/SettingsActivity.kt`](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/java/com/aitech/weartak_civ/presentation/SettingsActivity.kt:4530).
- MDM already writes Ditto app-id/token keys in [`app/src/main/java/com/aitech/weartak_civ/presentation/MainActivity.kt`](/Users/tommykaufmann/work-ascent/code/wearos-tak-civ/app/src/main/java/com/aitech/weartak_civ/presentation/MainActivity.kt:1951).

## Section 3: Key Decisions Captured
- Ditto default enable state: unchanged (OFF).
- Big Peer “license” value maps to `ditto_app_id`.
- Precedence behavior between MDM/local/default: unchanged from existing app behavior.
- Blank/invalid Big Peer entry handling: fallback to default community values.
- Community credentials are not yet externally confirmed; this ticket will use current built-in fallback values.

## Section 4: Solution Boundaries (Will / Won't)
Will:
- Add Settings UI route/chip for Big Peer app-id entry.
- Persist Big Peer app-id to existing `ditto_app_id` key.
- Update Ditto credential fallback reads to treat blank values as default community credentials.
- Keep Ditto token path and service orchestration unchanged.

Won't:
- Won't change MDM restrictions schema or precedence semantics.
- Won't change Ditto default ON/OFF toggle baseline.
- Won't add server-side validation or new entitlement APIs.
- Won't refactor unrelated licensing or settings architecture.

## Section 5: Implementation Contract
- Component touchpoints:
  - `Constants.kt`: define named community fallback constants for Ditto app-id/token (using current built-ins).
  - `DataRepository.kt`: update `getDittoAppId`/`getDittoAuthToken` to fallback for blank-or-missing values.
  - `SettingsActivity.kt`:
    - add UI navigation entry for Big Peer app-id editing.
    - add editor screen/route backed by existing settings state patterns.
    - save app-id changes via existing repository API on settings confirm.
  - `strings.xml`: add user-facing labels for Big Peer app-id setting.
- Data model behavior:
  - No new datastore keys.
  - Existing `ditto_app_id` key reused for Big Peer override.
- Permission/config impact:
  - none.
- Verification strategy:
  - Build `assembleDebug` and `assembleRelease`.
  - Manual behavior checks:
    - blank app ID -> Ditto reads fallback default.
    - non-empty app ID -> persisted override used.
    - Ditto service default remains OFF.
- Safe disable/rollback:
  - reverting UI/storage changes restores prior behavior; no migration required.

## Section 6: Open Risks and Follow-ups
- Risk: fallback constants may not match future authoritative community EdgeSync values.
- Follow-up: once official community credentials are provided, update constants only (no behavior redesign needed).
