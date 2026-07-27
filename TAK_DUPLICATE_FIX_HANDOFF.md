# TAK Duplicate Connection Fix — Implementation Handoff

## Purpose

This document records the duplicate TAK Server investigation and implementation path so the changes can be manually reapplied after stashing this work and pulling the latest `develop` branch.

The reported failure is:

1. Two saved rows represent the same configured TAK endpoint.
2. Both rows are enabled, so the connection service opens two TLS sockets.
3. The address-based certificate cache can cause both sockets to use the same client certificate/signature.
4. The same outgoing CoT can be written once per socket.
5. During startup migration, a saved list can temporarily appear empty. Managed configuration or UI code can then add a server that already exists.

The primary identity decision is:

> A TAK Server is unique by configured `address:port`.

Use the configured endpoint string. Do not resolve DNS or compare runtime IP addresses. The configured endpoint is the stable application-level identity.

## Objectives

### Required behavior

- Prevent two persisted entries with the same canonical `address:port`.
- Serialize all list mutations so concurrent duplicate checks cannot race.
- Reject duplicate add and edit-into-collision operations.
- De-duplicate bulk list replacement/import operations.
- Ensure migration completes before TAK consumers read or write the list.
- Treat decryption failure as unavailable, never as an empty writable list.
- Preserve UI/runtime indices; do not collapse rows in memory.
- Allow at most one active socket per endpoint as a runtime safety net.
- Show the compact watch dialog title: `Duplicate TAK Server`.

### Non-goals

- No changes to CoT XML generation.
- No changes to marker, PLI, alert, or UID generation.
- No assumptions about server-side TAK de-duplication.
- No DNS/IP resolution for identity comparison.
- No broad refactor from index-based EventBus messages to endpoint-based messages.

## Canonical endpoint identity

Add:

```text
app/src/main/java/com/aitech/weartak_civ/data/takserver/TakServerEndpointIdentity.kt
```

Recommended shape:

```kotlin
object TakServerEndpointIdentity {
    val mutationMutex = Mutex()

    fun key(address: String, port: Int): String {
        val normalizedAddress = address.trim()
            .removePrefix("[")
            .removeSuffix("]")
            .removeSuffix(".")
            .lowercase()

        val formattedAddress = if (normalizedAddress.contains(":")) {
            "[$normalizedAddress]"
        } else {
            normalizedAddress
        }

        return "$formattedAddress:$port"
    }

    fun key(item: DataRepository.TakServerItem): String =
        key(item.address, item.port)
}
```

Examples:

```text
tak.weartak.com:8089
TAK.WEARTAK.COM.:8089
 [tak.weartak.com]:8089
```

These should resolve to the same key. IPv6 must retain brackets:

```text
[2001:db8::1]:8089
```

The key intentionally excludes name, enabled state, username, password, and certificate. Two credential sets for one configured endpoint are one connection identity. Reject the duplicate; do not silently merge credentials.

## Persistence-layer changes

### Result types

```kotlin
enum class TakServerAddResult {
    ADDED,
    DUPLICATE
}

enum class TakServerUpdateResult {
    UPDATED,
    DUPLICATE,
    INVALID_INDEX
}
```

### Serialize every list mutation

The old read-modify-write was vulnerable:

```kotlin
val current = takServerListJson.first()
val list = parse(current)
list.add(item)
setTakServerListJson(serialize(list))
```

A duplicate check around this code is still TOCTOU-prone. All of the following must use the same process-wide mutex:

- `addTakServerItem`
- `updateTakServerItem`
- `removeTakServerItem`
- `storeTakServerList`
- legacy-to-encrypted migration

Pattern:

```kotlin
TakServerEndpointIdentity.mutationMutex.withLock {
    val currentList = readTakServerList()
    // Check, modify, and write while holding the lock.
}
```

### Add

```kotlin
suspend fun addTakServerItem(item: TakServerItem): TakServerAddResult {
    awaitTakServerMigration()
    return TakServerEndpointIdentity.mutationMutex.withLock {
        val currentList = readTakServerList()
        val normalizedItem = normalizeTakServerItem(item)
        val duplicate = currentList.any {
            TakServerEndpointIdentity.key(it) ==
                TakServerEndpointIdentity.key(normalizedItem)
        }

        if (duplicate) {
            return@withLock TakServerAddResult.DUPLICATE
        }

        currentList.add(normalizedItem)
        writeTakServerList(currentList, "addTakServerItem")
        TakServerAddResult.ADDED
    }
}
```

Return `DUPLICATE` before writing, posting EventBus messages, or connecting.

### Update/edit collision

An edit must reject a collision with a different row:

```kotlin
val duplicateIndex = currentList.indexOfFirst {
    TakServerEndpointIdentity.key(it) ==
        TakServerEndpointIdentity.key(normalizedItem) &&
        it !== currentList[oldItemIndex]
}

if (duplicateIndex >= 0) {
    return@withLock TakServerUpdateResult.DUPLICATE
}
```

The self-row exclusion allows an unchanged endpoint to be edited normally.

### Remove by endpoint key

Do not use full data-class equality. Normalization can change certificate/password fields between the UI object and the freshly-read persisted object.

```kotlin
val endpointKey = TakServerEndpointIdentity.key(item)
currentList.removeAll {
    TakServerEndpointIdentity.key(it) == endpointKey
}
```

### Bulk replacement/import

Every whole-list write must normalize and de-duplicate:

```kotlin
val uniqueList = normalizeTakServerList(newList)
    .distinctBy(TakServerEndpointIdentity::key)
```

This protects companion imports and other bulk replacement paths.

## Migration and read readiness

Migration previously ran fire-and-forget while UI and services performed one-shot reads. A reader could see an empty list before the legacy list had been copied.

Use a non-blocking readiness gate in `WearTakApplication`:

```kotlin
private val takServerMigrationReady =
    CompletableDeferred<Result<Unit>>()

CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
    val result = runCatching {
        TakServerMigration(legacyStore, takServerRepo).runIfNeeded()
    }
    takServerMigrationReady.complete(result)
}

suspend fun awaitTakServerMigration() {
    takServerMigrationReady.await().getOrThrow()
}
```

Do not block `Application.onCreate`. Consumers should suspend until readiness.

The encrypted read must distinguish absent, successful, and failed states:

```kotlin
when (val result = takServerSecretsRepository.takServerListRead.first()) {
    null -> emptyList()
    is DecryptResult.Success -> parse(result.plaintext)
    is DecryptResult.Failure ->
        throw IllegalStateException(
            "Unable to decrypt TAK server list",
            result.error
        )
}
```

A decryption failure must never become a writable empty baseline. Otherwise a later add can overwrite or truncate existing settings.

Reliable consumers should use `awaitTakServerList()`, not a bare `getTakServerList.first()`:

- `MainActivity.seedVariablesFromRepo`
- managed-config reconciliation
- `SettingsActivity.refreshTakServerSettings`
- `TAKConnectService.setFromDatastore`
- companion list import/read paths

## Managed configuration reconciliation

Match managed entries by endpoint key, not array index:

```kotlin
val endpointKey = TakServerEndpointIdentity.key(takServerConfig)
val index = takServerList.indexOfFirst {
    TakServerEndpointIdentity.key(it) == endpointKey
}
```

De-duplicate incoming configuration:

```kotlin
takServerListConfig.distinctBy(TakServerEndpointIdentity::key)
```

If `tak_server_list` is explicitly present, treat it as authoritative. Stored endpoints absent from the payload should be removed by endpoint key. Post removal events from highest index to lowest so the positional runtime list remains valid.

If the parcel array is absent/null, do not interpret that as an instruction to remove manually configured servers.

## UI behavior

Reuse the existing registration result dialog/modal. Keep the watch-facing copy minimal:

```xml
<string name="duplicate_tak_server">Duplicate TAK Server</string>
```

Add flow:

```kotlin
when (dataRepository.addTakServerItem(item)) {
    DataRepository.TakServerAddResult.ADDED -> {
        // Existing success/registration behavior.
    }
    DataRepository.TakServerAddResult.DUPLICATE -> {
        // No append and no connection event.
        duplicateDialog = true
    }
}
```

The edit flow handles `TakServerUpdateResult.DUPLICATE` the same way. It must not locally replace the row or post a changed event after persistence rejects the edit.

The duplicate dialog must not retry registration or connection; it only informs the user and dismisses.

## Runtime connection safety net

Do not collapse duplicate entries from `takServerDetails`; UI and EventBus still address rows by index. Preserve rows and suppress duplicate connection activity after the first occurrence of each endpoint.

Recommended state:

```kotlin
var duplicateEndpointSuppressed: Boolean = false
```

At load and after list-changing events:

```kotlin
val seen = mutableSetOf<String>()

takServerDetails.forEach { detail ->
    val duplicate = !seen.add(
        TakServerEndpointIdentity.key(detail.address, detail.port)
    )

    detail.duplicateEndpointSuppressed = duplicate
    if (duplicate) {
        detail.secureSocket?.close()
        detail.secureSocket = null
        detail.connectionStatus.set(TAK_SERVER_DISCONNECTED)
    }
}
```

Check suppression at:

1. Initial connect loop.
2. Individual connect/reconnect method.
3. Outgoing `sendDataTAKServers` loop.

This prevents duplicate sockets and duplicate writes even if old persisted data or an external writer introduces duplicates. Do not remove suppressed entries from the runtime list.

## Files affected

| File | Responsibility |
|---|---|
| `data/takserver/TakServerEndpointIdentity.kt` | Canonical endpoint key and shared mutation mutex |
| `data/DataRepository.kt` | Add/update/remove/store guards, result types, decrypt-aware reads |
| `data/secure/migration/TakServerMigration.kt` | Serialized migration and decrypt failure handling |
| `data/secure/takserver/TakServerEncryptedDataStore.kt` | Expose decrypt result separately from nullable plaintext |
| `data/secure/takserver/TakServerSecretsRepository.kt` | Expose TAK list read result |
| `WearTakApplication.kt` | Non-blocking migration readiness gate |
| `presentation/MainActivity.kt` | Endpoint reconciliation and stale managed removal |
| `presentation/SettingsActivity.kt` | Duplicate add/edit result handling |
| `service/TAKConnectService.kt` | Runtime duplicate suppression and guarded fan-out |
| `data/CompanionAppRepository.kt` | Reliable TAK list reads |
| `service/CompanionAppService.kt` | Reliable TAK list reads/import path |
| `res/values/strings.xml` | Duplicate dialog title |

## Reimplementation order after pulling develop

1. Stash this branch and pull the latest `develop`.
2. Re-check the current `TakServerItem` model and encrypted repository APIs.
3. Add endpoint identity and the shared mutex.
4. Expose decrypt results from the encrypted TAK store.
5. Add the migration readiness gate and propagate migration failure.
6. Convert every TAK list mutation to serialized read/check/write.
7. Add endpoint-based managed reconciliation and explicit stale removal.
8. Update UI add/edit flows to handle duplicate results.
9. Add runtime suppression without changing list indices.
10. Add tests, then compile the app module before resolving any branch-wide failures.

## Suggested tests

- Add the same endpoint twice.
- Concurrently add the same endpoint from two coroutines.
- Compare hostname case, whitespace, and trailing-dot variants.
- Compare bracketed/unbracketed IPv6.
- Edit one row into another row’s endpoint.
- Remove using an object whose normalized fields differ.
- Import a list containing duplicate endpoints.
- Verify migration completes before the first list read.
- Verify decrypt failure does not write an empty list.
- Verify managed-config removal posts descending indices.
- Verify duplicate rows cannot create two active sockets.
- Verify one outgoing CoT is not written to a suppressed duplicate row.
- Confirm CoT XML and UID generation remain unchanged.

## Current validation caveat

The full Kotlin build was blocked by existing branch-wide unresolved APIs, primarily CEP persistence symbols and related `DataRepository` members. The heart-rate and reporting-interval diagnostics are largely cascading errors from those failures. They are separate from the endpoint deduplication design and must be resolved or synchronized with the latest `develop` branch before the TAK implementation can be fully compiled.
