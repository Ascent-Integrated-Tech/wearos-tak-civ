# WearTAK-CIV

WearTAK-CIV is a standalone Wear OS application that connects a watch to the Team Awareness Kit (TAK) ecosystem. It is designed to collect location and on-watch sensor data, publish Cursor-on-Target (CoT) events, and provide a watch-native interface for operational awareness and alerts.

The app can connect directly to a TAK server and can optionally use SITX, Ditto mesh, BLE companion, and plugin integrations when those capabilities are configured. A phone is not required for the app's primary watch workflow.

Configure Ditto under **Preferences > Tool Preferences > Plugins**. The switch enables or disables Ditto;
tap the Ditto row to select Community or configure a custom PDL in Ditto Identity. The row subtitle shows
Community or the saved custom PDL, truncated to fit. Ditto is off by default on fresh installs, and saved
enablement settings are preserved. PDL Help provides scrollable instructions for copying and pasting a custom identity.
Plugins are listed alphabetically, including installed plugins. Everywhere Comms, GoTenna and Somewear Labs are
unfunded placeholders: each displays **Unfunded Request**, and their switches remain off without activating any integration.
Sensor Manager displays **Unfunded Request** and has a saved enable/disable preference (off by default). Tap its row to open the preview
configuration showing **Unfunded Request**, **Button is Placeholder**, and a single inert **Scan** button.
The future workflow will list discovered sensors with per-sensor Pair controls and change the main action
to Unpair once paired. Enabling Sensor Manager does not start scanning or connect hardware yet.

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

## GitHub pipeline release

Protected `develop` push pipelines can publish the signed WearOS APK to the rolling
**TAK.gov CI/CD Pipeline Release** in the GitHub mirror. Project policy treats these
as stable builds, not prereleases. See [CI release setup and retention](ci/README.md)
for required administrator configuration, provenance, and verified replacement behavior.
Other numbered releases and unrelated assets are preserved.

## Platform and requirements

### WEARTAK-169: Bluetooth name is no longer changed

WearTAK no longer changes the watch's system Bluetooth name at startup, when applying
a callsign from the phone, or when restoring the watch's own settings. Companion BLE
discovery still uses the A11A service UUID, and advertising still includes the current
system device name; no `WT-` prefix is required. Bluetooth connection permissions remain
necessary for GATT and bond handling.

An existing name such as `WT-ODIN-ATAK` is not automatically restored because the original
name is unknown. Rename the watch manually in watch Settings if desired.

### Companion bond retention

Transient missing-key, failed BLE setup, and pre-session disconnect retries retain
the Android system bond and the matching phone-managed settings. The watch never
invokes `removeBond` during a retry. An active-peer missing-key event resets transport
readiness, RX/queued work, and session state before advertising again; unrelated-peer
events cannot reset the current session. Retained pairing is not message authorization:
current-peer and bonded-device checks still reject stale/unbonded writes.

A genuine Android `BOND_NONE` transition releases management only through the
repository's matching-peer operation; an active unpair also resets BLE. Startup still
checks whether the managing peer is actually bonded. This does not change one-time
provisioning, explicit ongoing management opt-in, chat, marker deletion, or watch UI.

`CompanionBondRetentionTest` exercises transient retry versus explicit unpair,
management retention, unrelated-peer isolation, and stale/unbonded write rejection.
Paired-hardware missing-key recovery, repeated watch restart, and system unpair
acceptance remain pending. Do not install a feature APK before this change lands
in canonical `gov/develop`.

Validation (2026-10-12, base `702ee4a`): offline `:app:testDebugUnitTest`
with `*Companion*`, `*PhoneManagedSettings*`, `*CompanionChat*`, and
`*CompanionPoint*` selectors passed **75 tests**, including **6 new bond-retention
tests**, with no failures. Offline `:app:assembleDebug` passed. The APK was not
installed; paired hardware acceptance is pending.

### WEARTAK-160: Single-watch phone-user map relay

The single-watch companion link relays remote user PLI in
`phone_user_cot` payloads `{cot: XML, phoneUid: nonblank}`. A
`phone_user_remove` payload `{targetUid, phoneUid}` removes only the matching
companion-owned map copy. WearOS stores this copy separately
from network CoT with the same UID, expires stale positions, clears the
companion snapshot when its BLE generation ends, and caps it at 512 users.
Only live `a-*` PLI with a TAK user identity is accepted; dropped points,
emergencies, and the watch or phone's own identity are rejected.

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

### Bonded Location Diagnostics (WEARTAK-165)

The **Bonded Location Diagnostics** switch is in Developer Preferences and is persisted independently of Developer Mode. Diagnostics run only after a versioned capability exchange over the authenticated, bonded, ready single-watch Companion BLE session; setup and gateway routes do not enable observation. Disabling the switch sends a session-scoped stop so both devices unregister diagnostics-only callbacks. An unsupported Companion peer receives no repeated capability probes, and a new connection negotiates a new session.

The watch observes fixes already delivered to WearTAK and registers GNSS status/raw-measurement callbacks only for an accepted diagnostics session. It does not request an additional location stream, change the authoritative PLI, or publish diagnostic data as CoT. The phone observes passive platform fixes plus GNSS callbacks; it does not take over ATAK location authority. Permission failures, disabled providers, inactive receivers, and unsupported callback data remain visible as Unavailable or Unknown rather than being treated as good. Quality states are conservative and are not a determination of jamming or spoofing.

The Developer Preferences diagnostics page shows watch and phone quality separately and provides diagnostic-only Entry/Exit markers, JSON export, and Clear. Local daily summaries retain no exact coordinates, device identifiers, or raw satellite identifiers; logs are bounded to 256 KiB per day and seven days of retention. Diagnostic messages are versioned, session-fenced, size-limited, rate-limited, and best-effort behind operational BLE traffic.

## Configuration and operation

The legacy `SettingsActivity` component redirects to `WearTAKSettingsActivity`.
Debug tools are a screen within settings, not a separately launchable activity.
ATOS CoT IPC authorizes the caller on every Binder transaction, before processing
the payload; service binding itself does not establish a trusted caller identity.

### Route point selection

Route lines and named point markers remain visible on the map. Selecting a point
opens its details, with Chat and Bloodhound range/bearing tracking available as
before. Selection and sender lookup use both the source server ID and route UID,
so matching route UIDs received from different servers cannot select each other's
points. Route parsing does not log raw CoT XML.

### Companion provisioning and optional phone management (WEARTAK-90)

The companion defaults to one-time provisioning: edit the watch callsign and server
settings, select a `.p12` certificate if needed, and tap **Send to Watch**. Wait for
the watch's successful save acknowledgement before disconnecting. Certificates and
server settings remain stored on the watch; a phone is not required afterwards.
One-time uploads do not lock watch settings or copy the phone's team and role.

**Manage watch settings from this phone** is opt-in. It copies phone identity and
resynchronizes on reconnect, with watch identity, reporting, and server edits locked.
The editing lock requires a live, successfully managed Companion session. Application-level
heartbeats run every 10 seconds; after 30 seconds without a matching response, the watch
unlocks local edits and resets the Companion transport, even if Samsung retains the shared
Bluetooth connection after ATAK exits or crashes. This detects loss of responsiveness,
not the cause of the outage. The saved management opt-in is retained, while the watch's
own identity, reporting, servers, and certificates are restored (WEARTAK-168).
A fresh successful managed-settings handshake re-applies the phone's values and restores
the lock; merely reconnecting or receiving an old heartbeat does not. Watch process/service
restart also requires a fresh handshake; if none arrives within 30 seconds, the watch
restores its own values. Older Companion builds without heartbeat support
cannot maintain the lock and must be updated alongside the watch. To return to
one-time provisioning, uncheck the option and send settings again; this releases
the lock, restores the watch's own values, and then applies the one-time upload, whose
servers and certificates stay. Unpairing also releases the lock and restores the watch's
own values.

### WEARTAK-161: Explicitly releasing phone management

Turning off **Companion App** in watch settings first clears the phone-managed
settings lock for its recorded companion peer, then disables the companion service.
Since WEARTAK-168, this stop action restores the watch's own callsign, team, role,
reporting settings, TAK servers, and certificates. A transient BLE
disconnect retains the editing lock only for the heartbeat grace period; service restart
requires a new handshake. Android unpairing also releases management and restores
the values saved before management began.
The release transaction serializes settings writes and fences managed updates for
that BLE generation, so a delayed settings push cannot relock the watch before its
release acknowledgement; management can be enabled again after reconnecting.
Settings snapshots, acknowledgements, and queued notifications retain their original
peer and connection generation. Work from an ended connection is discarded rather
than delivered to a replacement session. Management activation rechecks its session
and release fence inside the DataStore transaction, including after server refresh.
An accepted notification subscription starts a new logical companion generation:
Samsung may keep the shared physical Bluetooth link alive while ATAK closes and
reopens its GATT client. Reconnect therefore does not depend on a physical
disconnect callback to clear the previous session's management-release fence.

### WEARTAK-168: Restoring watch settings after phone management

This applies to the Companion's **Bond Single Watch** mode (managed settings). Before the
phone first overwrites the watch, the watch saves its own values for every setting the phone
writes: callsign, team (and team color), role, role category, the dynamic reporting strategy,
the constant reporting interval, and the TAK server list including `.p12` certificates and
passwords. Identity and reporting values are saved in DataStore. The server list is saved in
the same encrypted TAK server store as the live list; only a non-secret "snapshot saved" marker
is kept in DataStore. The snapshot survives a process restart, and repeated phone pushes during
management never overwrite it. Stationary, on-foot, vehicle, and alerting intervals are locked
but never written by the phone, so they are not changed.

**Configure Single Watch** (one-time upload) does not lock or snapshot anything; its servers,
certificates, and other settings stay on the watch. If a one-time upload arrives from the
bonded phone, the watch first restores its saved values and ends the bond, then applies the
upload, so the configured settings stick.

The saved values are restored, the Bluetooth name is recomputed from the restored callsign,
the TAK service is asked to reconnect with the restored servers, and a PLI refresh is
requested when any of these happen:
- the phone releases management or sends a one-time (unmanaged) upload;
- **Companion App** is turned off on the watch;
- the managing phone is unpaired;
- the 30-second heartbeat lease expires, or no managed handshake arrives within
  30 seconds of a service restart.

A disconnect shorter than the lease grace keeps the phone's values, so the identity does
not switch back and forth. Releasing management, turning off Companion App, and unpairing
also clear the management opt-in. Lease expiry keeps the opt-in, so a later managed handshake
saves the watch values again and re-applies the phone's values.

Watch edits are blocked while the lock is active. If the user changes one of these settings
while management is enabled but unlocked (for example, during the restart grace), the edit
also updates the saved value, so it is not lost on restore. The same applies to TAK server
edits. Locked settings are labeled **Bonded** (previously "Phone managed").

If a managed handshake saves the servers but then fails, the saved servers are restored once
the watch is no longer locked. Installs whose bond began before identity or server snapshots
existed restore only what they saved: reporting and the current identity and servers stay.

### WEARTAK-66: Interaction-triggered PLI refresh and identity updates

Changing callsign, team, or role (but not saving the same value), opening/resuming the map,
sending chat, and activating or clearing manual/physiological alerts request a new watch
location and then rebroadcast own PLI through the existing configured dispatcher, including
the companion when available. Alerts (including cancellations) and chat retain their
baseline send/queue behavior: existing alert location-freshness protections are unchanged,
and the additional PLI request adds no new waiting to those messages. A qualifying new
interaction fix also releases queued alerts using the existing dispatcher. The separate
extra PLI requires a new fix.

Identity changes also publish promptly using an already-usable watch fix no more than
3 seconds old, if one exists. Settings saved together coalesce over 150 milliseconds,
and PLI contains the latest persisted callsign, role, and team. This fast identity
announcement keeps the original fix timestamp; it does not pretend the position was
just measured. A concurrent new GPS request still publishes a qualifying new fix.
Without an already-fresh fix, identity publication waits for GPS (up to the bounded
request timeout), not the periodic reporting interval.

Overlapping interactions share a high-accuracy acquisition bounded to 15 seconds. The fix
must be usable and measured after the latest interaction; cached coordinates are not
re-stamped as a fresh fix. PLI uses persisted identity and is sent as soon as a qualifying
fix arrives, not literally instantly. Missing permission, provider failure, or no fix ends
the refresh without sending an extra PLI. This does not change periodic reporting,
reconnect behavior, or enable services/transports that are disabled.

Configure the TAK endpoint and identity/certificates in the app before attempting to connect. Service enablement is persisted in `DataRepository` and centrally evaluated by `ReactiveServiceManager` and `ServiceConfiguration`; a service will run only when its setting, permissions, and prerequisite conditions are satisfied.

The app starts essential behavior through its application/bootstrap receivers and coordinates most long-running work as foreground services. This is intentional: direct TAK connectivity, location tracking, sensor collection, and optional integrations are service-driven rather than tied to a single UI screen.

Chat is an online feature; the project does not provide an offline TAK chat queue.

Companion chat uses the existing watch inbox. Replayed message IDs are deduplicated
per sender, including duplicates within a batch. Messages without IDs use sender,
receipt timestamp, and text rather than timestamp alone.

### WEARTAK-118: Existing group conversations

Standalone TAK chat uses `__chat.id`/`chatroom` and the `chatgrp.uidN` member list:
named groups containing the watch merge all senders into one room, and replies keep
the room ID/title while targeting the most recently received member UIDs with
`marti/dest uid`. Groups without known reply recipients are rejected and logged,
never converted to a generic broadcast. Membership is learned from received chat,
not a group-management service; create/update groups in ATAK and include the watch's
device UID. Other private conversations and own-sender echoes are filtered out.
`All Chat Rooms` is a single explicit broadcast conversation, not a sender DM.

With a matching companion plugin, `chat_received` retains `conversationUid`,
`conversationTitle`, `isGroup`, and `participantUids`. Replies carry canonical `roomUid`,
`roomTitle`, `isGroup`, `msg`, and `cs`, plus legacy `rmUid`/`rmTitle` aliases.
Direct replies additionally carry `recipientUid`/`replyTo` with the same target UID;
group/broadcast replies omit these direct-only fields. The phone accepts `text` as
an alias of `msg`, rejects conflicting aliases, and resolves the existing ATAK group and its current
recipients. Missing/unwritable groups and empty recipient sets fail with phone logs,
without a broadcast fallback. Legacy envelopes without an explicit group flag remain
sender DMs. Existing direct chat and the explicit All Chat Rooms action remain supported.
Inbox navigation follows room UID rather than a changing list index.

The chat picker has two entry actions above the existing conversations:
**All Chat Rooms** opens the canonical broadcast room, including before any message
has arrived. **Select User for Chat** opens the map: tap a person and confirm **Chat with this
person** to create/open a private conversation in the original picker activity and
add it to the normal chat-room list. Cancel/back
creates no room and sends nothing. Selection temporarily shows the people layer
regardless of saved team/role filters without changing those preferences; markers
and alerts are not private-chat recipients. Live UID lookup prevents list reordering
from changing the selected person, and removed/self/invalid contacts cannot be selected.
Chat must be enabled and running; an unavailable service is reported.
Standalone initiated private chats route by recipient UID, not callsign. Companion
private sends resolve a real ATAK IndividualContact with a GeoChat endpoint and use
ATAK's native targeted chat API, preserving native phone conversation history.
Unknown/self/broadcast-like direct contacts are rejected, never broadcast.
Picker order is All Chat Rooms pinned first, Select User for Chat second, then
direct/named-group rooms by their latest received remote message descending.
Incoming messages update this order dynamically; local sends and older replayed
messages do not promote a room. Empty rooms follow rooms with received messages.
The broadcast room appears only in its pinned entry, not again in the sorted list.
BLE uses version-1 `msgType: chat_received` inbound and `msgType: chat` outbound,
with a `payload` object and message ID in `msgUid`/`payload.uid`. Inbound retains
`senderUid`, `senderCallsign`, `msg`, and `receivedAt` (epoch milliseconds).
`isGroup` absent/false stays direct-compatible; no group-owner field is needed.
The Android WearOS BLE path does not advertise Garmin `relay_status.chatGroups`:
only a verified group-aware Garmin transport adapter may announce that capability
for its matching session. BLE enqueue remains unacknowledged chat delivery.

This supports existing groups and initiating direct/broadcast chats, not named watch group creation, private-messenger support,
or historical ATAK chat synchronization. The companion relays incoming remote ATAK
messages while connected; phone-authored outgoing messages and phone-attributed watch
echoes are not mirrored back. BLE enqueue is not an end-to-end delivery acknowledgement.

Hardware acceptance (not yet performed): use two remote ATAK senders and one outsider.
In standalone mode include the watch UID in an existing named group, send from both
members, verify one room with both callsigns, and reply by preset and keyboard; only
members should receive the reply. Repeat with the paired phone UID in companion mode
and both updated builds. In each mode test direct chat alongside the group, All Chat
Rooms, replay/duplicate delivery, and a new room arriving while a group detail/reply
screen is open. Check room/read state and the reply destination. Remove/empty the ATAK
group before a companion reply and verify the phone logs a rejection with no unrelated
delivery. Disconnect/reconnect BLE and confirm existing chats resume without promising
offline history. Smoke-test paired-local point deletion to confirm WEARTAK-112 behavior.
In both modes, start from an empty inbox: open All Chat Rooms and send by keyboard
and preset, checking native send-to-all behavior. Tap Select User for Chat, cancel first, then
select one person (also with the saved people layer disabled), confirm, and send.
Only that person must receive the private chat; on companion also check ATAK's direct
conversation history. Reorder incoming contacts while the person detail is open,
then remove the selected contact and check that confirmation becomes unavailable.
Confirm existing room history/read state survives reopening either entry action.

Outgoing periodic and interaction-triggered PLI expires after twice the active reporting
interval plus 15 seconds (135 seconds for a 60-second interval). Interaction PLI retains
the location fix timestamp as the expiry baseline. Alert and marker expiry is unchanged.

Channel HTTPS requests automatically discover a legacy TAK server's TLS name when the configured address does not match its certificate. For example, the watch can keep connecting to `takserver.nctak.org` while validating the certificate against its exact DNS SAN, `takserver2.nctak.org`. Discovery requires a certificate trusted by the imported/enrollment CA that is not also trusted by the device's public roots, and exactly one supported, non-wildcard DNS SAN. Normal hostname validation (including the existing exact Common Name fallback for certificates without SANs) remains unchanged. The discovered identity is reused for channel requests until the TAK connection is reset; a different identity is not silently substituted during that connection. Discovery failures appear in channel errors and diagnostic logs.

This fallback does not change the CoT stream or enrollment connection. A Data Sync API client is not currently implemented in this app.

### TAK certificate enrollment

Username/password enrollment uses HTTPS port **8446** (`tls/config`, then
`tls/signClient/v2`) with normal platform certificate and hostname validation.
Configuration failures stop enrollment before CSR generation/signing. Signing
responses may be JSON or XML with a required `signedCert`, OpenTAKServer's optional
unnumbered `ca`, and optional numbered `caN` certificates (Base64 DER or PEM).
Authorities are retained as trusted entries; chains are leaf-first, then `ca`,
then numbered CAs in numeric order. Malformed nonblank CA values are rejected.
Registration errors identify the failed stage and distinguish HTTP 401
authentication rejection, other HTTP statuses (including 403), TLS, connectivity,
and invalid certificate data. Retry clears the previous error for that server.
Add/edit registration waits for the saved configuration before starting an attempt.
The dialog follows a stable server-ID request across runtime configuration refreshes;
failures replace the spinner, and retry starts an attempt even when settings are unchanged.
Offline, disabled, companion-managed, cancelled, or stopped-service attempts also
terminate registration rather than leaving it loading; a completed TLS handshake
publishes success. Restarting the TAK foreground service creates a fresh connection
scope and retires the prior service's work. Late teardown of an old instance cannot
cancel the replacement's connections.
This does **not** repair rejected credentials: HTTP 401 still requires valid
credentials/server-side authorization. Response bodies and certificate material
are not logged by enrollment.

### Dev Mode Stay Awake

Debug and release APKs include an opt-in **Stay awake** switch under **Dev Debug Tools** (tap the
version eight times in Preferences to reveal these tools). This standalone demo/development
session control does not require Wireless debugging or read its system setting. The switch
holds screen, CPU and Wi-Fi locks and keeps a Wi-Fi network actively requested, including
while viewing system settings. The request does not require Internet access, so local-only
hotspots can satisfy it. It shows an ongoing notification with a Stop action. It remains
enabled until you turn off the switch,
tap notification Stop, or the app process ends. Leaving the UI or swiping the recent task
does not stop the foreground service. The adjacent **Resume after update** checkbox is
off by default. When checked, an enabled session is remembered and restored after an
in-place app update on the same watch boot. Updates briefly release the locks while replacing
the process. Turning off Stay awake or tapping notification Stop clears this remembered
session. The service uses `START_NOT_STICKY`: ordinary process restarts and watch reboots
do not start it automatically. A remembered session can still resume on a later update
in the same boot unless explicitly stopped or update resumption is unchecked.
There is no timed expiry. The warning **Extra battery use. May heat watch.** appears only while
enabled. The Wi-Fi request is released on stop, service destruction, or startup failure.
It does not force Wi-Fi on or guarantee connectivity against RF conditions, hotspot
disconnects, or system overrides. It does not prevent an explicit screen-off action or
guarantee ADB/multicast delivery. The switch
is only available in the hidden Dev Mode menu.

### TAK SA multicast

With TAK SA Multicast enabled and the watch directly connected to Wi-Fi, WearTAK sends and
receives position/other CoT on the configured multicast endpoint (default `239.2.3.1:6969`)
and GeoChat on `224.10.10.1:17012`. It also receives direct ATAK points/other CoT on
`224.10.10.1:6969`, since ATAK uses that port for non-chat traffic to the advertised UDP contact.
Incoming traffic uses the same contact, marker, and chat
handling as TAK-server traffic; receiving chat requires the chat service to be enabled.
Multicast status indicates that the sender and all receivers are ready, not that a peer
has been discovered. The Wi-Fi access point must permit multicast between clients.
Multicast tracks local Wi-Fi independently of Android's default internet connection,
so cellular routing changes do not stop receivers on a connected radio Wi-Fi network.
While multicast is enabled, WearTAK requests local Wi-Fi so Wear OS does not shut it down
for lack of network demand, and holds a high-performance Wi-Fi lock to reduce radio
power-saving interruptions where supported by the OS. The request does not require
internet. Both the request and lock are released when multicast is disabled or the TAK
connection service stops. This uses the existing multicast toggle, increases battery use,
and does not keep the screen or CPU awake. It does not pin an SSID/BSSID, prevent roaming,
override OS power restrictions, or guarantee screen-off delivery. Multicast sockets remain
bound to the Wi-Fi network and are recreated when that network changes.
On Android 14 and newer, the OS maps high-performance locks to low-latency mode,
which only takes effect with the screen on and the app in the foreground. The active
local Wi-Fi request still remains in place with the screen off; this is not an OS
power-policy bypass. Connection loss, validation/suspension capability changes and
interface changes are logged without including SSIDs, credentials or IP addresses.
Reception supports both XML CoT and binary TAK Protocol v1 datagrams, including GeoChat
and ATAK contacts using friendly equipment/vehicle position types.
Multicast position broadcasts advertise the GeoChat UDP endpoint so ATAK can send direct
messages to the watch without a TAK server; server-bound broadcasts retain their server endpoint.
The receiver ignores datagrams sent from this device's local addresses, including markers
whose event UID differs from the watch UID. Binary decoding preserves custom XML details
such as `t-x-meshcfg`/`__meshtastic_cfg`; a plugin declaring `configShareUrl` can receive that
configuration through its validated settings interface.

### Plugin CoT

Plugins can opt in through their settings schema and enabled boolean values:
`forwardOutboundCot` receives watch-generated CoT as `cot.outbound`, while
`receiveInboundCot` receives incoming CoT as `cot.inbound`. Both actions carry `xml`;
incoming actions also carry `source` and `receivedAt`. Incoming delivery covers direct
TAK, multicast, SITX, and companion CoT IPC without changing their existing processing.
Plugin-injected CoT is processed locally but is not sent back into plugin bearers,
preventing reinjection loops. Unsupported CoT types can still be observed by opted-in plugins.

These subscriptions do not start or enable any network transport, and observing inbound
CoT does not retransmit it. A BLE-only configuration remains BLE-only. The companion BLE
protocol itself is unchanged; it does not currently provide a raw incoming CoT feed.
Plugin `requestCotDispatch` retains its existing TAK-server relay by default; a plugin
can declare `relayInboundToNetwork` and set it false for local-only injection. That relay
does not enable disconnected TAK servers or fan out to other transports.

## Architecture

### Paired Bloodhound tracking

While the ATAK companion is bonded and BLE-ready, starting Bloodhound tracking on
either device starts the same CoT target on the other. Ending that shared session on
either device stops tracking on both, without cancelling the underlying emergency,
dismissing its alert, or deleting its marker. Closing a screen is not a tracking stop.
The watch must have its Bloodhound service enabled and running; companion commands
do not override disabled services or missing permissions.

Commands use `bloodhound_control` envelopes with an `action`, `session_id`, and
`target_uid`. Starts carry the target callsign and coordinates, allowing the watch to
create a companion-owned navigation target if the UID is not already available.
Existing locally received targets are preferred. Start/stop acknowledgements use
`bloodhound_control_result`; rejection or a missing acknowledgement is surfaced rather
than claiming synchronization succeeded. Session correlation, bounded ended-session
history keyed by session and target UID, and echo suppression prevent old commands
from stopping a replacement track. A repeated stop is acknowledged only for its
original session/target pair.
Simultaneous starts converge on the lexicographically smaller session ID.
An `update` action carries changed target coordinates within the same session, so a
companion-only target can move without restarting tracking. Updates do not overwrite
an independently received local CoT target; a real incoming source can replace the
companion snapshot without ending its tracking session.

Disconnect leaves each device's local tracking unchanged and forgets the shared session.
Reconnect does not replay an old start or stop; a new tracking action is required to
synchronize again. This does not depend on the separate phone-to-watch alert forwarding
feature and does not retransmit alert cancellations.
The Bloodhound picker has a **Remove All** button above its targets. Its confirmation
screen offers **Remove All** and **Cancel**; Cancel or dismissing the dialog leaves
the list unchanged. Remove All removes all
current source copies of ordinary targets and locally dismisses remote emergency alerts,
stopping navigation to removed targets. Alert dismissals survive restarts and suppress
ongoing refreshes until the sender cancels. This does not send cancellation or acknowledgement
messages, delete ordinary map markers, or prevent new ordinary targets from arriving.

### Phone-to-watch points

Paired companion BLE accepts `phone_marker_cot` envelopes with original marker CoT
in `payload.cot`. Valid points appear on the map and in Bloodhound under
`Companion ATAK`, without a separate watch TAK connection. Blank UIDs, invalid
timestamps, non-finite coordinates and out-of-range latitude/longitude are rejected.
Original event/start/stale times are retained; missing start or stale defaults to
event time, as with existing marker ingestion.

BLE, direct TAK and SITX share serialized map/navigation ingestion. Source-owned
copies are deduplicated by UID using event time before receipt time. A stale
incoming marker removes only its source's copy at or before that event time,
including navigation to that removed copy; another source's target remains intact.
Source-scoped removal watermarks reject older and equal-time replay during the
watch process lifetime, including BLE reconnects. Disconnecting does not clear
received points. There is no offline replay queue or new automatic expiry policy.
Asynchronous repository failures are logged; cancellation is not treated as an error.

### WEARTAK-130: chat commands

Tool Preferences > Plugins > **Commands** (off by default) lets another TAK user change a few watch settings by sending a direct chat message. Only direct messages from callsigns in **Allowed Callsigns** (comma separated, case-insensitive) are acted on. Group and "All Chat Rooms" messages are ordinary chat. Messages from other senders, stale messages (older than 5 minutes) and replays are dropped silently.

Format: `#weartakcommand<name> key=value key2="value with spaces"`

| Command | Arguments | Effect |
| --- | --- | --- |
| `setcallsign` | `callsign` (1-32 letters, digits, space, `.`, `_`, `-`) | Sets the callsign. Refused when settings are managed by the phone. |
| `enrollserver` | `name`, `url` (host name or IP), `port` (default 8089) | Posts a notification on the watch. Tapping it opens the New Server screen pre-filled; the user enters the username and password there and they are stored like any other TAK server. Credentials are never accepted in chat. Refused when network settings are locked or phone-managed. |

Profile commands take the same values as the settings screens (case-insensitive) and validate everything before changing anything, so one bad value changes nothing:

| Command | Argument |
| --- | --- |
| `setteam` | `team=<name from the team list>` |
| `setrole` | `role=<role name>` (the role category is set automatically) |
| `setheight` | `height=5'8"` (feet 1-8, inches 0-11; `5'8` and `5-8` also work) |
| `setweight` | `weight=<1-1000>` |
| `setbirthyear` | `birthyear=<1900-current year>` |
| `setsex` | `sex=<Male, Female, N/A>` |
| `setusertype` | `usertype="Coalition Civilian"` |
| `setpants`, `setwaist` | `pants=<20-50>`, `waist=<20-60>` |
| `setprofile` | any combination of the fields above plus `callsign` |

The callsign, team and role fields (including in `setprofile`) are refused when settings are managed by the phone.

`#weartakcommandloadprofile name=odin-weartak` applies a preset saved on the watch as `Android/data/<package>/files/presets/<name>.json`. Presets are provisioned with adb and are never part of this repository, because they can hold server credentials:

```json
{
  "profile": { "callsign": "ODIN-WEARTAK", "team": "Dark Green", "role": "K9", "height": "5'8\"", "weight": "150" },
  "servers": [ { "name": "NCTAK", "url": "takserver.nctak.gov", "port": 8089, "username": "user", "password": "secret" } ]
}
```

The profile is validated and applied, then each server is added through the normal add-server path (servers already present are skipped). Names are lowercase letters, digits and `-`. The reply says only how many servers were added. Refused when network settings are locked.

The watch replies in chat with the outcome. Callsigns are not authenticated, so anyone on the same server can pick an allowed callsign; this is why server enrollment always needs the user at the watch. Command arguments are never logged.
### WEARTAK-112: explicit shared-point deletion

**Local paired-device mirrors only.** A watch deletion removes the selected local
point and its eligible paired-phone copy; a phone deletion removes its local point
and its eligible paired-watch copy. It never sends a deletion to TAK servers,
multicast, Ditto, SITX, plugins/mesh, or any other devices. This also holds when
BLE is unavailable: delete locally immediately, without queuing a network clear.
Incoming mirrored deletion neither broadcasts nor re-echoes deletion.

Both peers add an opaque `payload.revision` (UUID) to live marker envelopes.
Existing marker fields and XML remain unchanged; peers without this addition can
still receive points but cannot authorize explicit deletion.

```json
{"msg_type":"marker_delete","payload":{"targetUid":"shared-point-uid","revision":"previously-exchanged-revision"}}
```

Deletion means **remove the exact shared revision**, not delete every copy of a UID.
Only the current bonded, services-ready BLE connection can send/apply this envelope.
There is no timestamp fabrication, CoT `t-x-d-d` importer invocation, network delete
broadcast, offline queue, startup snapshot, or reconnect replay.
The receiver answers each well-formed deletion with a `marker_delete_ack` envelope
(`targetUid`, `revision`, `result` of `applied` or `rejected`) on the same connection.
The sender matches it to the pending deletion for that exact uid, revision and
connection generation, and records the outcome (`PointDeletionAcks`). A deletion with
no matching ack within 10 seconds is recorded as timed out, and an unknown, duplicate or
previous-connection ack is ignored. An ack confirms only that the peer applied or
rejected the deletion, not delivery to any TAK server. Peers that predate this ack
never answer, so their deletions report a timeout.
The revision must match a supported marker exchanged in this connection. New
shared updates replace it; unknown, duplicate, superseded, malformed, and
previous-connection deletions are inert and logged without payload contents.

On phone deletion, the watch removes the `companion-phone` map/Bloodhound copy
using its stored event-time watermark. Other-source copies survive. An original
watch-dropped point with the matching shared revision is also removed unless a
local edit/location update is pending; its dispatch backlog is cleared without
echoing a delete. Navigation to a removed source copy stops.
On watch deletion, the phone removes only the observed, supported shared marker
whose exported content still matches the exchanged revision, using ATAK's local
`removeFromGroup()` API, with echo suppression. Unshared native items, alerts,
PLI, routes, shapes, and groups are not eligible.

The existing watch received-marker Delete UX still removes all local copies of
that UID; it relays only when the selected visible copy belongs to the companion.
Dropped-marker Clear/Last/All removes local points immediately and emits
per-point explicit BLE deletions only for points shared this connection.
Legacy expired marker clear CoT is no longer generated or dispatched on any
transport; the outbound `MARKER_CLEAR` policy rejects every CoT route defensively.
Bloodhound menu Remove remains local target dismissal, not map deletion.
Transport enqueue is best-effort, not delivery confirmation: both apps must be
updated together and hardware acceptance is still required.

Regression coverage: `CompanionPhoneMarkerLifecycleTest`, `SourceAwareCotStoreTest`,
and companion `PhoneMarkerRelayTest` cover revision/session rejection, source
preservation, navigation cleanup, replay watermarks, echo release, and write failures.
`CotDispatchServicePointDeletionTest` verifies local clear and dispatch-backlog
cleanup without any external/network/legacy companion CoT calls.
`CotEndpointDispatcherTest`/`CotDispatchPolicyTest` verify clear CoT never reaches
any transport even with all endpoints available, both with and without ready BLE.

Paired-device acceptance (not performed by JVM tests): create a phone point, start
watch Bloodhound, then delete in ATAK: the companion-source point/target disappears
and navigation stops. Repeat with a watch-dropped point deleted on the watch and
verify the ATAK marker/tracking disappears. Edit the watch-origin marker in ATAK
and repeat both directions. With a same-UID TAK copy present, verify deleting the
phone copy leaves the TAK copy intact. Replay the old deletion after a newer
shared edit or reconnect: the newer point must remain, with no echo deletion or
startup replay. Unsupported PLI/emergency/route/shape/group items must remain.
With a third TAK/network peer displaying the same point, delete on either paired
device and verify that third peer retains its copy and receives no deletion packet.
### Remote emergencies

Incoming `b-a-o`, `b-a-o-*`, and ATAK's standard `b-a-g` CoT emergencies share one active
alert state for the map and Bloodhound. A yellow warning symbol distinguishes them from
ordinary contacts and markers, remains visible regardless of those map filters, and opens
the alert's Bloodhound details. Alerts appear first in the picker, with the sender and
emergency category.
Choosing navigation does not send an assignment acknowledgement or automated chat.

TAK server, SITX, companion IPC, and paired companion BLE ingress use the same parser
and lifecycle handling. The ATAK companion plugin forwards incoming phone emergencies
and cancellations only while a bonded watch is connected, using a `phone_emergency_cot`
envelope with original CoT XML in `payload.cot`. BLE alerts appear under source
`Companion ATAK` and do not require a separate TAK connection on the watch.
Disconnected alerts are not queued for replay by the plugin.
Disconnecting does not clear alerts already received by the watch. A cancellation
received after reconnect removes matching alert copies from both BLE and direct TAK
ingress; older replayed alerts cannot resurrect them. Local dismissals remain local
and suppress refreshes until a matching cancellation ends that alert session.
Asynchronous ingestion failures are logged as processing errors, not successful delivery.
ATAK's `*-9-1-1` alert UID, parent link, emergency text/type and cancellation flag are
supported, as are WearTAK's biometric alert details. Own alerts are ignored. Source-owned
copies are deduplicated by CoT UID; cancellation removes only emergency copies at or before
its event time, not the sender's ordinary PLI or markers. Cancellation timestamps survive
process restarts to reject delayed replay. Passing the sender's CoT stale deadline marks
an alert **Stale** in both the map label and Bloodhound instead of removing it. Valid
already-stale incoming alerts are accepted as last-known emergencies. A newer refresh
updates the deadline and clears Stale while fresh; explicit cancellation removes the alert.
A missed cancellation can therefore leave a stale alert visible; Stale does not mean
the emergency ended or that its location is live. Explicit local dismissal is still available;
source disconnect/removal clears only that source and preserves other copies.

Alerts without a usable location remain readable in Bloodhound, but are not placed on
the map and cannot guide navigation. A location-less refresh can retain that source's
previous valid location, marked as last-known when stale. **Dismiss locally** hides the
current emergency from both map and picker without sending any cancellation to the sender.
The persisted dismissal suppresses ongoing repeats and refreshes, including after restart.
Only a sender cancellation at or after the latest observed emergency update ends that
dismissal; a newer reactivation may then reappear, even when ATAK reuses the same UID.
Delayed older cancellations do not clear a dismissal. Ordinary point deletion is unchanged.

Receive/apply/stale-transition logs record why an alert was ignored (self, cancelled, older),
distinguishing missing ingress from rejected alerts during hardware verification.

The manual alert picker also supports ATAK's standard `911 Alert`, `Ring The Bell`,
`Geofence Breached`, and `In Contact` types alongside the existing custom options. These
map to `b-a-o-tbl`, `b-a-o-pan`, `b-a-g`, and `b-a-o-opn`, respectively, so standard
alerts can be sent and received without changing custom alert behavior. This feature builds
on the remote-emergency reception and display work pending in gov MR!327.

Selecting a preset manual alert sends that exact alert immediately, without a separate
Send tap. Custom text still uses Send. Alerts without an available route continue to use
the existing pending-alert queue.

Chat presets in the reply picker submit the selected text immediately to that conversation.
Typed chat text still uses the existing compose-and-Send flow. The picker keeps the
conversation UID so incoming list changes cannot redirect a preset to another recipient.

When the companion phone BLE session is ready, outbound PLI, chat, markers, and alerts
use the phone uplink only; the phone handles onward TAK, SITX, and multicast delivery.
The watch resumes its directly configured routes when the companion session disconnects.
The connection complication reports **Phone uplink active** while that route is in use.

The phone's Multiwatch Gateway uses a transient, session-qualified BLE negotiation; it
does not add a watch mode selector or settings UI. During that session the watch keeps
its own UID, callsign, team, role, report interval, and server/P12 configuration and does
not accept phone profile writes. Gateway CoT is limited to supported map points, user
positions, emergency/cancellation events, and GeoChat. Incoming chats are checked against
the watch's own UID/callsign and explicit recipients; unsupported CoT and profile/control
messages are rejected. The Gateway session and duplicate-event ledger clear when BLE is
no longer services-ready, after which the existing directly configured routes resume.

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
