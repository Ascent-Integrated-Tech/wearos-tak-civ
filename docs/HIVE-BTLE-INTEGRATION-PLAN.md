# HIVE-BTLE Integration Plan

> Replacing Ditto P2P sync with hive-btle for power-efficient BLE mesh synchronization.

**Branch**: `feat/hive-btle-sync`
**Status**: Phases 1-4, 7-11 Complete ✅, Phase 5-6 In Progress
**Last Updated**: 2026-01-18
**hive-btle Version**: 0.0.12 (with ChatCRDT for persistent mesh chat)

## Document Purpose

This document serves as the authoritative implementation specification for HIVE BLE mesh integration
in WearTAK-CIV. It is designed to support:

1. **Development** - Clear implementation objectives with code references
2. **Coordination** - Shared context between WearTAK and hive-btle teams
3. **Research** - Empirical foundations for academic publication on BLE mesh performance

### Changelog

| Date | Version | Changes |
|------|---------|---------|
| 2026-01-18 | 0.0.12 | **Chat Sync End-to-End Working** - (1) Fixed CRDT message tracking: replaced time-based `getChatMessagesSince()` with set-based `processedChatMessages` tracking using `originNode:timestamp` keys. Fixes issue where historical messages weren't detected after mesh recreation. (2) Fixed peer registration: call `onBleDiscovered`/`onBleConnected` before `onBleDataReceived` to ensure native peer_manager knows about peers. (3) Fixed DataRepository sharing: ChatActivity now uses `WearTakApplication.repo` instead of creating separate instance. Chat + ACK now works across watches and ATAK plugin. |
| 2026-01-16 | 0.0.12 | **ChatCRDT Receive Fix** - Fixed critical bug where chat messages weren't being received via CRDT sync. The sender side called `mesh.buildDocument()` (which includes chat CRDT), but the receiver never called `mesh.onBleDataReceived()` to merge it. Fix: (1) Added CRDT merge call in `handlePeerDocumentInternal()`, (2) Added chat count tracking to detect new messages after merge, (3) Fixed GATT server path to pass raw bytes for merge. Chat now properly syncs via document exchange. |
| 2026-01-15 | 0.0.12 | **ChatCRDT Implementation** - Chat messages now use proper CRDT semantics. Messages persist in local state and sync via document merge. Automatic deduplication by (originNode, timestamp). Max 32 messages retained. Chat survives disconnect/reconnect. Wire format: chat included in document with 0xAD section marker. |
| 2026-01-15 | 0.0.11 | **Chat Threading Support** - Added `replyToNode` and `replyToTimestamp` fields to HiveChat for message threading. ACK messages now reference the original message's (originNode, timestamp). Wire format adds 12 bytes, backward compatible. |
| 2026-01-15 | 0.0.11 | **Phase 7: Chat Integration (MVP)** - One-way ATAK → Watch chat via dedicated chat document format (0xAD). `HiveChat` data class, `sendChat()` API, `onChatReceived()` callback. Simple overlay UI design with ACK button. Added **Chat Integration Guide** for Android-ATAK team. |
| 2026-01-14 | 0.0.10 | **Phase 11: Multi-Hop Relay** - Document forwarding to non-adjacent peers with deduplication. Fixes tablet seeing 2 peers but only syncing with direct peer. |
| 2026-01-14 | 0.0.10 | **Phase 9: Map Marker Sync** - P2P marker sync via dedicated marker document format (0xAC). `HiveMarker` data class, `sendMarker()` API, `onMarkerSynced()` callback. CoT ↔ HiveMarker translation. Added comprehensive **Map Marker Sync** section to ATAK Plugin guide. |
| 2026-01-14 | - | **SOS Cancellation Fix** - Fixed SOS cancellation propagation: sender now broadcasts `eventType=NONE` when SOS is cancelled, receivers detect transition from EMERGENCY to NONE and clear peer emergency state. Added comprehensive **SOS/Emergency Event Handling** section to ATAK Plugin guide. |
| 2026-01-14 | - | **Phase 8: SOS Distribution** - P2P emergency alerts, flashing SOS button when peer in distress, peer list SOS indicator with red badge |
| 2026-01-14 | 0.0.10 | **Phase 10: Delta-Only Transmission** - Field-level deltas (UpdateLocation 12B, UpdateHealth 5B, UpdateCallsign, UpdateEvent 9B), 68-75% bandwidth savings vs full documents, per-peer sync state tracking |
| 2026-01-14 | - | Added **Android-ATAK Plugin: Delta Integration Guide** - architecture, wire format, state management patterns, code examples |
| 2026-01-14 | - | Added Phases 7-11 specifications: Chat, SOS, Markers, Delta, Multi-Hop |
| 2026-01-13 | 0.0.9 | Peer disconnect callbacks, address-based lookup |
| 2026-01-12 | 0.0.7 | Improved peer connection tracking |

### Known Issues
- **HIVE button disappears after deep sleep** (off-charger): The button doesn't render after WearOS deep sleep when watch is off charger. Works fine when on charger. Other icons (TAK, network, battery) survive deep sleep normally.

### Pending UX Refinements (Chat)
- **ACKs don't need ACK button**: Messages with `replyToNode != 0` are replies/ACKs and shouldn't show an ACK button. Check `HiveChat.replyToNode` in UI to hide ACK option for reply messages.
- **ACK display**: Consider showing ACKs inline with original message rather than as separate chat entries.

  **Attempted fixes (none worked):**
  - `collectAsStateWithLifecycle()` with `minActiveState = CREATED`
  - `drawBehind` instead of `background()` modifier
  - `key()` with lifecycle counter to force recreation
  - `AndroidView` with native `ImageButton`
  - Moving button to different Compose hierarchy levels
  - `Canvas` for drawing circle background
  - `LiveData.observeAsState()` (same pattern as surviving icons)

  **Position locked**: `MainScreen` level, `offset(x = 70.dp, y = 0.dp)` - DO NOT MOVE

---

## Executive Summary

Replace Ditto SDK with hive-btle to achieve:
- **18+ hour battery life** (vs 3-4 hours with Ditto's continuous scanning)
- **2-5% radio duty cycle** (vs 20%+ with Ditto)
- **Open source** dependency (no vendor lock-in)
- **Lightweight CRDTs** optimized for constrained devices

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        WearTAK Application                          │
├─────────────────────────────────────────────────────────────────────┤
│  CotDispatchService                                                 │
│  └─ Posts: HiveDocumentEvent → EventBus                            │
├─────────────────────────────────────────────────────────────────────┤
│  HiveBtleService (replaces DittoService)                           │
│  ├─ Owns: HiveMesh (Rust via JNI)                                  │
│  ├─ Implements: HiveObserver (receives mesh events)                │
│  ├─ Subscribes: @Subscribe onHiveDoc(HiveDocumentEvent)            │
│  └─ Broadcasts: GATT notifications to peers                        │
├─────────────────────────────────────────────────────────────────────┤
│  HiveBtleRepository (state management)                             │
│  ├─ Exposes: Flow<BleConnectionState>                              │
│  ├─ Exposes: Flow<List<HivePeer>>                                  │
│  └─ Manages: Mesh configuration, encryption keys                   │
├─────────────────────────────────────────────────────────────────────┤
│  com.defenseunicorns:peat (Published AAR dependency)             │
│  ├─ HiveMesh: Peer discovery, document sync, encryption            │
│  ├─ JNI Bridge: Kotlin ↔ Rust communication                        │
│  └─ Native: libhive_btle.so (arm64-v8a, armeabi-v7a)              │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Phase 1: hive-btle Android Library (in hive-btle repo) ✅ COMPLETE

**Location**: `../hive-btle/android/`
**Artifact**: `com.defenseunicorns:peat:0.0.9`
**Status**: ✅ AAR built and ready for integration

### Completed Tasks

- [x] **1.1** Create Android Library module structure in hive-btle repo
  - [x] `android/build.gradle.kts` - Android Library plugin, maven-publish, native build tasks
  - [x] `android/settings.gradle.kts` - Standalone module settings
  - [x] `android/gradle.properties` - Gradle configuration
  - [x] `android/gradlew` / `android/gradlew.bat` - Gradle wrapper scripts
  - [x] `android/gradle/wrapper/gradle-wrapper.properties` - Gradle 8.12
  - [x] `android/src/main/AndroidManifest.xml` - BLE permissions (already existed)

- [x] **1.2** Configure Rust cross-compilation for Android targets
  - [x] `.cargo/config.toml` - Android NDK linker config for arm64-v8a, armeabi-v7a, x86_64, i686
  - [x] `Cargo.toml` - Already has `android` feature flag and `crate-type = ["cdylib"]`
  - [x] Gradle `buildNativeLibs` task for `aarch64-linux-android` and `armv7-linux-androideabi`

- [x] **1.3** Kotlin JNI wrapper classes (already existed + new)
  - [x] `HiveBtle.kt` - Pure Kotlin BLE implementation (2300+ lines, already existed)
  - [x] `HiveMesh.kt` - **NEW** JNI wrapper for native Rust HiveMesh API
  - [x] `HivePeer.kt` - Peer data class (inline in HiveBtle.kt)
  - [x] `DiscoveredDevice.kt` - Discovered device data class (inline in HiveBtle.kt)
  - [x] `DataReceivedResult` - Result from native data processing (in HiveMesh.kt)
  - [x] `PeripheralType` / `DisconnectReason` - Type annotations (in HiveMesh.kt)

- [x] **1.4** JNI callback proxy classes (already existed)
  - [x] `ScanCallbackProxy.kt` - Forwards BLE scan results to native
  - [x] `GattCallbackProxy.kt` - Forwards GATT events to native
  - [x] `AdvertiseCallbackProxy.kt` - Forwards advertising events to native

- [x] **1.5** Build and bundle native libraries
  - [x] `buildNativeLibs` Gradle task - Builds .so files via Cargo
  - [x] `buildAar` Gradle task - Builds native libs + assembles AAR
  - [x] `publishLocal` Gradle task - Publishes to ~/.m2 for local testing

### Rebuilding Native Library (Rust)

When Rust code changes in hive-btle (e.g., `src/*.rs`, `src/platform/android/*.rs`), you must rebuild the native library:

```bash
cd ../hive-btle
make build-android
```

This runs `cargo ndk` to cross-compile for `arm64-v8a` and `armeabi-v7a`, outputting to:
- `android/src/main/jniLibs/arm64-v8a/libhive_btle.so`
- `android/src/main/jniLibs/armeabi-v7a/libhive_btle.so`

After rebuilding, the WearTAK gradle build will pick up the new `.so` files via composite build.

**Note**: Kotlin changes in `android/src/main/java/` are picked up automatically by gradle without running `make build-android`.

- [x] **1.6** Set up publishing
  - [x] `maven-publish` plugin configured
  - [x] GitHub Packages repository configured
  - [x] Local Maven repository for testing
  - [x] POM metadata (name, description, license, developers, scm)

- [x] **1.7** Documentation
  - [x] `android/README.md` - Integration guide with build instructions
  - [x] KDoc comments on HiveMesh public API

### Build Commands

```bash
# From hive-btle/android directory:

# Build AAR with native libraries (all-in-one)
./gradlew buildAar

# Publish to local Maven (~/.m2) for wearos-tak testing
./gradlew publishLocal

# Output locations:
# - AAR: build/outputs/aar/hive-0.0.9.aar
# - Maven: ~/.m2/repository/com/revolveteam/hive/0.0.9/
```

### Prerequisites for Building

1. **Android NDK** - Set `ANDROID_NDK_HOME` environment variable
2. **Rust Android targets**:
   ```bash
   rustup target add aarch64-linux-android armv7-linux-androideabi
   ```
3. **NDK toolchain** - Automatically added to PATH by buildNativeLibs task

### Files Created/Updated

```
../hive-btle/
├── .cargo/
│   └── config.toml                    # NEW - Android NDK linker config
└── android/
    ├── build.gradle.kts               # UPDATED - maven-publish, build tasks
    ├── settings.gradle.kts            # UPDATED - standalone settings
    ├── gradle.properties              # EXISTS
    ├── gradlew                        # NEW - wrapper script
    ├── gradlew.bat                    # NEW - Windows wrapper
    ├── gradle/wrapper/
    │   └── gradle-wrapper.properties  # NEW - Gradle 8.12
    ├── README.md                      # NEW - integration guide
    └── src/main/
        ├── AndroidManifest.xml        # EXISTS - BLE permissions
        ├── jniLibs/
        │   ├── arm64-v8a/             # Created by buildNativeLibs
        │   └── armeabi-v7a/           # Created by buildNativeLibs
        └── java/com/revolveteam/hive/
            ├── HiveBtle.kt            # EXISTS - Pure Kotlin BLE (2300+ lines)
            ├── HiveMesh.kt            # NEW - JNI wrapper for native HiveMesh
            ├── ScanCallbackProxy.kt   # EXISTS - Scan callbacks
            ├── GattCallbackProxy.kt   # EXISTS - GATT callbacks
            └── AdvertiseCallbackProxy.kt # EXISTS - Advertise callbacks
```

### Deliverable

**Ready for WearTAK integration**: `../hive-btle/android/build/outputs/aar/hive-0.0.9.aar`

Contents:
- `jni/arm64-v8a/libhive_btle.so` (925KB) - Modern devices
- `jni/armeabi-v7a/libhive_btle.so` (604KB) - Older devices
- Kotlin classes: `HiveMesh`, `HiveBtle`, callback proxies

Build commands:
1. `./gradlew buildAar` - Build AAR with native libraries
2. `./gradlew publishLocal` - Publish to local Maven for Gradle dependency
3. Or use composite build for source-level integration

---

## Phase 2: WearTAK Service Architecture

**Location**: `app/src/main/java/com/aitech/weartak_civ/`
**Status**: ✅ Complete

### Tasks

- [x] **2.1** Add hive dependency
  - [x] `settings.gradle` - Composite build configured
  - [x] `app/build.gradle` - Dependency added
  - [ ] Verify native library loads on device (Phase 6)

- [x] **2.2** Create HiveDocumentEvent (EventBus event)
  - [x] `events/HiveDocumentEvent.kt`
  - [x] Mirror DittoDocumentEvent pattern: `(collection: String, cotXml: String)`

- [x] **2.3** Create HiveBtleRepository (state management)
  - [x] `data/HiveBtleRepository.kt`
  - [x] DataRepository keys: `enable_hive_btle`, `hive_mesh_id`, `hive_power_profile`
  - [x] Flows: `getEnableHiveBtle`, `getHiveMeshId`, `getHivePowerProfile`
  - [x] State tracking: connected peers, mesh status

- [x] **2.4** Create HiveBtleService (main service)
  - [x] `service/HiveBtleService.kt`
  - [x] Foreground service with `foregroundServiceType="connectedDevice"`
  - [x] HiveBtle lifecycle: init → startMesh → stopMesh → shutdown
  - [x] HiveMeshListener implementation for mesh events (onMeshUpdated, onPeerEvent, onDocumentSynced)
  - [x] EventBus subscription for HiveDocumentEvent
  - [x] Periodic track document updates via sync scheduler
  - [x] Companion object: `isRunning: AtomicBoolean`

- [x] **2.5** Create CoT ↔ HiveDocument translation layer
  - [x] `hive/CotHiveTranslator.kt`
  - [x] `parseCotXml()` / `cotToCompactMarker()` - Outbound translation
  - [x] `compactMarkerToCotXml()` - Inbound translation
  - [x] Handle: track, mapitem, alert collections

- [x] **2.6** Register service with system
  - [x] `AndroidManifest.xml` - Declare HiveBtleService
  - [x] `ServiceRegistry.kt` - Add HIVE_BTLE → HiveBtleService mapping
  - [x] `ReactiveServiceManager.kt` - Add ServiceType.HIVE_BTLE with conflicts

### Architecture Note

The service uses **HiveBtle** (pure Kotlin BLE mesh implementation) directly with its
`startMesh(listener)` API instead of the native **HiveMesh** JNI wrapper. This provides:
- Simpler integration (no bridging between HiveBtle and HiveMesh)
- Complete BLE operations (scanning, advertising, GATT) handled internally
- Event callbacks via HiveMeshListener interface
- Automatic peer discovery and connection management

---

## Phase 3: Integration with Existing Systems

**Status**: ✅ Mostly Complete

### Tasks

- [x] **3.1** Wire CotDispatchService to post HiveDocumentEvent
  - [x] `sendDropNow()` → post HiveDocumentEvent("mapitem", xml)
  - [x] `handleClearUid()` → post HiveDocumentEvent("mapitem", clearXml)
  - [x] `sendEmergencyAlertCotMessage()` → post HiveDocumentEvent("alert", xml)
  - [x] Check `HiveBtleService.isRunning` before posting

- [ ] **3.2** Implement inbound document handling
  - [x] HiveMeshListener.onPeerEvent() handles emergencies/ACKs
  - [x] HiveMeshListener.onDocumentSynced() receives synced documents
  - [ ] Convert HiveDocument → CoT and dispatch to local map display
  - [ ] Handle incoming tracks (update local peer positions on map)
  - [ ] Handle incoming emergencies (trigger alert UI via EventBus)

- [x] **3.3** Endpoint status tracking
  - [x] `DataRepository.setEndpointConnected(Endpoint.HIVE_BTLE)`
  - [x] `DataRepository.setEndpointDisconnected(Endpoint.HIVE_BTLE)`
  - [x] `Endpoint.HIVE_BTLE` added to enum

- [x] **3.4** Service conflict management
  - [x] HIVE_BTLE conflicts with: DITTO, COMPANION_APP
  - [x] Priority ordering: DITTO(10) < HIVE_BTLE(15) < COMPANION_APP(20)
  - [ ] User preference UI for which P2P method to use

---

## Phase 4: Data Model Mapping ✅ COMPLETE

### HiveDocument Wire Format (for ATAK Plugin Team)

The HiveDocument is the primary data structure synced over BLE GATT. Each node maintains
a **single persistent document** that is updated (not recreated) on each sync cycle.

#### Document Structure (Little-Endian)

```
┌──────────────────────────────────────────────────────────────────┐
│ HEADER (8 bytes)                                                 │
├──────────────────────────────────────────────────────────────────┤
│ version      │ u32 │ Always 1                                    │
│ nodeId       │ u32 │ Sender's 32-bit node ID (hex in logs)       │
├──────────────────────────────────────────────────────────────────┤
│ GCOUNTER (12 + 12*N bytes)                                       │
├──────────────────────────────────────────────────────────────────┤
│ numEntries   │ u32 │ Number of GCounter entries                  │
│ entries[]    │     │ Array of (nodeId: u32, count: u64) pairs    │
├──────────────────────────────────────────────────────────────────┤
│ PERIPHERAL EXTENSION (optional, 4 + M bytes)                     │
├──────────────────────────────────────────────────────────────────┤
│ marker       │ u8  │ 0xAB = extended data follows                │
│ reserved     │ u8  │ Always 0                                    │
│ length       │ u16 │ Peripheral data length (M bytes)            │
│ peripheral   │     │ HivePeripheral structure (M bytes)          │
└──────────────────────────────────────────────────────────────────┘
```

#### Minimum Document Size

- **36 bytes**: Header (8) + GCounter with 2 entries (4 + 24) = No location data
- **96 bytes**: Header + GCounter + Peripheral with location = Full PLI document

#### HivePeripheral Structure (when present)

Sizes: 35 bytes (base), 44 bytes (with event), 47 bytes (with location), 56 bytes (with both)

```
┌──────────────────────────────────────────────────────────────────┐
│ PERIPHERAL BASE (26 bytes)                                       │
├──────────────────────────────────────────────────────────────────┤
│ Offset│ Field       │ Type│ Description                          │
├──────────────────────────────────────────────────────────────────┤
│   0   │ id          │ u32 │ Peripheral ID (same as nodeId)       │
│   4   │ parentNode  │ u32 │ Parent node ID (0 if none)           │
│   8   │ type        │ u8  │ 1 = SOLDIER_SENSOR                   │
│   9   │ callsign    │ 12b │ Null-padded ASCII string             │
│  21   │ battery     │ u8  │ Battery percent (0-100)              │
│  22   │ heartRate   │ u8  │ Heart rate BPM (0 if not available)  │
│  23   │ activity    │ u8  │ Activity level                       │
│  24   │ alerts      │ u8  │ Alert flags (bit field)              │
│  25   │ hasEvent    │ u8  │ 1 if event data follows              │
├──────────────────────────────────────────────────────────────────┤
│ EVENT (optional, 9 bytes if hasEvent=1)                          │
├──────────────────────────────────────────────────────────────────┤
│  26   │ eventType   │ u8  │ 0=NONE, 1=EMERGENCY, 2=ACK, 3=PING   │
│  27   │ eventTs     │ u64 │ Event timestamp (ms since epoch)     │
├──────────────────────────────────────────────────────────────────┤
│ LOCATION FLAG (1 byte)                                           │
├──────────────────────────────────────────────────────────────────┤
│  26/35│ hasLocation │ u8  │ 1 if location follows, 0 otherwise   │
├──────────────────────────────────────────────────────────────────┤
│ LOCATION (optional, 12 bytes if hasLocation=1)                   │
├──────────────────────────────────────────────────────────────────┤
│  27/36│ latitude    │ f32 │ Latitude (IEEE 754 LE float)         │
│  31/40│ longitude   │ f32 │ Longitude (IEEE 754 LE float)        │
│  35/44│ altitude    │ f32 │ Altitude in meters (IEEE 754 LE)     │
├──────────────────────────────────────────────────────────────────┤
│ TIMESTAMP (8 bytes)                                              │
├──────────────────────────────────────────────────────────────────┤
│  last8│ timestamp   │ u64 │ Document timestamp (ms since epoch)  │
└──────────────────────────────────────────────────────────────────┘
```

Note: Event offset varies based on hasEvent. Location offset varies based on both hasEvent and hasLocation.

#### Example Wire Data (96 bytes with location)

```
01 00 00 00                 # version = 1
A7 65 3E 66                 # nodeId = 0x663E65A7
02 00 00 00                 # numEntries = 2
A7 65 3E 66                 # entry[0].nodeId
06 00 00 00 00 00 00 00     # entry[0].count = 6
11 3F 63 7A                 # entry[1].nodeId = 0x7A633F11
05 00 00 00 00 00 00 00     # entry[1].count = 5
AB                          # EXTENDED_MARKER
00                          # reserved
38 00                       # peripheral length = 56 bytes
A7 65 3E 66                 # peripheral.id
00 00 00 00                 # peripheral.parentNode
01                          # peripheral.type = SOLDIER_SENSOR
57 45 41 52 4F 53 2D 33 39 35 31 00  # callsign = "WEAROS-3951"
64                          # battery = 100%
00 00 00                    # heartRate, padding
01 00                       # eventType = 0, padding
11 12 17 B3 9B 01 00 00     # eventTs (6 bytes + 2 padding)
01                          # hasLocation = true
06 DD 06 42                 # latitude = 33.715843 (float LE)
8A D2 A8 C2                 # longitude = -84.41121 (float LE)
CD 0C 8E 43                 # altitude (float LE)
11 12 17 B3 9B 01 00 00     # timestamp (6 bytes + 2 padding)
```

#### Reading Location from HiveDocument

```kotlin
// Kotlin example for ATAK plugin
fun extractLocationFromDocument(data: ByteArray): LocationData? {
    if (data.size < 12) return null

    // Read header
    val numEntries = readU32LE(data, 8).toInt()
    val gcounterEnd = 12 + numEntries * 12  // header(8) + count(4) + entries

    // Check for extended marker (0xAB)
    if (data.size <= gcounterEnd || data[gcounterEnd] != 0xAB.toByte()) {
        return null  // No peripheral extension
    }

    // Read peripheral length
    val peripheralLen = readU16LE(data, gcounterEnd + 2).toInt()
    val peripheralStart = gcounterEnd + 4  // marker(1) + reserved(1) + len(2)

    if (data.size < peripheralStart + peripheralLen) return null

    // Parse peripheral fields
    val callsign = String(data, peripheralStart + 9, 12).trimEnd('\u0000')
    val battery = data[peripheralStart + 21].toInt() and 0xFF
    val heartRate = (data[peripheralStart + 22].toInt() and 0xFF).let { if (it > 0) it else null }
    val hasEvent = data[peripheralStart + 25] == 1.toByte()

    // Location flag comes after optional event data
    val locationFlagOffset = peripheralStart + 26 + (if (hasEvent) 9 else 0)
    if (data.size <= locationFlagOffset) return null

    val hasLocation = data[locationFlagOffset] == 1.toByte()
    if (!hasLocation) return null

    // Read location (3 floats, little-endian)
    val locationOffset = locationFlagOffset + 1
    if (data.size < locationOffset + 12) return null

    val latitude = Float.fromBits(readU32LE(data, locationOffset).toInt())
    val longitude = Float.fromBits(readU32LE(data, locationOffset + 4).toInt())
    val altitude = Float.fromBits(readU32LE(data, locationOffset + 8).toInt())

    return LocationData(callsign, latitude, longitude, altitude, battery, heartRate)
}

data class LocationData(
    val callsign: String,
    val latitude: Float,
    val longitude: Float,
    val altitude: Float,
    val battery: Int,
    val heartRate: Int?
)

fun readU32LE(data: ByteArray, offset: Int): Long {
    return ((data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24))
}

fun readU16LE(data: ByteArray, offset: Int): Long {
    return ((data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8))
}
```

### Collection Mapping

| Ditto Collection | HiveDocument Field | Notes |
|------------------|-------------------|-------|
| `track` | `peripheral` | GPS, battery, vitals, activity |
| `mapitem` | `extended` (custom payload) | Markers, waypoints |
| `alert` | `emergency` (eventType=1) | SOS with ACK tracking |

### Track Document Fields

```kotlin
// Ditto v2 schema (abbreviated) → HiveDocument.peripheral
mapOf(
    "j" to lat,           // → peripheral.location.latitude (float)
    "l" to lon,           // → peripheral.location.longitude (float)
    "i" to hae,           // → peripheral.location.altitude (float)
    "battery" to pct,     // → peripheral.health.batteryPercent (u8)
    "heartRate" to hr,    // → peripheral.health.heartRate (u8)
    "callsign" to cs,     // → peripheral.callsign (12 bytes)
)
```

### Tasks

- [x] **4.1** HiveDocument wire format defined and documented
  - [x] Header + GCounter + Peripheral extension structure
  - [x] HiveLocation encoding (3 floats, 12 bytes)
  - [x] Example wire data with byte-level breakdown

- [x] **4.2** Implement track field mapping
  - [x] `HiveBtleService.broadcastTrackUpdate()` - WearTAK → mesh
  - [x] `HiveBtleService.onDocumentSynced()` - mesh → WearTAK
  - [x] Location, callsign, battery, heartRate fields flowing

- [x] **4.3** Persistent document model
  - [x] Each node maintains ONE document (not creating new for each update)
  - [x] `syncWithPeers()` uses stored peripheral state
  - [x] Read requests return full document with peripheral

- [ ] **4.4** Implement emergency/ACK flow (SOS Sync)
  - [ ] `sendEvent(HiveEventType.EMERGENCY)` broadcasts emergency
  - [ ] `sendEvent(HiveEventType.ACK)` acknowledges emergency
  - [ ] Track ACK status per peer
  - [ ] Sync SOS state across all HIVE BLE Mesh devices
  - [ ] Visual/audio alert on receiving device
  - [ ] ACK confirmation propagates back to originator

- [ ] **4.5** Define mapitem extended payload format (Map Markers)
  - [ ] Compact binary format for markers/waypoints
  - [ ] Include: uid, type, lat, lon, callsign, detail hash
  - [ ] Sync map marker creation to HIVE documents
  - [ ] Display markers on ATAK via HIVE→ATAK plugin bridge
  - [ ] Handle marker updates and deletions

- [ ] **4.6** Implement full-duplex chat over HIVE BLE Mesh
  - [ ] Define chat message payload format (sender, timestamp, message text)
  - [ ] Integrate with existing WearTAK chat UI (ChatActivity)
  - [ ] Bi-directional message flow between mesh peers
  - [ ] Message delivery confirmation / read receipts (optional)
  - [ ] Chat history persistence across app restarts

---

## Phase 5: Settings & UI

### DataRepository Settings

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `enable_hive_btle_service` | Boolean | `false` | Enable HIVE BLE sync |
| `hive_mesh_id` | String | `"WEARTAK"` | Mesh identifier for filtering |
| `hive_power_profile` | Int | `2` (LowPower) | 0=Aggressive, 1=Balanced, 2=LowPower |
| `hive_encryption_key` | String? | `null` | Optional 32-byte hex key |
| `hive_strict_encryption` | Boolean | `false` | Reject unencrypted docs |

### Tasks

- [ ] **5.1** Add settings to DataRepository
  - [ ] Store/retrieve functions
  - [ ] Flow emissions for reactive UI

- [ ] **5.2** Settings UI (if applicable)
  - [ ] Toggle for HIVE BLE sync
  - [ ] Mesh ID configuration
  - [ ] Power profile selector
  - [ ] Encryption key input (advanced)

- [x] **5.3** Status indicators
  - [x] Connection status in main UI (green/gray HIVE button next to TAK icon)
  - [x] Peer count via button tap → HiveMeshActivity
  - [x] Peer list showing callsign, distance, RSSI, battery
  - [ ] Sync activity indicator

- [ ] **5.4** HIVE mesh status screen (HiveMeshActivity)
  - [x] Peer list with callsign, distance, signal strength
  - [x] Battery and heart rate display per peer
  - [ ] Peer detail view (tap peer for full info)
  - [ ] Manual refresh/rescan option

---

## Phase 6: Testing & Validation

### Tasks

- [ ] **6.1** Unit tests
  - [ ] CotHiveTranslator tests
  - [ ] HiveBtleRepository tests
  - [ ] Mock HiveMesh for service tests

- [ ] **6.2** Integration tests
  - [ ] Service lifecycle tests
  - [ ] EventBus flow tests
  - [ ] ReactiveServiceManager conflict tests

- [ ] **6.3** Hardware testing
  - [ ] Single device: service starts/stops cleanly
  - [ ] Two devices: peer discovery works
  - [ ] Two devices: document sync works
  - [ ] Emergency/ACK round-trip
  - [ ] Battery life measurement (target: 18+ hours)

- [ ] **6.5** Battery-life testing (comprehensive)
  - [ ] Baseline: HIVE mesh idle (scanning/advertising only)
  - [ ] Active sync: continuous PLI updates every 5s
  - [ ] Chat workload: periodic message send/receive
  - [ ] Emergency scenario: SOS + ACK round-trip
  - [ ] Compare against Ditto equivalent workloads
  - [ ] Document power consumption per operation type
  - [ ] Test across power profiles (Aggressive, Balanced, LowPower)

- [ ] **6.4** Multi-device mesh testing
  - [ ] 3+ devices forming mesh
  - [ ] Message propagation across hops
  - [ ] Peer timeout and cleanup

---

## Files to Create

### In wearos-tak-civ repo (this repo)

```
app/src/main/java/com/aitech/weartak_civ/
├── service/
│   └── HiveBtleService.kt
├── data/
│   └── HiveBtleRepository.kt
├── events/
│   └── HiveDocumentEvent.kt
└── hive/
    └── CotHiveTranslator.kt
```

---

## Files to Modify

### In wearos-tak-civ repo

| File | Changes |
|------|---------|
| `settings.gradle.kts` | Add composite build or mavenLocal() |
| `app/build.gradle.kts` | Add hive dependency |
| `AndroidManifest.xml` | Declare HiveBtleService |
| `CotDispatchService.kt` | Post HiveDocumentEvent for sync |
| `ReactiveServiceManager.kt` | Add HIVE_BTLE ServiceType |
| `ServiceRegistry.kt` | Register HiveBtleService |
| `DataRepository.kt` | Add HIVE settings and Endpoint.HIVE_BTLE |

---

## Success Criteria

| Metric | Target | Measurement |
|--------|--------|-------------|
| Battery Life | 18+ hours | Hardware test with continuous sync |
| Radio Duty Cycle | <5% | BLE analyzer measurement |
| Sync Latency | <5 seconds | Track update propagation time |
| Emergency Round-Trip | <10 seconds | Emergency → all ACKs received |
| Peer Discovery | <30 seconds | New device to mesh join time |
| Crash Rate | 0 | No service crashes in 24hr test |

---

## Dependencies

### Option A: Composite Build (Recommended for Development)

No publishing required. Gradle builds hive-btle from source.

```kotlin
// In wearos-tak-civ/settings.gradle.kts
includeBuild("../hive-btle/android") {
    dependencySubstitution {
        substitute(module("com.defenseunicorns:peat")).using(project(":"))
    }
}

// In wearos-tak-civ/app/build.gradle.kts
dependencies {
    implementation("com.defenseunicorns:peat")  // Version not needed
}
```

### Option B: Local Maven (After `./gradlew publishLocal`)

```kotlin
// In wearos-tak-civ/settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenLocal()  // ~/.m2/repository
        // ... other repos
    }
}

// In wearos-tak-civ/app/build.gradle.kts
dependencies {
    implementation("com.defenseunicorns:peat:0.0.9")
}
```

### Option C: GitHub Packages (For CI/CD)

```kotlin
// In wearos-tak-civ/settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/Ascent-Integrated-Tech/hive-btle")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

// In wearos-tak-civ/app/build.gradle.kts
dependencies {
    implementation("com.defenseunicorns:peat:0.1.0")
}
```

---

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| JNI stability on WearOS | Extensive testing on target hardware; graceful error handling |
| Native library size | Strip symbols; LTO optimization; target specific ABIs only |
| BLE permission changes | Target appropriate SDK; runtime permission handling |
| Power regression | Battery benchmarking in CI; power profile defaults to LowPower |
| Migration complexity | Run alongside Ditto during transition via conflict rules |

---

## Phase 7: Chat Integration (MVP)

> **Objective**: Display ATAK chat messages on WearTAK watches via HIVE BLE mesh with CRDT persistence.

**Status**: ✅ Complete (WearTAK ready, ATAK Plugin pending)
**Depends On**: Phase 4 (Data Model), multi-hop relay (Phase 11)
**Scope**: Bidirectional chat via CRDT, persistent message history
**hive-btle Version**: 0.0.12+

### MVP Scope

This phase implements **CRDT-based chat** with automatic persistence and sync:

1. **ATAK Plugin** sends chat messages via `hiveBtle.sendChat()`
2. **Chat CRDT** stores messages locally and syncs with document merge
3. **Messages persist** across disconnect/reconnect (up to 32 messages)
4. **Automatic deduplication** by (originNode, timestamp)

**Key Features**:
- Messages survive BLE disconnection and reconnect
- New peers receive chat history on first sync
- No duplicate messages even with multi-hop relay
- Reply-to threading with `replyToNode` and `replyToTimestamp`

### Architecture Overview (CRDT-Based)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      CHAT MESSAGE FLOW (CRDT)                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SENDER (ATAK or Watch):                                                     │
│  ┌──────────────┐    ┌────────────────┐    ┌─────────────────┐              │
│  │ Chat UI      │───▶│ HiveBtle       │───▶│ ChatCRDT        │              │
│  │ (send msg)   │    │ sendChat()     │    │ add_message()   │              │
│  └──────────────┘    └────────────────┘    └────────┬────────┘              │
│                                                      │                       │
│                                                      ▼                       │
│                                             ┌─────────────────┐              │
│                                             │ HiveDocument    │              │
│                                             │ (includes chat) │              │
│                                             └────────┬────────┘              │
│                                                      │                       │
│                                             GATT broadcast                   │
│                                             (document sync)                  │
│                                                      │                       │
│  RECEIVER (Watch or ATAK):                           ▼                       │
│  ┌──────────────┐    ┌────────────────┐    ┌─────────────────┐              │
│  │ Chat UI      │◀───│ HiveBtleService│◀───│ ChatCRDT.merge()│              │
│  │ (display)    │    │ onChatReceived │    │ (dedup + store) │              │
│  └──────────────┘    └────────────────┘    └─────────────────┘              │
│                                                                              │
│  CRDT Semantics:                                                             │
│  - Add-only set: messages cannot be deleted                                  │
│  - Dedup key: (originNode, timestamp)                                        │
│  - Merge: union of messages from both sets                                   │
│  - Prune: oldest messages removed when > 32                                  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Wire Format: Chat in Document (0xAD Section)

**Chat Section Marker**: `0xAD` (as section within HiveDocument)

Chat is now stored as a section within the standard HiveDocument, not as a standalone document.
This enables automatic sync via document merge.

```
┌──────────────────────────────────────────────────────────────────┐
│ HIVEDOCUMENT WITH CHAT SECTION                                   │
├──────────────────────────────────────────────────────────────────┤
│ [Standard HiveDocument header + counter + peripheral + emergency]│
│                                                                  │
│ CHAT SECTION (if present):                                       │
│ Offset │ Field           │ Type    │ Description                 │
├──────────────────────────────────────────────────────────────────┤
│   0    │ marker          │ u8      │ 0xAD = CHAT section         │
│   1    │ reserved        │ u8      │ 0x00                        │
│   2-3  │ length          │ u16 LE  │ Length of chat CRDT data    │
│   4+   │ chat_crdt       │ [u8]    │ Encoded ChatCRDT            │
└──────────────────────────────────────────────────────────────────┘

CHAT CRDT ENCODING:
│   0-1  │ num_messages    │ u16 LE  │ Number of messages          │
│   2+   │ messages[]      │ [u8]    │ Array of ChatMessage        │

CHAT MESSAGE ENCODING (per message):
│   0-3  │ originNode      │ u32 LE  │ Sender's node ID            │
│   4-11 │ timestamp       │ u64 LE  │ Message timestamp (ms)      │
│  12    │ senderLen       │ u8      │ Sender length (max 12)      │
│  13+   │ sender          │ [u8]    │ Sender callsign             │
│  +L1   │ textLen         │ u8      │ Text length (max 128)       │
│  +L1+1 │ text            │ [u8]    │ UTF-8 message text          │
│  +L2   │ flags           │ u8      │ bit 0: broadcast, bit 1: ack│
│  +L2+1 │ replyToNode     │ u32 LE  │ Reply-to origin node (0=none)│
│  +L2+5 │ replyToTimestamp│ u64 LE  │ Reply-to timestamp (0=none) │
└──────────────────────────────────────────────────────────────────┘
```

**CRDT Limits**:
| Parameter | Value | Notes |
|-----------|-------|-------|
| Max sender length | 12 chars | UTF-8 bytes |
| Max message length | 128 chars | UTF-8 bytes |
| Max messages retained | 32 | Oldest pruned on overflow |
| Message ID | (originNode, timestamp) | Deduplication key |

### HiveBtle API (hive-btle library)

#### Data Class

```kotlin
data class HiveChat(
    val sender: String,              // Sender callsign (max 12 chars)
    val message: String,             // Message text (max 128 chars)
    val timestamp: Long,             // Epoch milliseconds
    val originNode: Long,            // Sender's node ID
    val isBroadcast: Boolean = true,
    val requiresAck: Boolean = false,
    val replyToNode: Long = 0,       // originNode of message being replied to
    val replyToTimestamp: Long = 0   // timestamp of message being replied to
) {
    fun isReply(): Boolean = replyToNode != 0L || replyToTimestamp != 0L
    fun messageIdString(): String = "${String.format("%08X", originNode)}:$timestamp"
    fun replyToIdString(): String? = if (isReply()) "${String.format("%08X", replyToNode)}:$replyToTimestamp" else null

    companion object {
        /** Max sender length (12 chars for CRDT compatibility) */
        const val MAX_SENDER_LENGTH = 12
        /** Max message length (128 chars for CRDT compatibility) */
        const val MAX_MESSAGE_LENGTH = 128
    }
}
```

#### Send Methods (CRDT-Based)

```kotlin
// In HiveBtle class

/**
 * Send a chat message via CRDT.
 * Message is stored locally and document is broadcast to all peers.
 * Automatically deduplicated by (originNode, timestamp).
 */
fun sendChat(chat: HiveChat) {
    val mesh = _mesh ?: return

    val docBytes = if (chat.isReply()) {
        mesh.sendChatReply(
            chat.sender.take(12),
            chat.message.take(128),
            chat.replyToNode,
            chat.replyToTimestamp
        )
    } else {
        mesh.sendChat(chat.sender.take(12), chat.message.take(128))
    }

    if (docBytes.isEmpty()) return  // Duplicate message

    // Broadcast document to all connected peers
    for ((_, gatt) in connections) {
        writeDocumentToGatt(gatt, docBytes)
    }
    notifyConnectedCentrals(docBytes)
}

// Convenience overload
fun sendChat(sender: String, message: String) {
    sendChat(HiveChat(
        sender = sender.take(12),
        message = message.take(128),
        timestamp = System.currentTimeMillis(),
        originNode = nodeId
    ))
}
```

#### Read Methods (CRDT-Based)

```kotlin
/**
 * Get count of chat messages in local CRDT.
 */
fun getChatCount(): Int = _mesh?.chatCount() ?: 0

/**
 * Get all chat messages from local CRDT.
 */
fun getAllChatMessages(): List<HiveChat>

/**
 * Get chat messages received since a given timestamp.
 */
fun getChatMessagesSince(sinceTimestamp: Long): List<HiveChat>
```

#### Receive Callback

```kotlin
interface HiveMeshListener {
    // ... existing methods ...

    /**
     * Called when a chat message is received from a mesh peer.
     * @param chat The received chat message
     * @param fromPeer The peer that relayed this message (may differ from chat.originNode for multi-hop)
     */
    fun onChatReceived(chat: HiveChat, fromPeer: HivePeer)
}
```

### WearTAK Integration

#### HiveBtleService Callback

```kotlin
// In HiveBtleService.kt
override fun onChatReceived(chat: HiveChat, fromPeer: HivePeer) {
    Log.i(TAG, "[CHAT] Received from ${chat.sender}: ${chat.message}")

    // Post event for UI to display
    EventBus.getDefault().post(HiveChatReceivedEvent(
        sender = chat.sender,
        message = chat.message,
        timestamp = chat.timestamp,
        originNodeId = chat.originNode
    ))
}
```

#### Chat Overlay UI (New)

Simple overlay that appears when chat is received:

```kotlin
// New file: HiveChatOverlay.kt
@Composable
fun HiveChatOverlay(
    chat: HiveChatReceivedEvent?,
    onAck: () -> Unit,
    onDismiss: () -> Unit
) {
    if (chat == null) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .clickable { onDismiss() }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Sender
            Text(
                text = chat.sender,
                color = Color(0xFF2196F3),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Message
            Text(
                text = chat.message,
                color = Color.White,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ACK button
            Button(
                onClick = onAck,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                )
            ) {
                Text("ACK", fontWeight = FontWeight.Bold)
            }
        }
    }
}
```

### Implementation Tasks

**hive-btle Library** (0.0.11):
- [x] **7.1** Add `HiveChat` data class with encode/decode
- [x] **7.2** Add `CHAT_DOCUMENT_MARKER = 0xAD` constant
- [x] **7.3** Add `sendChat()` method to HiveBtle
- [x] **7.4** Add `onChatReceived()` to HiveMeshListener
- [x] **7.5** Add `handlePeerChatDocument()` handler with multi-hop relay

**WearTAK** (complete):
- [x] **7.6** Add `InboundChat` data class to HiveBtleRepository
- [x] **7.7** Implement `onChatReceived()` in HiveBtleService
- [x] **7.8** Create `HiveChatOverlay` composable
- [x] **7.9** Integrate overlay in MainActivity
- [x] **7.10** ACK button dismisses overlay (ACK transmission deferred)

**Android-ATAK Plugin** (upstream - ready for implementation):
- [ ] **7.11** Hook ATAK chat events
- [ ] **7.12** Call `hiveBtle.sendChat()` for outgoing messages
- [ ] **7.13** Test multi-hop relay to watches

### Key Files

| File | Purpose | Status |
|------|---------|--------|
| `hive-btle/.../HiveBtle.kt` | Chat API: sendChat(), onChatReceived() | Complete |
| `wearos-tak-civ/.../HiveBtleService.kt` | Chat callback handler | Complete |
| `wearos-tak-civ/.../HiveChatOverlay.kt` | Overlay UI | Complete |
| `wearos-tak-civ/.../MainActivity.kt` | Overlay integration | Complete |
| `wearos-tak-civ/.../HiveBtleRepository.kt` | InboundChat state | Complete |

---

## Android-ATAK Plugin: Chat Integration Guide

This section provides implementation guidance for the Android-ATAK team to enable chat message transmission over HIVE BLE mesh.

### Quick Start

```kotlin
// 1. Get HiveBtle instance
val hiveBtle = HiveBtle.getInstance(context)

// 2. Send chat message
hiveBtle.sendChat(
    sender = "ALPHA-1",      // Your callsign
    message = "Rally at checkpoint bravo"
)

// 3. (Optional) With full control
val chat = HiveChat(
    sender = callsign,
    message = messageText,
    timestamp = System.currentTimeMillis(),
    originNode = hiveBtle.nodeId,
    isBroadcast = true,
    requiresAck = false
)
hiveBtle.sendChat(chat)
```

### Integration with ATAK Chat

```kotlin
class AtakHiveChatPlugin : HiveMeshListener {

    private var hiveBtle: HiveBtle? = null

    fun onAtakChatSent(roomId: String, message: String, senderCallsign: String) {
        // Only broadcast "All Chat Rooms" or team rooms
        if (roomId == "All Chat Rooms" || roomId.startsWith("Team-")) {
            hiveBtle?.sendChat(senderCallsign, message)
        }
    }

    // Receive chat from mesh (if implementing tablet display)
    override fun onChatReceived(chat: HiveChat, fromPeer: HivePeer) {
        Log.i(TAG, "[HIVE-CHAT] ${chat.sender}: ${chat.message}")
        // Optional: Display in ATAK chat UI
    }

    // ... other HiveMeshListener methods ...
}
```

### Message Constraints

| Field | Max Length | Notes |
|-------|------------|-------|
| sender | 16 chars | Callsign, truncated if longer |
| message | 140 chars | SMS-style limit for BLE efficiency |
| total packet | ~170 bytes | Fits BLE MTU comfortably |

### Multi-Hop Behavior

Chat messages automatically relay through the mesh:
- Tablet → Watch 1 → Watch 2 (if Watch 2 not in tablet range)
- Deduplication prevents message loops
- 30-second dedup window

### Testing Checklist

- [ ] Send chat from ATAK, verify watch displays overlay
- [ ] Verify sender callsign displays correctly
- [ ] Verify message truncation at 140 chars
- [ ] Test multi-hop: tablet → watch1 → watch2
- [ ] Verify ACK button dismisses overlay on watch

### ATAK Native Chat Integration (Future Consideration)

> **Status**: Deferred - Requires architecture review for deeper ATAK integration

The current implementation uses a custom chat UI in the HIVE plugin dropdown. Full integration with ATAK's native chat system was considered but deferred due to complexity. This section documents the challenges for future reference.

#### Integration Approach

ATAK's SMS sample (`samples/smsservice/`) demonstrates the pattern:

```kotlin
// 1. Create contact for each HIVE peer
val contact = IndividualContact(peerId)
Contacts.getInstance().addContact(contact)

// 2. Add connector for HIVE transport
contact.addConnector(HiveChatConnector(peerId))

// 3. Store message in ChatDatabase
val messageBundle = Bundle().apply {
    putString("conversationId", contact.uid)
    putString("messageId", UUID.randomUUID().toString())
    putString("senderUid", senderContact.uid)
    putString("message", chat.message)
    putLong("sentTime", chat.timestamp)
    putString("senderCallsign", chat.sender)
}
ChatDatabase.getInstance(null).addChat(messageBundle)

// 4. Notify UI
val intent = Intent("com.atakmap.android.chat.NEW_CHAT_MESSAGE")
intent.putExtra("conversationId", contact.uid)
AtakBroadcast.getInstance().sendBroadcast(intent)
```

#### Challenges

| Challenge | Description | Complexity |
|-----------|-------------|------------|
| **Contact Management** | Each HIVE peer must be registered as an ATAK `IndividualContact`. Requires lifecycle management (add on connect, remove on disconnect, handle reconnects). | High |
| **Contact Persistence** | ATAK persists contacts - need to handle stale HIVE peers that no longer exist. May require custom `IContactService` implementation. | Medium |
| **Connector Implementation** | Must implement `Connector` subclass for HIVE transport. Requires understanding ATAK's connector priority and routing logic. | Medium |
| **ChatDatabase Access** | `ChatDatabase` is an internal ATAK API. May change between ATAK versions. No public SDK documentation. | High |
| **Bidirectional Routing** | Outgoing messages need to be intercepted from ATAK's chat UI and routed to HIVE. Requires registering as a chat handler. | High |
| **Room/Group Chat** | ATAK has room-based chat. HIVE broadcast chat doesn't map cleanly to ATAK's conversation model. | Medium |

#### Architecture Review Needed

The HIVE ATAK plugin currently uses a custom UI approach (dropdown panels) for all features:
- Cells list
- Platforms list
- Tracks list
- HIVE Markers list
- Chat (this phase)

A larger architecture review should consider deeper ATAK integration:
- Using ATAK's native marker system instead of custom overlays
- Integrating with ATAK's contact/chat system
- Leveraging ATAK's CoT routing for message delivery
- Plugin as a "transport" rather than parallel UI

This review is out of scope for the current MVP but should be considered for v2.0.

---

## Phase 8: SOS Distribution

> **Objective**: Synchronize emergency alerts (SOS) across all HIVE mesh peers with acknowledgment tracking.

**Status**: ✅ Complete (P2P SOS distribution)
**Completed**: 2026-01-14
**Depends On**: Phase 4.4 (Emergency/ACK flow)

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         SOS DISTRIBUTION FLOW                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ORIGINATOR (Device A triggers SOS):                                         │
│  ┌──────────────┐    ┌────────────────┐    ┌─────────────────┐              │
│  │ SosActivity  │───▶│CotDispatchSvc  │───▶│ HiveBtleService │              │
│  │ (2-tap SOS)  │    │ line 922       │    │ handleAlert()   │              │
│  └──────────────┘    └────────────────┘    └─────────────────┘              │
│         │                    │                      │                        │
│         ▼                    ▼                      ▼                        │
│  CustomEmergencyEvent  EmergencySnapshot     hiveBtle.sendEvent             │
│  (category, desc)      + CoT XML             (EMERGENCY)                    │
│                                                                              │
│  RECEIVER (Device B receives SOS):                                           │
│  ┌──────────────┐    ┌────────────────┐    ┌─────────────────┐              │
│  │ HiveBtle     │───▶│ HiveBtleService│───▶│ AlertActivity   │              │
│  │ onPeerEvent  │    │ onPeerEvent()  │    │ (visual/audio)  │              │
│  └──────────────┘    └────────────────┘    └─────────────────┘              │
│         │                    │                      │                        │
│         ▼                    ▼                      ▼                        │
│  HiveEventType         Repository update     User sees alert                │
│  .EMERGENCY            + EventBus post       Can ACK                        │
│                                                                              │
│  ACK FLOW (Device B acknowledges):                                           │
│  ┌──────────────┐    ┌────────────────┐    ┌─────────────────┐              │
│  │ AlertActivity│───▶│ HiveBtleService│───▶│ HiveBtle        │              │
│  │ (tap ACK)    │    │ sendAck()      │    │ sendEvent(ACK)  │              │
│  └──────────────┘    └────────────────┘    └─────────────────┘              │
│                                                    │                         │
│                                                    ▼                         │
│                              Device A receives ACK, updates UI               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Current Implementation Status

**Outbound SOS (WearTAK → HIVE)**: ✅ Complete
- `CotDispatchService.sendEmergencyAlertCotMessage()` posts `HiveDocumentEvent("alert", xml)` at line 1591
- `HiveBtleService.handleAlertDocument()` calls `hiveBtle.sendEvent(EMERGENCY)` at line 347
- Also sends to TAK server (existing path preserved)

**Inbound SOS (HIVE → WearTAK)**: ✅ Complete (visual indicator)
- `HiveBtleService.onPeerEvent()` receives EMERGENCY events at line 162
- Sets `peer.isInEmergency = true` via `hiveBtleRepository.setPeerEmergency()`
- **SOS Button**: Flashes orange when any peer in emergency (MainScreen:2850-2875)
- **Peer List**: Shows red flashing "SOS" badge + status dot (HiveMeshActivity:254-267)

### HivePeripheral Event Types

```kotlin
enum class HiveEventType(val value: Int) {
    NONE(0),       // Normal sync, no event
    EMERGENCY(1),  // SOS initiated
    ACK(2),        // SOS acknowledged
    PING(3)        // Keep-alive / presence check
}
```

### Implementation Tasks

- [x] **8.1** Complete inbound emergency handling
  - [x] In `onPeerEvent(peer, EMERGENCY)`, update peer emergency state
  - [x] `HiveBtleRepository.setPeerEmergency(nodeId, true, timestamp)`
  - [x] Visual alert: SOS button flashes orange
  - [x] Peer list shows "SOS" badge + red flashing dot
  - [ ] *(Future)* Audio alert (alarm sound, vibration)
  - [ ] *(Future)* Full-screen overlay with peer details

- [ ] **8.2** *(Deferred)* Implement ACK transmission
  - ACK complexity deferred - basic awareness is sufficient for MVP
  - The person in distress knows their SOS was sent
  - Team sees flashing SOS indicator

- [ ] **8.3** *(Deferred)* ACK reception and display

- [ ] **8.4** Emergency state persistence
  - [x] Track active emergencies in `HiveBtleRepository.peersInEmergency`
  - [ ] *(Future)* Survive app restart (persist to DataStore)
  - [ ] *(Future)* Auto-clear stale emergencies

- [ ] **8.5** Emergency cancellation
  - [ ] *(Future)* Support cancel propagation over mesh

### Key Files

| File | Purpose | Line References |
|------|---------|-----------------|
| `SosActivity.kt` | Emergency trigger UI | Line 293 (`SOSButtonEmergencyMessage`) |
| `CotDispatchService.kt` | Emergency dispatch | Lines 922, 1547-1603 |
| `HiveBtleService.kt` | HIVE emergency handling | Lines 162-168 (`onPeerEvent`) |
| `HiveBtleRepository.kt` | Emergency state tracking | `setPeerEmergency()`, `peersInEmergency` |
| `MainActivity.kt` | SOS button flash | Lines 2850-2875 (`SOSButton`) |
| `HiveMeshActivity.kt` | Peer list SOS indicator | Lines 207-224, 254-267 (`PeerCard`) |

---

## Phase 9: Map Marker Sync

> **Objective**: Synchronize user-dropped map markers across HIVE mesh for shared situational awareness.

**Status**: ✅ Complete
**Completed**: 2026-01-14
**Depends On**: Phase 4.5 (mapitem extended payload)

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         MAP MARKER FLOW                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  DROP (Device A creates marker):                                             │
│  ┌──────────────┐    ┌────────────────┐    ┌─────────────────┐              │
│  │ MarkerActivity│───▶│CotDispatchSvc  │───▶│ HiveBtleService │              │
│  │ (drop marker)│    │ sendDropNow()  │    │ handleMapItem() │              │
│  └──────────────┘    └────────────────┘    └─────────────────┘              │
│         │                    │                      │                        │
│         ▼                    ▼                      ▼                        │
│  DropRequest            CoT XML              HiveMarker.encode()            │
│  (type, time)           + EventBus           → sendMarker()                 │
│                                                                              │
│  RECEIVE (Device B gets marker):                                             │
│  ┌──────────────┐    ┌────────────────┐    ┌─────────────────┐              │
│  │ HiveBtle     │───▶│ HiveBtleService│───▶│ EventBus        │              │
│  │onMarkerSynced│    │ (decode marker)│    │ HiveDocEvent    │              │
│  └──────────────┘    └────────────────┘    └─────────────────┘              │
│         │                    │                      │                        │
│         ▼                    ▼                      ▼                        │
│  HiveMarker              CoT XML             Display on map                 │
│  (0xAC format)           compactMarkerToCot  (ATAK / local UI)              │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Marker Wire Format (0xAC)

Markers use a dedicated document format separate from track/delta documents:

```
┌──────────────────────────────────────────────────────────────────┐
│ MARKER DOCUMENT (0xAC format)                                     │
├──────────────────────────────────────────────────────────────────┤
│ Offset │ Field      │ Type  │ Size │ Description                 │
├──────────────────────────────────────────────────────────────────┤
│   0    │ marker     │ u8    │ 1    │ 0xAC = marker document      │
│   1    │ nodeId     │ u32   │ 4    │ Sender node ID (LE)         │
│   5    │ count      │ u16   │ 2    │ Number of markers (LE)      │
│   7    │ markers[]  │ var   │ var  │ Array of HiveMarker data    │
└──────────────────────────────────────────────────────────────────┘
```

### HiveMarker Structure (~84 bytes typical)

Each marker in the array:

```
┌──────────────────────────────────────────────────────────────────┐
│ HIVE MARKER (~84 bytes)                                           │
├──────────────────────────────────────────────────────────────────┤
│ Offset │ Field     │ Type  │ Size │ Description                  │
├──────────────────────────────────────────────────────────────────┤
│   0    │ uidLen    │ u8    │ 1    │ Length of UID string         │
│   1    │ uid       │ [u8]  │ ≤36  │ UUID string                  │
│   1+L1 │ typeLen   │ u8    │ 1    │ Length of type string        │
│   2+L1 │ type      │ [u8]  │ ≤12  │ CoT type (e.g., "a-f-G")     │
│  2+L1+L2│ lat      │ f32   │ 4    │ Latitude (IEEE 754 LE)       │
│  6+L1+L2│ lon      │ f32   │ 4    │ Longitude (IEEE 754 LE)      │
│ 10+L1+L2│ hae      │ f32   │ 4    │ Altitude (meters, LE)        │
│ 14+L1+L2│ csLen    │ u8    │ 1    │ Length of callsign           │
│ 15+L1+L2│ callsign │ [u8]  │ ≤16  │ Marker label                 │
│  last 8│ timestamp │ u64   │ 8    │ Creation time (ms epoch, LE) │
└──────────────────────────────────────────────────────────────────┘
```

### Marker Types (MIL-STD-2525C)

| Type | CoT Type | Icon | Usage |
|------|----------|------|-------|
| FRIENDLY | `a-f-G` | Blue square | Friendly unit/position |
| UNKNOWN | `a-u-G` | Yellow diamond | Unknown affiliation |
| HOSTILE | `a-h-G` | Red diamond | Hostile/threat |
| NEUTRAL | `a-n-G` | Green square | Neutral party |
| WAYPOINT | `b-m-p-w` | Circle | Navigation waypoint |

### API Reference

**HiveBtle.kt:**
```kotlin
// Send a marker to all connected peers
fun sendMarker(marker: HiveMarker)

// Data class matching CotHiveTranslator.CompactMarker
data class HiveMarker(
    val uid: String,        // 36 bytes max (UUID)
    val type: String,       // 12 bytes max (CoT type)
    val lat: Float,         // IEEE 754 LE
    val lon: Float,         // IEEE 754 LE
    val hae: Float,         // Altitude meters
    val callsign: String,   // 16 bytes max
    val time: Long          // ms since epoch
)
```

**HiveMeshListener:**
```kotlin
// Called when a marker is received from a peer
fun onMarkerSynced(peer: HivePeer, marker: HiveMarker) {}
```

### Implementation Tasks ✅

- [x] **9.1** Complete outbound marker encoding
  - [x] In `handleMapItemDocument()`, parse CoT via `CotHiveTranslator.parseCotXml()`
  - [x] Convert to `HiveMarker` and call `hiveBtle.sendMarker()`
  - [x] Uses dedicated 0xAC document format (not embedded in track document)

- [x] **9.2** Implement inbound marker decoding
  - [x] `handlePeerMarkerDocument()` detects 0xAC marker
  - [x] `HiveMarker.decode()` parses marker data
  - [x] `onMarkerSynced()` callback to HiveMeshListener

- [x] **9.3** WearTAK integration
  - [x] `HiveBtleService.onMarkerSynced()` converts HiveMarker → CoT XML
  - [x] Posts `HiveDocumentEvent("mapitem_rx", cotXml)` to EventBus
  - [x] UI can subscribe to receive mesh markers

- [ ] **9.4** Marker lifecycle management (future)
  - [ ] Handle marker updates (same UID, new position)
  - [ ] Handle marker deletions (clear CoT message)
  - [ ] Stale marker cleanup

### Key Files

| File | Purpose | Line References |
|------|---------|-----------------|
| `HiveBtle.kt` | `HiveMarker` class, `sendMarker()`, `handlePeerMarkerDocument()` | Lines 3064-3198, 1230-1262, 1541-1584 |
| `HiveBtleService.kt` | Marker handling, `onMarkerSynced()` | Lines 398-424, 266-295 |
| `CotHiveTranslator.kt` | CoT parsing, `compactMarkerToCotXml()` | Lines 276-291 |
| `CotDispatchService.kt` | Marker drop handling | Lines 329-342, 1712-1753 |

---

## Phase 10: Delta-Only Transmission

> **Objective**: Minimize BLE bandwidth by transmitting only changed fields, not full documents.

**Status**: ✅ Complete (implemented in hive-btle 0.0.10)
**Completed**: 2026-01-14

### Problem Statement

Current implementation broadcasts full HiveDocument on each sync:
- 96 bytes minimum (with location)
- Redundant data when only position changes
- Wastes battery on unnecessary radio time

### Proposed Solution: Field-Level Deltas

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         DELTA ENCODING STRATEGY                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  FULL DOCUMENT (first sync or reconnect):                                    │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │ Header │ GCounter │ Peripheral (full) │ Location │ Timestamp       │    │
│  │ 8 bytes│ 12+12*N  │ 26 bytes          │ 12 bytes │ 8 bytes         │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  DELTA DOCUMENT (subsequent syncs):                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │ Header │ Delta Flags │ Changed Fields Only │ Timestamp             │    │
│  │ 8 bytes│ 2 bytes     │ Variable            │ 8 bytes               │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  DELTA FLAGS (bitmask):                                                      │
│  ┌──────────────────────────────────────────────────────────────────┐       │
│  │ Bit │ Field           │ Size if Set │                            │       │
│  ├──────────────────────────────────────────────────────────────────┤       │
│  │  0  │ location        │ 12 bytes    │ lat/lon/alt changed        │       │
│  │  1  │ battery         │ 1 byte      │ battery % changed          │       │
│  │  2  │ heartRate       │ 1 byte      │ HR changed                 │       │
│  │  3  │ activity        │ 1 byte      │ activity level changed     │       │
│  │  4  │ alerts          │ 1 byte      │ alert flags changed        │       │
│  │  5  │ event           │ 9 bytes     │ new event (SOS/ACK)        │       │
│  │  6  │ callsign        │ 12 bytes    │ callsign changed (rare)    │       │
│  │  7  │ extended        │ variable    │ chat/marker payload        │       │
│  │ 8-15│ reserved        │ -           │ future use                 │       │
│  └──────────────────────────────────────────────────────────────────┘       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Bandwidth Savings Estimate

| Scenario | Full Doc | Delta Doc | Savings |
|----------|----------|-----------|---------|
| Position only | 96 bytes | 22 bytes | **77%** |
| Position + HR | 96 bytes | 23 bytes | **76%** |
| Battery only | 96 bytes | 11 bytes | **89%** |
| Event (SOS) | 96 bytes | 19 bytes | **80%** |

### Implementation Tasks (hive-btle library) ✅ COMPLETE

- [x] **10.1** Add delta encoding to HiveDocument
  - [x] `PeerSyncState` class tracks per-peer: lastSentTimestamp, lastSentPeripheral, lastSentCounterValue, syncCount
  - [x] `peerSyncState: ConcurrentHashMap<Long, PeerSyncState>` in HiveBtle class
  - [x] `buildSyncDocumentForPeer()` computes delta or full based on what changed
  - [x] `peripheralChanged()` detects location, health, event, callsign changes

- [x] **10.2** Add delta decoding
  - [x] `HiveDeltaDocument.isDeltaDocument()` checks for 0xB2 marker
  - [x] `HiveDeltaDocument.decode()` parses operations by type
  - [x] `handlePeerDeltaDocument()` applies operations to local state
  - [x] Creates synthetic HiveDocument for mesh listener callbacks

- [x] **10.3** Optimize sync scheduler
  - [x] First sync always sends full document (syncCount == 0)
  - [x] Every 10th sync sends full document (FULL_SYNC_INTERVAL = 10)
  - [x] Returns null (skip sync) if nothing changed - 100% savings
  - [x] Emergency events bypass delta and clear peer state

### Implementation Tasks (WearTAK)

- [x] **10.4** Update HiveBtleService for delta support
  - [x] No changes required - HiveBtle handles delta internally
  - [x] Delta encoding is transparent to WearTAK
  - [x] Logcat shows `[FULL]`, `[DELTA]`, `[SKIP]` for monitoring

### Key Files Modified

| File | Changes |
|------|---------|
| `hive-btle/android/.../HiveBtle.kt` | +400 lines: delta classes, per-peer tracking, encoding/decoding |

### Wire Format Implemented (0xB2)

```
Header (16 bytes):
  marker:       1 byte (0xB2)
  flags:        1 byte (bit 0: hasVectorClock, bit 1: isResponse)
  origin_node:  4 bytes (LE)
  timestamp_ms: 8 bytes (LE)
  op_count:     2 bytes (LE)

Operations (variable):
  0x01 IncrementCounter: counterId(1) + nodeId(4) + amount(8) + timestamp(8) = 21 bytes
  0x02 UpdatePeripheral: timestamp(8) + len(2) + peripheral(34-56) = ~60 bytes (legacy/full)
  0x03 SetEmergency:     source(4) + timestamp(8) + peerCount(1) + peers(N*4)
  0x04 AckEmergency:     nodeId(4) + emergencyTimestamp(8) = 12 bytes
  0x05 ClearEmergency:   emergencyTimestamp(8) = 8 bytes

  FIELD-LEVEL DELTAS (bandwidth efficient):
  0x10 UpdateLocation:   lat(4) + lon(4) + alt(4) = 12 bytes
  0x11 UpdateHealth:     battery(1) + hasHR(1) + [hr(1)] + activity(1) + alerts(1) = 4-5 bytes
  0x12 UpdateCallsign:   len(1) + callsign(1-12) = 2-13 bytes
  0x13 UpdateEvent:      eventType(1) + timestamp(8) = 9 bytes
```

### Actual Bandwidth Savings (Field-Level Deltas)

| Scenario | Full Doc | Delta Doc | Savings |
|----------|----------|-----------|---------|
| Location only | 96 bytes | ~31 bytes | **68%** |
| Health only | 96 bytes | ~24 bytes | **75%** |
| Event only | 96 bytes | ~28 bytes | **71%** |
| Callsign only | 96 bytes | ~26 bytes | **73%** |
| Counter only | 96 bytes | 37 bytes | **61%** |
| Nothing changed | 96 bytes | 0 bytes | **100%** |
| Emergency | Full doc always | N/A | 0% (intentional) |

**Key Improvement**: Previous coarse delta sent entire peripheral (~60 bytes) on any field change.
New field-level deltas send only the changed field (12-13 bytes typical), achieving 68-75% savings.

### Testing & Verification

**Logcat Monitoring:**
```bash
# Filter for delta sync activity
adb logcat | grep -E "HiveBtle.*\[(FULL|DELTA|SKIP|DELTA-RX)\]"

# Expected output patterns:
# D HiveBtle: [FULL] Peer 12345678: 96 bytes (sync #1)      <- First sync
# D HiveBtle: [DELTA] Peer 12345678: 76 bytes, 1 ops (sync #2)  <- Position changed
# V HiveBtle: [SKIP] Peer 12345678: no changes             <- Nothing to send
# D HiveBtle: [DELTA-RX] From HIVE_DEMO_87654321: 2 ops    <- Received delta
```

**Verification Checklist:**
- [ ] First sync to new peer shows `[FULL]`
- [ ] Subsequent syncs with changes show `[DELTA]`
- [ ] Syncs without changes show `[SKIP]`
- [ ] Every 10th sync shows `[FULL]` (consistency check)
- [ ] Emergency events show `[EMERGENCY - FULL DOCUMENT]`
- [ ] Received deltas show `[DELTA-RX]` with op count

---

## Phase 11: Multi-Hop Mesh Routing

> **Objective**: Extend mesh coverage beyond direct BLE range via message forwarding.

**Status**: 🔲 Not Started (requires hive-btle library support)
**Depends On**: hive-btle 0.2.x with routing layer

### Problem Statement

Current BLE range: ~30-50 meters (urban), ~100 meters (open)
Team spread: May exceed direct BLE connectivity
Need: Messages reach all team members regardless of physical topology

### Multi-Hop Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         MULTI-HOP TOPOLOGY                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│     [Device A]◄──────────────►[Device B]◄──────────────►[Device C]          │
│         │                         │                         │                │
│         │      BLE Range          │      BLE Range          │                │
│         │       ~50m              │       ~50m              │                │
│         │                         │                         │                │
│         └─────────────────────────┼─────────────────────────┘                │
│                                   │                                          │
│                           Device B forwards                                  │
│                           A↔C messages                                       │
│                                                                              │
│  MESSAGE PATH (A → C):                                                       │
│  ┌──────┐         ┌──────┐         ┌──────┐                                 │
│  │  A   │──msg──▶│  B   │──fwd──▶│  C   │                                 │
│  │origin│        │relay │        │dest  │                                 │
│  └──────┘         └──────┘         └──────┘                                 │
│     │                │                │                                      │
│     ▼                ▼                ▼                                      │
│  hop=0            hop=1            received                                  │
│  TTL=3            TTL=2            (delivered)                               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Routing Header

```
┌──────────────────────────────────────────────────────────────────┐
│ ROUTING HEADER (8 bytes, prepended to HiveDocument)              │
├──────────────────────────────────────────────────────────────────┤
│ Offset │ Field      │ Type │ Description                         │
├──────────────────────────────────────────────────────────────────┤
│   0    │ originNode │ u32  │ Original sender's nodeId            │
│   4    │ hopCount   │ u8   │ Number of hops traversed            │
│   5    │ ttl        │ u8   │ Time-to-live (max hops remaining)   │
│   6    │ msgType    │ u8   │ 0=broadcast, 1=unicast, 2=geo       │
│   7    │ reserved   │ u8   │ Future use                          │
└──────────────────────────────────────────────────────────────────┘
```

### Routing Modes

| Mode | msgType | Description | Use Case |
|------|---------|-------------|----------|
| Broadcast | 0 | Flood to all reachable nodes | PLI, SOS |
| Unicast | 1 | Targeted to specific nodeId | Chat DM |
| Geo-fence | 2 | Nodes within radius of origin | Local alerts |

### Implementation Tasks (hive-btle library)

- [ ] **11.1** Add routing header to HiveDocument
  - [ ] Prepend 8-byte routing header
  - [ ] Track origin vs relay node
  - [ ] Implement TTL decrement

- [ ] **11.2** Implement forwarding logic
  - [ ] On receive: check if self is destination
  - [ ] If not destination and TTL > 0: forward
  - [ ] Deduplication (seen message cache)

- [ ] **11.3** Implement routing table
  - [ ] Track known nodes and next-hop
  - [ ] Age out stale routes
  - [ ] Prefer shortest path

### Implementation Tasks (WearTAK)

- [ ] **11.4** Update HiveBtleService for multi-hop
  - [ ] Configure default TTL (suggest: 3)
  - [ ] Handle forwarded messages same as direct
  - [ ] UI indicator for hop count (optional)

- [ ] **11.5** Power optimization for relaying
  - [ ] Battery threshold for relay participation
  - [ ] Disable relay when battery < 20%
  - [ ] Configurable relay policy

### Success Criteria

| Metric | Target | Measurement |
|--------|--------|-------------|
| Max Hops | 3+ | Message reaches 3-hop distant node |
| Latency/Hop | <2 seconds | Time added per relay |
| Dedup Rate | >99% | No duplicate delivery to destination |
| Battery Impact | <5% additional | Relay duty vs non-relay |

---

## Android-ATAK Plugin: Delta Integration Guide

> **Audience**: Android-ATAK plugin development team
> **Required hive-btle version**: 0.0.10+

### Architecture: You Are a Mesh Node

The Android phone running the ATAK plugin is **not** a server receiving processed data—it's another
node in the BLE mesh, directly communicating with watches:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         MESH TOPOLOGY                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│         Watch A ◄────[BLE delta]────► Watch B                                │
│             ▲                             ▲                                   │
│             │                             │                                   │
│        [BLE delta]                   [BLE delta]                             │
│             │                             │                                   │
│             └──────────► Android Phone ◄──┘                                  │
│                          (ATAK Plugin)                                       │
│                                                                              │
│   ALL nodes send/receive DELTA documents (0xB2), not full documents          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### What This Means For Your Plugin

1. **You receive incremental updates**, not complete state snapshots
2. **You must maintain per-peer state** to reconstruct full peer information
3. **Updates arrive field-by-field**, not as complete documents
4. **A peer may have location but no health data** (yet) - handle gracefully

### Delta Protocol Overview

Starting with hive-btle 0.0.10, nodes send **field-level deltas** instead of full documents:

| Sync Type | When Used | Marker Byte |
|-----------|-----------|-------------|
| Full Document | First sync, every 10th sync, after emergency | 0xAB |
| Delta Document | All other syncs | 0xB2 |

**First byte of received data determines format:**
```kotlin
when (data[0]) {
    0xB2.toByte() -> handleDeltaDocument(data)  // Field-level updates
    else -> handleFullDocument(data)             // Complete state
}
```

### Field-Level Delta Operations

Delta documents contain one or more operations. Each operation updates a **single field**:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    DELTA OPERATION TYPES (0xB2 format)                       │
├──────────┬────────┬─────────────────────────────────────────────────────────┤
│ Op Code  │ Size   │ Description                                              │
├──────────┼────────┼─────────────────────────────────────────────────────────┤
│ 0x10     │ 12 B   │ UpdateLocation: lat(f32) + lon(f32) + alt(f32)           │
│ 0x11     │ 4-5 B  │ UpdateHealth: battery(u8) + hasHR(u8) + [hr(u8)] +       │
│          │        │               activity(u8) + alerts(u8)                  │
│ 0x12     │ 2-13 B │ UpdateCallsign: len(u8) + callsign(1-12 bytes)           │
│ 0x13     │ 9 B    │ UpdateEvent: eventType(u8) + timestamp(u64)              │
├──────────┼────────┼─────────────────────────────────────────────────────────┤
│ 0x01     │ 21 B   │ IncrementCounter: CRDT counter update                    │
│ 0x02     │ ~60 B  │ UpdatePeripheral: Full peripheral (legacy fallback)      │
│ 0x03     │ var    │ SetEmergency: SOS broadcast                              │
│ 0x04     │ 12 B   │ AckEmergency: SOS acknowledgment                         │
│ 0x05     │ 8 B    │ ClearEmergency: SOS cancelled                            │
└──────────┴────────┴─────────────────────────────────────────────────────────┘
```

### State Management Pattern

Your plugin must maintain state for each peer and apply deltas incrementally:

```kotlin
// Per-peer state - you build this up over time from deltas
data class PeerState(
    val nodeId: Long,
    var location: Location? = null,      // Updated by 0x10
    var batteryPercent: Int? = null,     // Updated by 0x11
    var heartRate: Int? = null,          // Updated by 0x11
    var activityLevel: Int? = null,      // Updated by 0x11
    var callsign: String? = null,        // Updated by 0x12
    var lastEvent: Event? = null,        // Updated by 0x13
    var lastSeen: Long = 0
)

// Your state store
private val peerStates = ConcurrentHashMap<Long, PeerState>()

// On receiving delta document from peer
fun handleDeltaDocument(originNodeId: Long, operations: List<DeltaOperation>) {
    val peer = peerStates.getOrPut(originNodeId) { PeerState(originNodeId) }
    peer.lastSeen = System.currentTimeMillis()

    for (op in operations) {
        when (op) {
            is UpdateLocation -> {
                peer.location = Location(op.latitude, op.longitude, op.altitude)
            }
            is UpdateHealth -> {
                peer.batteryPercent = op.batteryPercent
                peer.heartRate = op.heartRate  // May be null
                peer.activityLevel = op.activityLevel
            }
            is UpdateCallsign -> {
                peer.callsign = op.callsign
            }
            is UpdateEvent -> {
                peer.lastEvent = Event(op.eventType, op.timestamp)
                // Trigger UI notification for SOS, ACK, etc.
                onPeerEvent(peer, op.eventType)
            }
        }
    }

    // Update your UI/map with potentially partial peer data
    onPeerStateChanged(peer)
}
```

### Handling Partial State

**Critical**: A peer's state builds up over multiple delta cycles. Handle missing fields:

```kotlin
// WRONG - assumes complete data
fun displayPeer(peer: PeerState) {
    mapView.addMarker(peer.location!!, peer.callsign!!)  // NPE if delta hasn't arrived
}

// CORRECT - handle partial state
fun displayPeer(peer: PeerState) {
    val location = peer.location ?: return  // Skip if no location yet
    val label = peer.callsign ?: "Node ${peer.nodeId.toString(16)}"  // Fallback
    mapView.addMarker(location, label)

    // Show battery only if we have it
    peer.batteryPercent?.let { battery ->
        markerOverlay.setBattery(battery)
    }
}
```

### SOS/Emergency Event Handling

Emergency events require special attention - they're critical for user safety. Here's the complete lifecycle:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     SOS EVENT LIFECYCLE                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│   Watch A triggers SOS                                                       │
│         │                                                                    │
│         ▼                                                                    │
│   eventType = EMERGENCY (3)  ───────►  All mesh nodes receive               │
│         │                                      │                             │
│         │                                      ▼                             │
│         │                              Show SOS indicator                    │
│         │                              (flashing badge, alert)               │
│         │                                                                    │
│   Watch A cancels SOS                                                        │
│         │                                                                    │
│         ▼                                                                    │
│   eventType = NONE (0)  ────────────►  All mesh nodes receive               │
│                                               │                              │
│                                               ▼                              │
│                                        Clear SOS indicator                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### HiveEventType Values

```kotlin
enum class HiveEventType(val value: Int) {
    NONE(0),       // Normal state / Cancel emergency
    EMERGENCY(3),  // SOS active
    ACK(6);        // Acknowledgment (optional)
}
```

#### Detecting Emergency Events

Emergency state comes via `UpdateEvent` (0x13) operation in delta documents, or in full document's peripheral:

```kotlin
// Track per-peer emergency state
data class PeerState(
    // ... other fields ...
    var isInEmergency: Boolean = false,
    var emergencyTimestamp: Long? = null
)

fun handleDeltaOperation(peer: PeerState, op: DeltaOperation) {
    when (op) {
        is UpdateEvent -> {
            val wasInEmergency = peer.isInEmergency

            when (op.eventType) {
                HiveEventType.EMERGENCY -> {
                    peer.isInEmergency = true
                    peer.emergencyTimestamp = op.timestamp

                    if (!wasInEmergency) {
                        // NEW emergency - trigger alert UI
                        onPeerEmergencyStarted(peer)
                    }
                }
                HiveEventType.NONE -> {
                    if (wasInEmergency) {
                        // Emergency CANCELLED
                        peer.isInEmergency = false
                        peer.emergencyTimestamp = null
                        onPeerEmergencyCancelled(peer)
                    }
                }
                HiveEventType.ACK -> {
                    // Optional: track who acknowledged the SOS
                    onPeerAcknowledged(peer)
                }
            }
        }
        // ... handle other operations ...
    }
}
```

#### Emergency from Full Documents

When receiving a full document (0xAB marker), check the peripheral's event type:

```kotlin
fun handleFullDocument(doc: HiveDocument) {
    val peer = getOrCreatePeer(doc.nodeId)
    val wasInEmergency = peer.isInEmergency

    // Full documents contain peripheral with event info
    val eventType = doc.peripheral?.lastEvent?.eventType ?: HiveEventType.NONE

    peer.isInEmergency = (eventType == HiveEventType.EMERGENCY)
    peer.emergencyTimestamp = if (peer.isInEmergency) {
        doc.peripheral?.lastEvent?.timestamp
    } else null

    // Detect state transitions
    if (peer.isInEmergency && !wasInEmergency) {
        onPeerEmergencyStarted(peer)
    } else if (!peer.isInEmergency && wasInEmergency) {
        onPeerEmergencyCancelled(peer)
    }

    // Update other peer fields...
}
```

#### Displaying Emergency State

```kotlin
// In your UI layer - observe peers in emergency
val peersInEmergency: List<PeerState>
    get() = peerStates.values.filter { it.isInEmergency }

// Map marker with SOS indicator
fun updateMapMarker(peer: PeerState) {
    val marker = getOrCreateMarker(peer.nodeId)

    if (peer.isInEmergency) {
        marker.icon = sosIcon  // Red flashing icon
        marker.label = "${peer.callsign ?: "Unknown"} - SOS!"
        // Consider: play alert sound, vibrate, show notification
    } else {
        marker.icon = normalIcon
        marker.label = peer.callsign ?: "Node ${peer.nodeId.toString(16)}"
    }
}

// Global SOS indicator (e.g., toolbar badge)
fun updateGlobalSosIndicator() {
    val emergencyCount = peersInEmergency.size
    if (emergencyCount > 0) {
        sosButton.visibility = VISIBLE
        sosButton.text = "SOS ($emergencyCount)"
        sosButton.startFlashingAnimation()
    } else {
        sosButton.visibility = GONE
        sosButton.stopFlashingAnimation()
    }
}
```

#### Important: Preserve Emergency State

When updating peer data from mesh updates, **preserve the emergency state**:

```kotlin
fun updatePeerFromMesh(newPeerInfo: PeerInfo) {
    val existing = peerStates[newPeerInfo.nodeId]

    if (existing != null) {
        // PRESERVE emergency state - don't overwrite with default false
        peerStates[newPeerInfo.nodeId] = newPeerInfo.copy(
            isInEmergency = existing.isInEmergency,
            emergencyTimestamp = existing.emergencyTimestamp
        )
    } else {
        peerStates[newPeerInfo.nodeId] = newPeerInfo
    }
}
```

#### Testing SOS Flow

```bash
# On Watch 1: Enable SOS
# Watch logs:
adb logcat | grep -i "Emergency broadcast sent"

# On Watch 2 / ATAK device: Should see
adb logcat | grep -iE "PEER SOS|emergency|UpdateEvent.*EMERGENCY"

# Expected log sequence on receiving device:
# I HiveBtle: New event from HIVE_WEARTAK-XXXXXXXX: EMERGENCY
# I WearTAK: Peer event from HIVE_WEARTAK-XXXXXXXX: EMERGENCY
# D HiveBtleRepo: setPeerEmergency(XXXXXXXX, true)

# On Watch 1: Cancel SOS
# Watch 1 logs:
adb logcat | grep -i "Emergency CANCELLED broadcast sent"

# Watch 2 / ATAK should see:
# I HiveBtle: Received document from XXXXXXXX, event=NONE
# I WearTAK: Peer XXXXXXXX emergency cancelled (now sending NONE)
# D HiveBtleRepo: setPeerEmergency(XXXXXXXX, false)
```

### Typical Delta Scenarios

| Scenario | What You Receive | Your Action |
|----------|------------------|-------------|
| New peer discovered | Full document (0xAB) | Initialize complete PeerState |
| Peer moved | UpdateLocation (0x10) only | Update location field only |
| Peer battery changed | UpdateHealth (0x11) only | Update health fields only |
| Peer sent SOS | UpdateEvent (0x13) with EMERGENCY | Set isInEmergency=true, trigger alert |
| Peer cancelled SOS | UpdateEvent (0x13) with NONE | Set isInEmergency=false, clear alert |
| Peer stationary, no changes | Nothing (sync skipped) | No action needed |
| Every 10th sync | Full document (0xAB) | Refresh entire PeerState |

### Bandwidth Savings

Field-level deltas dramatically reduce BLE bandwidth:

| Change | Full Doc | Delta | Savings |
|--------|----------|-------|---------|
| Position only | 96 bytes | 31 bytes | **68%** |
| Health only | 96 bytes | 24 bytes | **75%** |
| Event only | 96 bytes | 28 bytes | **71%** |
| Nothing changed | 96 bytes | 0 bytes | **100%** |

### Testing Your Delta Integration

```bash
# Monitor delta traffic from watches
adb logcat | grep -E "HiveBtle.*\[(FULL|DELTA|SKIP|DELTA-RX)\]"

# Expected patterns:
# D HiveBtle: [FULL] Peer 12345678: 96 bytes (sync #1)       <- First sync
# D HiveBtle: [DELTA] Peer 12345678: 31 bytes (1 ops)       <- Location update
# D HiveBtle: [SKIP] Peer 12345678: nothing changed          <- No transmission
# D HiveBtle: [DELTA-RX] From HIVE-ABCD: 2 ops              <- Received delta
```

### Checklist for ATAK Plugin Team

**Core Delta Integration:**
- [ ] Update hive-btle dependency to 0.0.10+
- [ ] Implement per-peer state storage (`ConcurrentHashMap<Long, PeerState>`)
- [ ] Handle both full documents (0xAB) and delta documents (0xB2)
- [ ] Apply field-level operations incrementally to peer state
- [ ] Handle null/missing fields in UI gracefully
- [ ] Test with watches sending real deltas

**SOS/Emergency Integration:**
- [ ] Add `isInEmergency` and `emergencyTimestamp` to peer state
- [ ] Handle UpdateEvent (0x13) with EMERGENCY event type
- [ ] Handle UpdateEvent (0x13) with NONE event type (cancellation)
- [ ] Preserve emergency state when updating peer data from mesh
- [ ] Display SOS indicator on map markers for peers in emergency
- [ ] Add global SOS indicator (badge/alert) when any peer is in emergency
- [ ] Test SOS enable → propagate → cancel flow with watches

**Map Marker Integration:**
- [ ] Handle marker documents (0xAC) separately from track documents
- [ ] Implement `onMarkerSynced()` callback in your `HiveMeshListener`
- [ ] Store received markers in local cache by UID
- [ ] Convert `HiveMarker` to ATAK-compatible format for display
- [ ] Handle marker updates (same UID, new data)
- [ ] Test marker drop on watch → display in ATAK flow

---

## Android-ATAK Plugin: Map Marker Integration Guide

> **Audience**: Android-ATAK plugin development team
> **Required hive-btle version**: 0.0.10+

### Overview

Markers are user-dropped map points (waypoints, POIs, hostile indicators) that sync across the mesh.
Unlike tracks (which update continuously), markers are event-driven and use a dedicated wire format.

### Document Type Detection

Markers use the **0xAC** marker byte, distinct from tracks (0xAB) and deltas (0xB2):

```kotlin
fun handleIncomingDocument(data: ByteArray) {
    when (data[0]) {
        0xAC.toByte() -> handleMarkerDocument(data)   // Map markers
        0xB2.toByte() -> handleDeltaDocument(data)    // Track deltas
        else -> handleFullDocument(data)               // Full track (0xAB implied)
    }
}
```

### Receiving Markers via Callback

The simplest approach is implementing `onMarkerSynced()` in your `HiveMeshListener`:

```kotlin
class AtakHiveListener : HiveMeshListener {

    // Store received markers by UID for deduplication and updates
    private val markerCache = ConcurrentHashMap<String, HiveMarker>()

    override fun onMarkerSynced(peer: HivePeer, marker: HiveMarker) {
        Log.i(TAG, "Marker from ${peer.displayName()}: ${marker.uid} at (${marker.lat}, ${marker.lon})")

        // Check if this is an update to existing marker
        val existing = markerCache[marker.uid]
        if (existing != null && existing.time >= marker.time) {
            // Ignore older update
            return
        }

        // Cache the marker
        markerCache[marker.uid] = marker

        // Convert to ATAK format and display
        displayMarkerOnMap(marker, peer)
    }

    private fun displayMarkerOnMap(marker: HiveMarker, sourcePeer: HivePeer) {
        // Convert HiveMarker to ATAK CoT or native format
        val cotXml = buildMarkerCot(marker)

        // Inject into ATAK map display
        atakMapView.addCotMarker(cotXml)
    }
}
```

### Building CoT XML from HiveMarker

```kotlin
fun buildMarkerCot(marker: HiveMarker): String {
    val staleTime = marker.time + 300_000  // 5 minute stale
    val timeIso = Instant.ofEpochMilli(marker.time).toString()
    val staleIso = Instant.ofEpochMilli(staleTime).toString()

    return """
        <event version="2.0" uid="${marker.uid}" type="${marker.type}"
               time="$timeIso" start="$timeIso" stale="$staleIso" how="m-g">
            <point lat="${marker.lat}" lon="${marker.lon}" hae="${marker.hae}"
                   ce="9999999" le="9999999"/>
            <detail>
                <contact callsign="${marker.callsign}"/>
                <remarks>Received via HIVE mesh</remarks>
            </detail>
        </event>
    """.trimIndent()
}
```

### Manual Marker Document Parsing

If you need to parse marker documents directly (without the callback):

```kotlin
fun parseMarkerDocument(data: ByteArray): List<HiveMarker> {
    if (data.isEmpty() || data[0] != 0xAC.toByte()) {
        return emptyList()
    }

    val markers = mutableListOf<HiveMarker>()
    var offset = 1

    // Read source nodeId (4 bytes LE)
    val sourceNodeId = readU32LE(data, offset)
    offset += 4

    // Read marker count (2 bytes LE)
    val markerCount = readU16LE(data, offset).toInt()
    offset += 2

    // Parse each marker
    for (i in 0 until markerCount) {
        val (marker, newOffset) = HiveMarker.decode(data, offset)
        if (marker != null) {
            markers.add(marker)
            offset = newOffset
        } else {
            break  // Malformed data
        }
    }

    return markers
}
```

### Marker Types to Icon Mapping

Map CoT types to ATAK icons:

```kotlin
fun getIconForMarkerType(cotType: String): AtakIcon {
    return when {
        cotType.startsWith("a-f") -> AtakIcon.FRIENDLY  // Blue
        cotType.startsWith("a-h") -> AtakIcon.HOSTILE   // Red
        cotType.startsWith("a-n") -> AtakIcon.NEUTRAL   // Green
        cotType.startsWith("a-u") -> AtakIcon.UNKNOWN   // Yellow
        cotType.startsWith("b-m-p-w") -> AtakIcon.WAYPOINT
        cotType.startsWith("b-m-p-s") -> AtakIcon.SPOT
        else -> AtakIcon.GENERIC_POINT
    }
}
```

### Sending Markers (ATAK → Mesh)

To send markers from ATAK to watches:

```kotlin
fun sendMarkerToMesh(atakMarker: AtakMapMarker) {
    val hiveMarker = HiveMarker(
        uid = atakMarker.uid,
        type = atakMarker.cotType,
        lat = atakMarker.latitude.toFloat(),
        lon = atakMarker.longitude.toFloat(),
        hae = atakMarker.altitude.toFloat(),
        callsign = atakMarker.title ?: "Marker",
        time = System.currentTimeMillis()
    )

    hiveBtle.sendMarker(hiveMarker)
}
```

### Testing Marker Flow

```bash
# On Watch: Drop a marker, then check logcat
adb logcat | grep -i "MapItem marker sent"

# On ATAK device: Should see
adb logcat | grep -iE "MARKER-RX|onMarkerSynced"

# Expected log sequence:
# I HiveBtle: Broadcasting marker: uid=abc-123, callsign=HostilePos
# I HiveBtle: [MARKER-RX] From HIVE_WEARTAK-XXXXXXXX: 1 markers
# D YourPlugin: Marker from HIVE_WEARTAK-XXXXXXXX: abc-123 at (33.7, -84.4)
```

### Marker Checklist

- [ ] Implement `onMarkerSynced()` in your `HiveMeshListener`
- [ ] Create marker cache (`ConcurrentHashMap<String, HiveMarker>`)
- [ ] Handle marker deduplication by UID + timestamp
- [ ] Convert `HiveMarker` to ATAK CoT format
- [ ] Map CoT types to appropriate icons
- [ ] Display markers on ATAK map
- [ ] Test watch → ATAK marker flow
- [ ] (Optional) Implement ATAK → watch marker sending

---

## Open Questions

1. ~~**Publishing location**: GitHub Packages vs Maven Central vs private Artifactory?~~ → **Resolved**: Composite build for dev, local Maven or GitHub Packages for CI
2. ~~**Minimum Android API**~~ → **Resolved**: API 26 (Wear OS 3)
3. **Encryption key distribution**: How do users share mesh encryption keys?
4. **Companion app coordination**: Should HIVE_BTLE and COMPANION_APP coexist?
5. **Chat room semantics**: Should HIVE chat use "All Chat Rooms" or separate mesh room?
6. **SOS timeout**: How long before SOS auto-clears if not cancelled?
7. **Delta sync interval**: How often to force full document for consistency?
8. **Multi-hop TTL default**: What's the right balance between reach and battery?

---

## References

- [hive-btle library](../hive-btle/) - Rust BLE mesh implementation
- [hive-btle Android README](../hive-btle/android/README.md) - Android library integration guide
- [hive-btle JNI bridge](../hive-btle/src/platform/android/jni_bridge.rs) - Native method exports
- [DittoService.kt](app/src/main/java/com/aitech/weartak_civ/service/DittoService.kt) - Current implementation to replace
- [CompanionAppService.kt](app/src/main/java/com/aitech/weartak_civ/service/CompanionAppService.kt) - BLE patterns reference
