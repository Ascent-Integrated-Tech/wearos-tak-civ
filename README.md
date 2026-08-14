# WearTAK-CIV

WearTAK-CIV is a standalone Wear OS application that connects a watch to the Team Awareness Kit (TAK) ecosystem. It is designed to collect location and on-watch sensor data, publish Cursor-on-Target (CoT) events, and provide a watch-native interface for operational awareness and alerts.

The app can connect directly to a TAK server and can optionally use SITX, Ditto mesh, BLE companion, and plugin integrations when those capabilities are configured. A phone is not required for the app's primary watch workflow.

## What it does

- Connects to TAK endpoints and sends and receives CoT traffic.
- Publishes position/location information (PLI), markers, SOS/manual alerts, sensor-driven alerts, and TAK chat payloads.
- Collects health, activity, and environmental data, including heart rate, location, activity, skin temperature (where supported), BLE beacons, pressure, and off-body state.
- Provides Wear OS activities, a Tile, and watch-face complications for common operations and status.
- Supports managed configuration, allowing an enterprise mobility/MDM provider to apply settings and service enablement.
- Includes a configurable complex-event-processing (CEP) engine for evaluating sensor/event streams and dispatching alerts.
- Provides optional audio detection pipelines for gunshot and UAS events, and optional CRM inference.
- Hosts supported WearTAK plugins through the included plugin SDK contract.

Some capabilities depend on hardware, permissions, credentials, configuration, and separately provisioned services or SDKs. In particular, mesh, companion, SITX, plugin, audio-detection, and managed-configuration features are optional.

## Platform and requirements

This is a single-module Wear OS app (`:app`) with package ID `com.aitech.weartak_civ`.

- **Android API levels:** min SDK 34, target SDK 34, compile SDK 35
- **Java:** JDK 17
- **Device:** a Wear OS watch with GPS; BLE and telephony are optional
- **Target hardware:** Samsung Galaxy Watch 7/8 and Galaxy Watch Ultra 7/8 are the intended hardware family. Samsung-specific sensor features require compatible Samsung hardware and services.
- **Native libraries:** debug APKs are built for `armeabi-v7a`.

The project includes local binary dependencies for the Samsung Health Sensor API, HAIL SDK, and WearTAK plugin SDK. Keep these files available when building locally.

## Getting started

1. Install Android Studio and an Android SDK that includes API 35, build-tools 35.0.0, and the Android NDK 27.0.12077973.
2. Configure JDK 17 for Gradle.
3. If required for your environment, create `signing.properties` with the debug/release keystore values expected by `app/build.gradle`. The build also accepts the corresponding `WEARTAK_*` environment variables.
4. Build a debug APK:

   ```bash
   ./gradlew :app:assembleDebug
   ```

5. Install the generated `armeabi-v7a` APK on a connected watch:

   ```bash
   adb install -r app/build/outputs/apk/debug/*armeabi-v7a-debug.apk
   ```

6. Launch the app:

   ```bash
   adb shell am start -n com.aitech.weartak_civ/.presentation.MainActivity
   ```

The app requests permissions at runtime. On a physical device, grant only the permissions required for the services you intend to enable (for example, location, body sensors, activity recognition, notifications, Bluetooth, and microphone access).

## Configuration and operation

Configure the TAK endpoint and identity/certificates in the app before attempting to connect. Service enablement is persisted in `DataRepository` and centrally evaluated by `ReactiveServiceManager` and `ServiceConfiguration`; a service will run only when its setting, permissions, and prerequisite conditions are satisfied.

The app starts essential behavior through its application/bootstrap receivers and coordinates most long-running work as foreground services. This is intentional: direct TAK connectivity, location tracking, sensor collection, and optional integrations are service-driven rather than tied to a single UI screen.

Chat is an online feature; the project does not provide an offline TAK chat queue.

## Architecture

`WearTakApplication` initializes shared repositories and the CEP/plugin infrastructure. `DataRepository` is the main persistent state and settings hub, based on DataStore and Kotlin Flows. Newer components use Flow/StateFlow for reactive updates; some existing components communicate through EventBus.

The important runtime path is:

```text
Watch sensors, user actions, and integrations
                 |
                 v
       repositories and event streams
                 |
                 v
  ReactiveServiceManager / service layer
                 |
                 v
    CotDispatchService -> TAK/SITX/Ditto endpoints
```

Key areas of the source tree:

```text
app/src/main/java/com/aitech/weartak_civ/
├── presentation/       Wear OS activities and Compose UI
├── service/            foreground services, connectivity, and sensor orchestration
├── data/               DataStore-backed state, repositories, models, and secure storage
├── cep/                CEP rules, configuration, event bus, and alert actions
├── integration/        managed configuration and plugin-host integration
├── ipc/                CoT IPC models and serializers
├── complication/       watch-face complication providers
├── tile/               Wear OS Tile provider
├── receiver/           boot, update, and alarm receivers
├── events/             legacy EventBus event types
└── WearTakApplication.kt
```

## Development notes

- Use `./gradlew :app:assembleDebug` for a focused compile/package check.
- Enable optional services through the app settings or managed configuration; do not start a service directly without considering its `ServiceConfiguration` prerequisites.
- Runtime configuration defaults live in `Constants.kt` and persistence keys/state are in `data/DataRepository.kt`.
- CEP rules and related runtime configuration are under `app/src/main/res/raw/` and `cep/`.
- The repository currently has unit and instrumentation test dependencies, but it does not contain a broadly established test suite. Add targeted tests with feature changes where practical.

For live Android logs, use:

```bash
adb logcat
```

To inspect app-private files on a debuggable install:

```bash
adb shell run-as com.aitech.weartak_civ ls files
```

## Security and data handling

WearTAK-CIV handles potentially sensitive location, health, communications, and credential data. Treat device logs, exported app files, managed configurations, and build artifacts accordingly. Configure secure TAK endpoints and credentials appropriate to your deployment, and review required Android permissions before field use.

## Contributing

Unlimited Rights granted to TPC

Keep changes focused and validate them with the narrowest relevant Gradle task or test. When adding or changing a service, account for its manifest declaration, permission requirements, persisted enablement, and registration with the service orchestration layer.

### Point of Contact: Alex Gorsuch on chat.tak.gov or Signal

