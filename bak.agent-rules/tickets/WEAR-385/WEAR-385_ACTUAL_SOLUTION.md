# WEAR-385 Actual Solution (As Implemented)

## Summary
- Replaced plaintext storage of the TAK server list JSON with encrypted-at-rest storage using the existing Tink AEAD + `EncryptedSerializer` pattern.
- Stored the entire JSON blob as a single encrypted Base64 string under key `tak_server_list_json` in a dedicated `takserver_secrets` Preferences DataStore.
- Preserved the legacy plaintext path (commented out only) and added migration to move existing plaintext data into the encrypted store.
- Maintained watch-side canonical serialization and BLE sync behavior.

## Final Architecture (Actual)
- **Secure storage domain:** `com.aitech.weartak_civ.data.secure.takserver`
- **DataStore:** `takserver_secrets` (Preferences DataStore)
- **Key:** `tak_server_list_json`
- **AEAD keyset:** `takserver_aead_keyset`
- **Keystore alias:** `weartak.takserver.master.v1`
- **AAD:** `weartak:takserver:list:v1`

## Repo Initialization Pattern (Aligned to SITX)
- `WearTakApplication` owns the secure repo, consistent with `sitxRepo`:
  - `takServerRepo = TakServerSecretsRepository(TakServerEncryptedDataStore(applicationContext))`
- `DataRepository` accesses the repo via the application instance:
  - `(context.applicationContext as WearTakApplication).takServerRepo`
- This avoids multiple active DataStore instances and aligns with the SITX secure initialization pattern.

## Migration Behavior (Actual)
- Migration runs from `WearTakApplication` after repos are initialized.
- Migration reads legacy plaintext `tak_server_list` from the existing `data` DataStore via `DataRepository.legacyDataStore()`.
- If present, it writes the plaintext JSON into the encrypted store and removes the legacy key.

## DataRepository Changes (Actual)
- All TAK server list operations now:
  1) Read decrypted JSON from the secure store
  2) Deserialize to list
  3) Modify list
  4) Serialize
  5) Store encrypted monolithic JSON
- Legacy plaintext write path is kept but commented out (not deleted).
- The existing Flow `getTakServerList` remains as a `val` and continues to publish list changes.

## Logging
- Temporary WT/CDX log tags were removed.
- Logging now uses existing file TAGs (where present); logs without an existing TAG were removed.

## Files Added
- `app/src/main/java/com/aitech/weartak_civ/data/secure/takserver/TakServerSecretsKeys.kt`
- `app/src/main/java/com/aitech/weartak_civ/data/secure/takserver/TakServerEncryptedDataStore.kt`
- `app/src/main/java/com/aitech/weartak_civ/data/secure/takserver/TakServerSecretsRepository.kt`
- `app/src/main/java/com/aitech/weartak_civ/data/secure/migration/TakServerMigration.kt`

## Files Modified
- `app/src/main/java/com/aitech/weartak_civ/data/DataRepository.kt`
- `app/src/main/java/com/aitech/weartak_civ/WearTakApplication.kt`
- `app/src/main/java/com/aitech/weartak_civ/service/CompanionAppService.kt`

## Builds Run
- `./gradlew clean :app:assembleDebug`
