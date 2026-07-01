# WEAR-318: Ditto community-default credentials with user Big Peer app-id override

## Title
Ditto defaults to community EdgeSync credentials; allow user-entered Big Peer app ID in Settings

## Problem / Why
Users should not need to manually configure Ditto for the standard/community path. Users with Big Peer entitlement still need a local way to enter their app-specific value.

## User impact
- Standard users: Ditto credential reads should resolve to a working community fallback path without manual app-id/token entry.
- Big Peer users: can enter a Big Peer license-equivalent app ID in Settings and persist it locally.

## In-scope
- Keep Ditto service default OFF.
- Add a user-editable Settings field for Big Peer license mapped to `ditto_app_id`.
- Persist and reuse that value for Ditto initialization.
- Treat blank app-id value as fallback to community defaults.
- Keep current MDM behavior and precedence unchanged.

## Out-of-scope
- Changing Ditto service default ON/OFF behavior.
- Redesigning config precedence across MDM/local/default.
- Adding new backend validation APIs for Big Peer credentials.
- Refactoring unrelated licensing or service architecture.

## Functional requirements
1. Settings UI exposes a dedicated Big Peer app-id entry path.
2. Confirming settings persists the entered value to repository key `ditto_app_id`.
3. On Ditto credential reads, blank/missing app ID resolves to built-in community default value.
4. Existing Ditto token behavior remains, including fallback behavior.
5. Existing MDM updates for Ditto app ID/token continue to apply exactly as today.

## Non-functional constraints
- Minimal diff; mirror existing Settings + DataRepository patterns.
- No changes to service orchestration architecture.
- No changes to Ditto default service enable state.

## Acceptance criteria
1. Fresh install keeps `enable_ditto_service = false` by default.
2. Settings includes a Big Peer app-id editor reachable from main settings flow.
3. Saving a non-empty Big Peer app ID persists and is visible after app restart.
4. Saving/holding an empty app ID results in community fallback app ID during Ditto initialization.
5. Existing MDM-driven Ditto app-id/token updates still persist and are consumed at startup.

## Dependencies / risks
- Authoritative community EdgeSync credential values are not yet supplied; this implementation uses existing built-in defaults as the constrained fallback source.
- If product later provides different community credentials, constants should be updated in one place.
