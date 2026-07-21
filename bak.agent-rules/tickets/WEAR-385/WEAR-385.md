# WEAR-385: Monolithic Encrypted Storage for TAK Server List

## Branch

agent/WEAR-385

## Summary

Replace plaintext persistence of the monolithic TAK server list JSON
with encrypted-at-rest storage using the existing Tink AEAD +
EncryptedSerializer pattern. Store the entire JSON blob as a single
encrypted Base64 string in a dedicated secure DataStore. Maintain
existing sync behavior and canonical serialization on watch.

## Success criteria

-   [ ] TAK server list JSON is no longer stored in plaintext
    preferences
-   [ ] Encrypted blob stored under key `tak_server_list_json`
-   [ ] Existing installs migrate plaintext → encrypted store
    successfully
-   [ ] Legacy plaintext write path commented out (not deleted)
-   [ ] All provisioning, BLE sync, and canonical watch behavior remains
    unchanged
-   [ ] No per-item indexing or partial encryption introduced

## Files to edit (expected)

-   app/src/main/java/.../DataRepository.kt
-   app/src/main/java/.../TakServerMigration.kt (or existing
    initialization path)
-   app/src/main/AndroidManifest.xml (only if migration hook requires
    it)

## Files to add (expected)

-   app/src/main/java/com/aitech/weartak_civ/data/secure/takserver/TakServerSecretsKeys.kt
-   app/src/main/java/com/aitech/weartak_civ/data/secure/takserver/TakServerEncryptedDataStore.kt
-   app/src/main/java/com/aitech/weartak_civ/data/secure/takserver/TakServerSecretsRepository.kt

## Constraints

-   No new architecture patterns without stopping and asking
-   No guessing imports/package names
-   Must use existing:
    -   AppKeyManager
    -   CryptoConfig
    -   EncryptedSerializer.encryptToBase64(...)
    -   EncryptedSerializer.decryptFromBase64(...)
-   Use SITX secure folder conventions
-   Use topic-specific AEAD keyset (e.g., "takserver")
-   Use topic-specific AAD constant
-   Minimize diffs
-   Comment out legacy plaintext key usage (do not delete yet)

## Design (approved)

### Storage Model

-   Single monolithic JSON string
-   Entire JSON encrypted using AEAD
-   Stored as Base64 ciphertext in Preferences DataStore
-   Key: `tak_server_list_json`
-   DataStore name: `takserver_secrets`

### Encryption Flow

1.  Serialize `List<TakServerItem>` → JSON via Gson
2.  Encrypt JSON using:
    -   AEAD from AppKeyManager (TakServer-specific keyset)
    -   Topic-specific AAD (e.g., "weartak:taksever:list:v1")
3.  Store Base64 ciphertext in encrypted DataStore

### Decryption Flow

1.  Read Base64 ciphertext
2.  Decrypt via `EncryptedSerializer.decryptFromBase64(...)`
3.  Return plaintext JSON to calling components

### Migration Behavior

On startup or first server list access: 1. Check secure key
`tak_server_list_json` 2. If present → do nothing 3. If missing: - Read
legacy `TAK_SERVER_LIST_KEY` plaintext - If present: - Write to secure
store - Clear legacy key 4. Comment out legacy write path in
DataRepository

### DataRepository Changes

-   Replace plaintext writes with:
    -   `takServerSecretsRepository.setTakServerListJson(jsonString)`
-   Keep old DataStore write commented out
-   All add/update/remove operations:
    1.  Read decrypted JSON
    2.  Deserialize to list
    3.  Modify
    4.  Serialize
    5.  Store encrypted monolith

### BLE Sync Behavior

-   Watch remains canonical serializer
-   BLE sends plaintext JSON derived from decrypted store
-   Companion remains networking-pass-through only
-   No schema changes required

## Notes / links

-   Jira: WEAR-385
-   Encryption utility: EncryptedSerializer (existing)
-   Crypto pattern reference: SITX secure module
-   Secure folder reference: com/aitech/weartak_civ/data/secure
